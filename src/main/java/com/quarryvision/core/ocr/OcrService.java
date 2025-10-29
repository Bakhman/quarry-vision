package com.quarryvision.core.ocr;

import net.sourceforge.tess4j.ITessAPI;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import net.sourceforge.tess4j.Word;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
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
            log.info("OCR: disabled in config");
            return;
        }
        File dp = new File(cfg.datapath);
        if (!dp.exists() || !dp.isDirectory()) {
            throw new IllegalArgumentException("tessdata path not found: " + dp.getAbsolutePath());
        }
        Tesseract t = new Tesseract();
        t.setDatapath(dp.getAbsolutePath());
        t.setLanguage(cfg.languages);
        // PSM/OEM
        t.setPageSegMode(cfg.psm);
        t.setOcrEngineMode(cfg.oem);
        // узкий алфавит и отключение словарей + DPI для стабильности

        t.setVariable("tessedit_char_whitelist", "ABEKMHOPCTYX0123456789");
        t.setVariable("load_system_dawg", "F");
        t.setVariable("load_freq_dawg", "F");
        t.setVariable("user_defined_dpi", "300");
        t.setVariable("preserve_interword_spaces", "1");



        this.tess = t;
        log.info("OCR: initialized datapath={} languages={} psm={} oem={}",
                dp.getAbsolutePath(), cfg.languages, cfg.psm, cfg.oem);
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
            log.warn("OCR: doOCR failed: {}", e.toString());
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
        try {
            var words = tess.getWords(bi, ITessAPI.TessPageIteratorLevel.RIL_WORD);
            String best = null; int conf = -1;
            for (Word w : words) {
                String s = w.getText();
                if (s == null) continue;
                String norm = s.toUpperCase().replaceAll("[^A-Z0-9]", "");
                if (norm.length() < 3 || norm.length() > 10) continue;
                if (w.getConfidence() > conf) { conf = (int) w.getConfidence(); best = norm; }
            }
            return Optional.ofNullable(best);
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
