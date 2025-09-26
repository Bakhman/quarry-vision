package com.quarryvision.core.detection;

import org.bytedeco.opencv.opencv_core.Size;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

// @DisabledIfEnvironmentVariable(named = "CI", matches = "true")
public class DetectTest {
    @Test
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
            Assumptions.assumeTrue(in != null, "skip: /video/test.mp4 not on classpath");
            Path tmp = Files.createTempFile("qv-test-", ".mp4");
            Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            tmp.toFile().deleteOnExit();
            run(tmp);
        }
    }

    private static void run(Path video) {
        BucketDetector det = new BucketDetector(12, 40, 0.08, 30,
                8000, new Size(3,3));
        DetectionResult res = det.detect(video);

        System.out.println("Video: " + video);
        System.out.println("Events: " + res.timestampsMs().size());
        System.out.println("FPS: " + res.fps());
        System.out.println("Frames: " + res.frames());
    }
}
