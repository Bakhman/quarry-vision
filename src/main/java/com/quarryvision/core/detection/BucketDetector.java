package com.quarryvision.core.detection;

import javafx.scene.effect.GaussianBlur;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.opencv_core.Size;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.global.opencv_videoio;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_videoio.VideoCapture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Простейший «детектор ковшей»: анализирует разности кадров,
 * при превышении порога считает событие.
 */
public final class BucketDetector {
    private static final Logger log = LoggerFactory.getLogger(BucketDetector.class);

    private final int stepFrames;       // шаг по кадрам, например 3..5
    private final int diffThreshold;    // порог бинаризации 15..40
    private final double eventRatio;    // доля «белых» пикселей для события, например 0.02
    private final int cooldownFrames;   // анти-дребезг, минимум кадров между событиями

    public BucketDetector(int stepFrames, int diffThreshold, double eventRatio, int cooldownFrames) {
        this.stepFrames = Math.max(1, stepFrames);
        this.diffThreshold = Math.max(1, diffThreshold);
        this.eventRatio = Math.max(1e-4, eventRatio);
        this.cooldownFrames = Math.max(0, cooldownFrames);
    }

    public DetectionResult detect(Path videoPath) {
        try (VideoCapture cap = new VideoCapture(videoPath.toString())) {
            if (!cap.isOpened()) {
                log.error("VideoCapture cannot open: {}", videoPath);
                return new DetectionResult(videoPath, 0, List.of(), 0.0, 0);
            }
            double fps = cap.get(opencv_videoio.CAP_PROP_FPS);
            if (!(fps > 1e-3)) fps = 25.0;
            long frameCount = (long) cap.get(opencv_videoio.CAP_PROP_FRAME_COUNT);

            Mat prev = new Mat();
            Mat frame = new Mat();
            Mat grayPrev = new Mat();
            Mat gray = new Mat();
            Mat diff = new Mat();

            List<Instant> stamps = new ArrayList<>();
            long lastEventFrame = -cooldownFrames - 1;

            // первый кадр
            if (!cap.read(prev) || prev.empty()) {
                log.error("First frame is empty: {}", videoPath);
                return new DetectionResult(videoPath, 0, List.of(), fps, frameCount);
            }
            opencv_imgproc.cvtColor(prev, grayPrev, opencv_imgproc.COLOR_BGR2GRAY);
            opencv_imgproc.GaussianBlur(grayPrev, grayPrev, new Size(5, 5), 0);

            long idx = 1;
            while (true) {
                if (!cap.read(frame) || frame.empty()) {
                    log.warn("Empty frame at idx={} file={}", idx, videoPath);
                    break;
                }
                // шаг через несколько кадров
                for (int s = 1; s < stepFrames; s++) {
                    if (!cap.read(frame) || frame.empty()) { break; }
                    idx++;
                }

                // после шага могли получить пустой кадр
                if (frame == null || frame.empty()) {
                    log.warn("Empty frame after stepping at idx={} file={}", idx, videoPath);
                    break;
                }

                opencv_imgproc.cvtColor(frame, gray, opencv_imgproc.COLOR_BGR2GRAY);
                opencv_imgproc.GaussianBlur(gray, gray, new Size(5, 5), 0);

                opencv_core.absdiff(gray, grayPrev, diff);
                opencv_imgproc.threshold(diff, diff, diffThreshold, 255, opencv_imgproc.THRESH_BINARY);

                double white = opencv_core.countNonZero(diff);
                double ratio = white / (diff.rows() * diff.cols());

                if (ratio >= eventRatio && (idx - lastEventFrame) > cooldownFrames) {
                    long ms = (long) ((idx / fps) * 1000.0);
                    stamps.add(Instant.ofEpochMilli(ms));
                    lastEventFrame = idx;
                }

                gray.copyTo(grayPrev);
                idx++;
            }

            return new DetectionResult(videoPath, stamps.size(), List.copyOf(stamps), fps, frameCount);
        }
    }
}
