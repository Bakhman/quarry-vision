package com.quarryvision.core.ocr;

import net.sourceforge.tess4j.ITessAPI;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import net.sourceforge.tess4j.Word;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Базовый сервис OCR c Tess4J.
 * Инициализация через datapath (каталог tessdata) и languages (напр. "eng" или "eng+rus")
 * На этом этапе работаем с BufferedImage. Конвертацию из OpenCV Mat добавим на шаге интеграции с детекцией.
 */
public final class OcrService implements OcrEngine {
    private static final Logger log = LoggerFactory.getLogger(OcrService.class);

    private enum Mode { FAST, AUDIT }


    private final Tesseract tess;
    private final int basePsm;
    private final int baseOem;
    private final Mode mode;

    public static final class Config {
        public final boolean enabled;
        public final String datapath;  // путь к каталогу с *.traineddata
        public final String languages; // "eng", "eng+rus"
        public final int psm;          // Page Segmentation Mode
        public final int oem;          // OCR Engine Mode

        public Config(boolean enabled, String datapath, String languages, int psm, int oem) {
            this.enabled = enabled;
            this.datapath = Objects.requireNonNull(datapath, "datapath");
            this.languages = Objects.requireNonNull(languages, "languages");
            this.psm = psm;
            this.oem = oem;
        }
    }
    public OcrService(Config cfg) {
        if (!cfg.enabled) {
            // Заглушка: инициализируем, но сразу выходим на no-op.
            this.tess = null;
            this.basePsm = 0;
            this.baseOem = 0;
            this.mode = Mode.FAST;
            log.info("OCR: disabled in config");
            return;
        }
        // Путь к tessdata: -Dqv.ocr.tessdataDir → cfg.datapath → ENV TESSDATA_PREFIX
        String overrideDir = System.getProperty("qv.ocr.tessdataDir");
        String dir = (overrideDir != null && !overrideDir.isBlank()) ? overrideDir : cfg.datapath;
        if (dir == null || dir.isBlank()) dir = System.getenv("TESSDATA_PREFIX");
        Path dp = Path.of(Objects.requireNonNull(dir, "tessdataDir is required"))
                .toAbsolutePath().normalize();
        if (!Files.isDirectory(dp)) {
            throw new IllegalStateException("tessdataDir not found: " + dp);
        }
        // Языки/PSM/OEM: допускаем override через -D
        String languages = System.getProperty("qv.ocr.lang", cfg.languages);
        int psm = Integer.getInteger("qv.ocr.psm", cfg.psm);
        int oem = Integer.getInteger("qv.ocr.oem", cfg.oem);
        String whitelist = System.getProperty(
                "qv.ocr.whitelist",
                // латиница + кириллица-двойники + цифры
                "ABEKMHOPCTYXАВЕКМНОРСТУХ0123456789" );

        String modeProp = System.getProperty("qv.ocr.mode", "fast");
        Mode mode = "audit".equalsIgnoreCase(modeProp) ? Mode.AUDIT : Mode.FAST;

        Tesseract t = new Tesseract();
        t.setDatapath(dp.toString());
        t.setLanguage(languages);
        t.setPageSegMode(psm);
        t.setOcrEngineMode(oem);
        // узкий алфавит и отключение словарей + DPI для стабильности
        if (whitelist != null && !whitelist.isBlank()) {
            t.setVariable("tessedit_char_whitelist", whitelist);
        }
        t.setVariable("load_system_dawg", "F");
        t.setVariable("load_freq_dawg", "F");
        t.setVariable("user_defined_dpi", "300");
        t.setVariable("preserve_interword_spaces", "1");

        this.tess = t;
        this.basePsm = psm;
        this.baseOem = oem;
        this.mode = mode;
        log.info("OCR: init datapath={} languages={} psm={} oem={} mode={}",
                dp, languages, psm, oem, mode);
    }

