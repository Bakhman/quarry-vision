package com.quarryvision.core.ocr;

import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.File;
import java.util.Objects;
import java.util.Optional;

/**
 * Базовый сервис OCR c Tess4J.
 * Инициализация через datapath (каталог tessdata) и languages (напр. "eng" или "eng+rus")
 * На этом этапе работаем с BufferedImage. Конвертацию из OpenCV Mat добавим на шаге интеграции с детекцией.
 */
public class OcrService {
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
            return;
        }
        File dp = new File(cfg.datapath);
        if (!dp.exists() || !dp.isDirectory()) {
            throw new IllegalArgumentException("tessdata path not found: " + dp.getAbsolutePath());
        }
        Tesseract t = new Tesseract();
        t.setDatapath(dp.getAbsolutePath());
        t.setLanguage(cfg.languages);
        // Доп.параметры движка
        // PSM/OEM
        t.setPageSegMode(cfg.psm);
        t.setOcrEngineMode(cfg.oem);
        this.tess = t;
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
            return Optional.empty();
        }
    }

    /** Временная утилита для локальной проверки из файла. */
    public Optional<String> readText(File imageFile) {
        if (tess == null || imageFile == null) return Optional.empty();
        try {
            BufferedImage img = ImageIO.read(imageFile);
            return readText(img);
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
