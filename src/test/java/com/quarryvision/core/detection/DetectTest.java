package com.quarryvision.core.detection;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@DisabledIfEnvironmentVariable(named = "CI", matches = "true")
public class DetectTest {
    @Test
    public void runDetection() throws IOException {
        Path video = Path.of("E:/INBOX/loading_of_crushed_stone_2.mp4");
        System.out.println("Exists: " + Files.exists(video) + " size=" + Files.size(video));

        Assumptions.assumeTrue(Files.exists(video),
                                "Тест пропущен: файл не найден " + video);

        BucketDetector det = new BucketDetector(
                5,
                25,
                0.02,
                8
        );

        DetectionResult res = det.detect(video);
        System.out.println("Video: " + video);
        System.out.println("Events: " + res.events());
        System.out.println("FPS: " + res.fps());
        System.out.println("Frames: " + res.frames());
        res.timestampsMs().forEach(t ->
                System.out.println("Event @ " + t.toEpochMilli() + " ms"));
    }
}
