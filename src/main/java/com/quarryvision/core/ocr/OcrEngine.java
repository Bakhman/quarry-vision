package com.quarryvision.core.ocr;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Optional;

public interface OcrEngine {
    Optional<String> readText(BufferedImage bi);
    Optional<String> readText(File imageFile);
    /** Самый уверенный токен по словам Tesseract. */
    Optional<String> readBestToken(BufferedImage bi);
}
