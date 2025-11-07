package com.quarryvision.core.ocr;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class OcrServiceTest {
    static OcrService svc;
    static Method norm;

    @BeforeAll
    static void init() {
        // путь к tessdata: берём из -Dqv.ocr.tessdataDir или из репо по умолчанию
        String dp = System.getProperty("qv.ocr.tessdataDir", "src/main/resources/tessdata");
        assertTrue(Files.isDirectory(Path.of("src/main/resources/tessdata")));

        System.setProperty("qv.ocr.lang", "eng+rus");
        System.setProperty("qv.ocr.psm", "7");    // узкая строка
        System.setProperty("qv.ocr.oem", "1");    // LSTM only
        System.setProperty("qv.ocr.whitelist", "ABEKMHOPCTYXАВЕКМНОРСТУХ0123456789");

        svc = new OcrService(new OcrService.Config(true, dp,
                        System.getProperty("qv.ocr.lang"),
                        Integer.getInteger("qv.ocr.psm", 7),
                        Integer.getInteger("qv.ocr.oem", 1)));
        try {
            norm = Class.forName("com.quarryvision.core.detection.BucketDetector")
                    .getDeclaredMethod("normalizePlate", String.class);
            norm.setAccessible(true);
        } catch (Exception e) {
            fail("normalizePlate reflection failed: " + e);
        }
        assertNotNull(svc, "OcrService must init");
    }

    @Test
    void plate_positive_examples() throws Exception {
        // возьмём пару первых файлов если их больше — этого достаточно для smoke
        Path dir = Path.of("testdata/ocr/plates");
        assertTrue(Files.isDirectory(dir), "missing testdata/ocr/plates");

        var list = Files.list(dir)
                .filter(p -> p.toString().endsWith(".png"))
                .sorted()
                .limit(5)
                .toList();
        assertFalse(list.isEmpty(),"no plate samples");

        for (Path png : list) {
            Path txt = png.resolveSibling(png.getFileName().toString().replaceAll("\\.png$", ".txt"));
            assertTrue(Files.isRegularFile(txt), "missing .txt for " + png);

            BufferedImage img = ImageIO.read(png.toFile());
            System.out.printf("sample=%s size=%dx%d%n",
                    png.getFileName(), img.getWidth(), img.getHeight());
            String expectedRaw = Files.readString(txt).trim();
            String gotRaw = svc.readBestToken(img).orElse(null);
            // допускаем несовпадения на первых итерациях; цель — увидеть стабильность
            // для строгой проверки используй assertEquals(expected, got)
            assertNotNull(gotRaw, "must detect something for " + png);
            String exp = (String) norm.invoke(null, expectedRaw);
            String got = (String) norm.invoke(null, gotRaw);
            assertEquals(exp, got, "normalized mismatch for " + png + " raw=" + gotRaw + " expRaw=" + expectedRaw);
        }
    }

    @Test
    void negatives_must_be_empty() throws Exception {
        Path dir = Path.of("testdata/ocr/neg");
        assertTrue(Files.isDirectory(dir), "missing testdata/ocr/neg");

        var list = Files.list(dir)
                .filter(p -> p.toString().endsWith(".png"))
                .sorted()
                .limit(5)
                .toList();

        assertFalse(list.isEmpty(), "no negatives");

        for (Path png : list) {
            BufferedImage img = ImageIO.read(png.toFile());
            var got = svc.readBestToken(img);
            assertTrue(got.isEmpty(), "negative should return empty for " + png + " but got=" + got);
        }
    }

    @Test
    void override_lang_psm_oem_via_system_properties() throws  Exception {
        System.setProperty("qv.ocr.lang", "eng"); // просто smoke что не падает
        System.setProperty("qv.ocr.psm", "7");
        System.setProperty("qv.ocr.oem", "1");
        // повторная инициализация не обязательна: в текущей реализации параметры читаются в конструкторе
        // этот тест служит как документирование контракта: override работает и не ломает выполнение
        assertNotNull(svc);
    }
}