    /** Простой OCR всего изображения. Возвращает trimmed-текст без внутренних переводов строк */
    public Optional<String> readText(BufferedImage image) {
        if (tess == null || image == null) return Optional.empty();
        try {
            String raw = tess.doOCR(image);
            if (raw == null) return Optional.empty();
            String norm = raw.replace('\n', ' ').replace('\r', ' ').trim();
            return norm.isEmpty() ? Optional.empty() : Optional.of(norm);
        } catch (TesseractException e) {
            log.warn("OCR: doOCR failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** Временная утилита для локальной проверки из файла. */
    @Override
    public Optional<String> readText(File imageFile) {
        if (tess == null || imageFile == null) return Optional.empty();
        try {
            BufferedImage img = ImageIO.read(imageFile);
            return readText(img);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Возвращает самый уверенный токен [A-Z0-9]{3,10}. */
    @Override
    public Optional<String> readBestToken(BufferedImage bi) {
        if (tess == null || bi == null) return Optional.empty();
        try {
            BufferedImage prepared = preparedForOcr(bi);
            // применяем whitelist и DPI перед распознаванием
            final String wl = System.getProperty("qv.ocr.whitelist",
                    "ABEKMHOPCTYXАВЕКМНОРСТУХ0123456789");
            try { tess.setVariable("tessedit_char_whitelist", wl); } catch (Exception ignore) {}
            try { tess.setVariable("user_defined_dpi", "300"); } catch (Exception ignore) {}
            String best = bestToken(prepared);
            java.util.List<String> candidates = new java.util.ArrayList<>();
            // первичная валидация best по whitelist
            if (best != null) {
                String checked = cleanByWhitelist(best, wl);
                if (checked != null) candidates.add(checked);
                else best = null; // не прошёл фильтр →  fallback
            }
            // Fallback: если пусто ИЛИ только цифры, пробуем другой PSM
            if (best == null || best.matches("\\d+")) {
                int prev = basePsm;
                try {
                    // свип по нескольким PSM
                    int[] psms = (mode == Mode.AUDIT)
                            ? new int[]{8, 7, 6, 13} // WORD → LINE → BLOCK → RAW_LINE
                            : new int[]{8, 7};       // урезанный FAST-режим

                    for (int psm : psms) {
                        tess.setPageSegMode(psm);
                        String alt = bestToken(prepared);
                        if (alt != null && !alt.isBlank()) {
                            String checked  = cleanByWhitelist(alt, wl);
                            if (checked != null) { candidates.add(checked); break; }
                        }
                    }
                } finally {
                    tess.setPageSegMode(prev);
                }
            }
            // Финальный fallback: общий OCR-текст с фильтрацией по whitelist
            // В FAST-режиме отключаем тяжёлые doOCR-варианты и работаем только через getWords/PSM.
            if (mode == Mode.AUDIT) {
                String raw = safeDoOcr(prepared);
                String cleaned = cleanByWhitelist(raw, wl);
                if (cleaned != null) candidates.add(cleaned);
                // попытка на инвертированном изображении
                BufferedImage inv = invertBinary(prepared);
                raw = safeDoOcr(inv);
                cleaned = cleanByWhitelist(raw, wl);
                if (cleaned != null) candidates.add(cleaned);

                // doOcr на сером апскейле
                BufferedImage gray = upscaledGray(bi);
                raw = safeDoOcr(gray);
                cleaned = cleanByWhitelist(raw, wl);
                if (cleaned != null) candidates.add(cleaned);
            }
            // финальный выбор: самый длинный валидный
            String chosen = candidates.stream()
                    .filter(s -> s.length() >= 3)
                    .max(Comparator.<String>comparingInt(OcrService::plateShapeScore)
                            .thenComparingInt(String::length))
                    .orElse(null);
            return Optional.ofNullable(chosen);
        } catch (Exception e) {
            log.debug("OCR: best-token failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    // ---- вспомогательные ----
    private String safeDoOcr(BufferedImage img) {
        try {
            String t = tess.doOCR(img);
            return (t == null || t.isBlank()) ? null : t;
        } catch (Exception e) {
            return null;
        }
    }

    private String cleanByWhitelist(String raw, String wl) {
        if (raw == null) return null;
        String cleaned = raw.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9А-ЯЁ]", "");
        if (cleaned.length() < 3) return null;
        boolean hasDigit = cleaned.chars().anyMatch(ch -> ch >= '0' && ch <= '9');
        boolean hasLetter = cleaned.chars().anyMatch(ch -> (ch >= 'A' && ch <= 'Z') || (ch >= 'А' && ch <= 'Я') || ch=='Ё');
        if(!hasDigit || ! hasLetter) return null;
        // если whitelist задан, требуем хотя бы одно совпадение
        if (wl != null && !wl.isBlank()) {
            String keepRe = "[" + wl + "]+";
            if (!cleaned.matches(".*" + keepRe + ".*")) return null;
        }
        return cleaned;
    }

    private static BufferedImage invertBinary(BufferedImage src) {
        int W = src.getWidth(), H = src.getHeight();
        BufferedImage dst = new BufferedImage(W, H, BufferedImage.TYPE_BYTE_BINARY);
        for (int y=0; y<H; y++)
            for (int x=0; x<W; x++) {
                int rgb = src.getRGB(x,y);
                int v = (rgb & 0x00FFFFFF) ==0x00FFFFFF ? 0x000000 : 0xFFFFFF;
                dst.setRGB(x, y, (0xFF<<24) | v);
            }
        return dst;
    }

    /** Серый апскейл без порога: как в preparedForOcr, но без Отсу. */
    private BufferedImage upscaledGray(BufferedImage src) {
        int w = src.getWidth(), h = src.getHeight();
        BufferedImage gray = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g0 = gray.createGraphics();
        g0.drawImage(src, 0, 0, null);
        g0.dispose();
        int minW = 420;
        if (w >= minW) return gray;
        int newW = minW;
        int newH = Math.max(1, (int)Math.round(h * (newW / (double) w)));
        BufferedImage scaled = new BufferedImage(newW, newH, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.drawImage(gray, 0, 0, newW, newH, null);
        g.dispose();
        return scaled;
    }

    private String bestToken(BufferedImage img) {
        var words = tess.getWords(img, ITessAPI.TessPageIteratorLevel.RIL_WORD);
        String best = null;
        int conf = -1;
        for (Word w : words) {
            String s = w.getText();
            if (s == null) continue;
            String norm = s.toUpperCase().replaceAll("[^A-Z0-9А-ЯЁ]", "");
            if (norm.length() < 3 || norm.length() > 10) continue;
            if (w.getConfidence() > conf) {
                conf = (int) w.getConfidence();
                best = norm;
            }
        }
        return best;
    }

    /** Подготовка: серый → апскейл до minWidth=420 → Отсу. */
    private static BufferedImage preparedForOcr(BufferedImage src) {
        int w = src.getWidth(), h = src.getHeight();
        // to grayscale
        BufferedImage gray = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g0 = gray.createGraphics();
        g0.drawImage(src, 0, 0, null);
        g0.dispose();
        // up-scale if too small
        int minW = 420;
        BufferedImage scaled = gray;
        if (w < minW) {
            int newW = minW;
            int newH = Math.max(1, (int) Math.round(h * (newW / (double) w)));
            scaled = new BufferedImage(newW, newH, BufferedImage.TYPE_BYTE_GRAY);
            Graphics2D g = scaled.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(gray, 0, 0, newW, newH, null);
            g.dispose();
        }

        // Otsu threshold
        int W = scaled.getWidth(), H = scaled.getHeight();
        int[] hist = new int[256];
        for (int y=0; y<H; y++)
            for (int x=0; x<W; x++) {
                hist[scaled.getRaster().getSample(x, y, 0)]++;
            }
        int total = W*H, sum=0;
        for (int t=0;t<256;t++) sum += t*hist[t];
        int sumB=0, wB=0;
        double maxVar = -1;
        int thr = 127;
        for (int t = 0; t < 256; t++) {
            wB += hist[t]; if( wB==0) continue;
            int wF = total - wB; if (wF==0) break;
            sumB += t*hist[t];
            double mB = sumB / (double) wB;
            double mF = (sum - sumB) / (double) wF;
            double varBetween = wB * wF * (mB - mF) * (mB - mF);
            if (varBetween > maxVar) { maxVar = varBetween; thr = t; }
        }
        BufferedImage bin = new BufferedImage(W, H, BufferedImage.TYPE_BYTE_BINARY);
        for (int y=0; y<H; y++)
            for (int x=0; x<W; x++){
                int v = scaled.getRaster().getSample(x,y,0);
                int b = (v >= thr ? 0xFFFFFF : 0X000000);
                bin.setRGB(x, y, (0xFF<<24) | b);
            }
        return bin;
    }

    /** Приоритет формы: LLDDDDLL или LDDDLL[region]. Больше = лучше. */
    private static int plateShapeScore(String s) {
        int score = 0;
        // generic: LL DDDD LL
        if (s.matches("^[A-Z]{2}\\d{4}[A-Z]{2}$")) score += 200;
        // ru score: L DDD LL, регион опционально
        if (s.matches("^[A-Z]\\d{3}[A-Z]{2}(\\d{2,3})?$")) score += 200;
        // легкий бонус за идеальную длину 8
        if (s.length() == 8) score += 10;
        return score;
    }
}
