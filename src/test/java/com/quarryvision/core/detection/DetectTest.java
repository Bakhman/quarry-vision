package com.quarryvision.core.detection;

import com.quarryvision.app.Config;
import org.bytedeco.javacpp.Loader;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.global.opencv_videoio;
import org.bytedeco.opencv.opencv_core.Size;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

// @DisabledIfEnvironmentVariable(named = "CI", matches = "true")
public class DetectTest {
    // фиксированный кэш, чтобы JavaCPP не распаковывал заново каждый запуск
    static {
        System.setProperty("org.bytedeco.javacpp.cachedir",
                System.getProperty("user.home") + "/.javacpp-cache");
        // прелоад нужных модулей OpenCV
        Loader.load(opencv_core.class);
        Loader.load(opencv_imgproc.class);
        Loader.load(opencv_videoio.class);
        // 🔹 быстрый лимит по умолчанию (если не задан через -Dqv.maxFrames)
        System.setProperty("qv.maxFrames",
                System.getProperty("qv.maxFrames", "60")); // ~6 кадров при step=10
    }
    @Test
    @Timeout(10) // sec
    public void runDetection() throws IOException {
        // 1) явный путь через -Dqv.video=... (локальные прогоны)
        String fromProp = System.getProperty("qv.video");
        if (fromProp != null && !fromProp.isBlank()) {
            Path p = Path.of(fromProp);
            Assumptions.assumeTrue(Files.exists(p), "skip: file not found " + p);
            run(p);
            return;
        }

        // 2) CI/по умолчанию — ресурс в classpath: src/test/resources/video/test.mp4
        try (InputStream in = getClass().getResourceAsStream("/video/test.mp4")) {
            if (in == null) throw new IOException("Missing test resource: /video/test.mp4");;
            Path tmp = Files.createTempFile("qv-test-", ".mp4");
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            tmp.toFile().deleteOnExit();
            run(tmp);
        }
    }

    private static void run(Path video) {
        var dc = Config.load().detection();
        BucketDetector det = new BucketDetector(
                dc.stepFrames(), dc.diffThreshold(), dc.eventRatio(),
                dc.cooldownFrames(), dc.minChangedPixels(),
                new Size(dc.morphW(), dc.morphH()));
        DetectionResult res = det.detect(video);

        System.out.println("Video: " + video);
        System.out.println("Events: " + res.timestampsMs().size());
        System.out.println("FPS: " + res.fps());
        System.out.println("Frames: " + res.frames());
    }
}
