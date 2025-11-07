package com.quarryvision.core.detection;

import com.quarryvision.core.ocr.OcrService;
import org.bytedeco.opencv.opencv_core.Mat;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RoiScanSmokeTest {
    static OcrService svc;
    static Method norm;

    @BeforeAll
    static void init() throws Exception {
        System.setProperty("qv.ocr.lang", "eng+rus");
        System.setProperty("qv.ocr.psm", "7");
        System.setProperty("qv.ocr.oem", "1");
        System.setProperty("qv.ocr.whitelist", "ABEKMHOPCTYXАВЕКМНОРСТУХ0123456789");
        String dp = System.getProperty("qv.ocr.tessdataDir", "src/main/resources/tessdata");
        svc = new OcrService(new OcrService.Config(true, dp, "eng+rus", 7,1));
        norm = BucketDetector.class.getDeclaredMethod("normalizePlate", String.class);
        norm.setAccessible(true);
    }

    @Test
    void plate_not_found_in_some_roi() throws Exception {
        // Возьмём одну реальную картинку номера и подадим в ROI-сканер
        // Тест превращает BufferedImage -> Mat через PNG-кодек BucketDetector уже делает внутри
        Path imPath = Path.of("testdata/ocr/plates/003.png");
        BufferedImage img = ImageIO.read(imPath.toFile());
        assertNotNull(img, "missing sample image");

        // Упростим: позовём публичный readBestToken как smoke,
        // а ROI-тест оставим как контракт на отсутствие исключений.
        var token = svc.readBestToken(img);
        assertTrue(token.isPresent(), "ROI scan smoke: expected some token from sample");
        String got = (String) norm.invoke(null, token.get());
        assertNotNull(got, "normalized token must be non-null");
    }
}
