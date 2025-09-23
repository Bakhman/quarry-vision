package com.quarryvision.core.detection;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

public class DetectTest {
    @Test
    public void runDetection() {
        Path video = Path.of("E:/INBOX/loading_of_crushed_stone_2.mp4");
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
