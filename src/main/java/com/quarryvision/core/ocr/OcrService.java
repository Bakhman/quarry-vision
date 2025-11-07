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
import java.util.Objects;
import java.util.Optional;

/**
 * Базовый сервис OCR c Tess4J.
 * Инициализация через datapath (каталог tessdata) и languages (напр. "eng" или "eng+rus")
 * На этом этапе работаем с BufferedImage. Конвертацию из OpenCV Mat добавим на шаге интеграции с детекцией.
 */
public final class OcrService implements OcrEngine {
    private static final Logger log = LoggerFactory.getLogger(OcrService.class);
    private final Tesseract tess;
    private final int basePsm;
    private final int baseOem;

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
        this.basePsm = cfg.psm;
        this.baseOem = cfg.oem;
        log.info("OCR: init datapath={} languages={} psm={} oem={}",
                dp, languages, psm, oem);
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
            String best = bestToken(prepared);
            // Fallback: если пусто ИЛИ только цифры, пробуем другой PSM
            if (best == null || best.matches("\\d+")) {
                int prev = basePsm;
                try {
                    // чаще помогает PSM 8 (SINGLE_WORD) или 13 (RAW_LINE). Начнём с 8.
                    tess.setPageSegMode(8);
                    String alt = bestToken(prepared);
                    if (alt != null && !alt.isBlank()) best = alt;
                    if (best == null || best.matches("\\d+")) {
                        tess.setPageSegMode(13);
                        alt = bestToken(prepared);
                        if (alt != null && !alt.isBlank()) best = alt;
                    }
                } finally {
                    tess.setPageSegMode(prev);
                }
            }
            return Optional.ofNullable(best);
        } catch (Exception e) {
            log.debug("OCR: best-token failed: {}", e.getMessage());
            return Optional.empty();
        }
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

    /** Подготовка: серый → апскейл до minWidth=420 → контраст-stretch → Отсу. */
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
        int total = W*H, sum=0; for (int t=0;t<256;t++) sum += t*hist[t];
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
}
