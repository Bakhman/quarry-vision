package com.quarryvision.core.detection;

import com.quarryvision.app.Config;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.opencv_core.Size;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.global.opencv_videoio;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_videoio.VideoCapture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.nio.file.Files;
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
    private final int minChangedPixels; // минимальное число «белых» пикселей
    private final Size morphKernel;     // ядро морфологии
    private final int mergeMs;
    private final double emaAlpha;
    private final double thrLowFactor;
    private final int minActiveMs;
    private final int nmsWindowMs;
    private final boolean trace;
    private PrintWriter traceOut;

    /** Расширенный конструктор: можно задать порог по пикселям и ядро морфологии. */
    public BucketDetector(int stepFrames, int diffThreshold, double eventRatio, int cooldownFrames,
                          int minChangedPixels, Size morphKernel) {
        this(stepFrames, diffThreshold, eventRatio, cooldownFrames, 5_000, new Size(3, 3), 4_000,
                0.20, 0.60, 1200, 2000, true);
    }

    public BucketDetector(int stepFrames, int diffThreshold, double eventRatio, int cooldownFrames,
                          int minChangedPixels, Size morphKernel, int mergeMs,
                          double emaAlpha, double thrLowFactor, int minActiveMs, int nmsWindowMs, boolean trace) {
        this.stepFrames = Math.max(1, stepFrames);
        this.diffThreshold = Math.max(1, diffThreshold);
        this.eventRatio = Math.max(1e-4, eventRatio);
        this.cooldownFrames = Math.max(0, cooldownFrames);
        this.minChangedPixels = Math.max(0, minChangedPixels);
        this.morphKernel = (morphKernel == null ? new Size(3, 3) : morphKernel);
        this.mergeMs = Math.max(0, mergeMs);
        this.emaAlpha = Math.max(0.0, Math.min(1.0, emaAlpha));
        this.thrLowFactor = Math.max(1e-6, thrLowFactor);
        this.minActiveMs = Math.max(0, minActiveMs);
        this.nmsWindowMs = Math.max(0, nmsWindowMs);
        this.trace = trace;
    }

    /** Удобный конструктор: параметры из application.yaml (+ учёт -Dqv.mergeMs). */
    public BucketDetector(Config cfg) {
        var d = cfg.detection();
        int stepFrames = d.stepFrames();
        int diffThreshold = d.diffThreshold();
        double eventRatio = d.eventRatio();
        int cooldownFrames = d.cooldownFrames();
        int minChangedPixels = d.minChangedPixels();
        // morphKernel: { w, h } → Size(w,h); безопасные дефолты 3×3
        int kw = 3, kh = 3;
        try {
            kw = Math.max(1, d.morphW());
            kh = Math.max(1, d.morphH());
        } catch (Throwable ignore) {/* оставим 3x3, если поле отсутствует */}
        Size kernel = new Size(kw, kh);
        // mergeMs: системное свойство приоритетнее YAML
        int mergeMs = Integer.getInteger("qv.mergeMs", d.mergeMs());
        // делегируем на основной конструктор
        // minChangedPix уже «порог изменившихся пикселей», kernel — морфология.
        // mergeMs — окно слияния событий.
        this.stepFrames = Math.max(1, stepFrames);
        this.diffThreshold = Math.max(1,diffThreshold);
        this.eventRatio = Math.max(1e-4, eventRatio);
        this.cooldownFrames = Math.max(0, cooldownFrames);
        this.minChangedPixels = Math.max(0, minChangedPixels);
        this.morphKernel = kernel;
        this.mergeMs = Math.max(0, mergeMs);
        this.emaAlpha = Math.max(0.0, Math.min(1.0, d.emaAlpha()));
        this.thrLowFactor = Math.max(1e-6, d.thrLowFactor());
        this.minActiveMs = Math.max(0, d.minActiveMs());
        this.nmsWindowMs = Math.max(0, d.nmsWindowMs());
        this.trace = d.trace();
    }

    private static List<Instant> mergeClose(List<Instant> src, long mergeMs) {
        if (src.isEmpty()) return List.of();
        List<Instant> out = new ArrayList<>();
        Instant last = null;
        for (Instant t: src) {
            if (last == null || (t.toEpochMilli() - last.toEpochMilli()) > mergeMs) {
                out.add(t);
                last = t;
            }
        }
        return out;
    }

    public DetectionResult detect(Path videoPath) {
        int effectiveMergeMs = Integer.getInteger("qv.mergeMs", this.mergeMs);
        log.info("Detect params: stepFrames={}, diffThreshold={}, eventRatio={}, cooldownFrames={}, minChangedPixels={}, mergeMs={}, emaAlpha={}, thrLowFactor={}, minActiveMs={}, nmsWindowMs={}",
                stepFrames, diffThreshold, eventRatio, cooldownFrames, minChangedPixels, effectiveMergeMs, emaAlpha, thrLowFactor, minActiveMs, nmsWindowMs);
        try {
            if (!java.nio.file.Files.isRegularFile(videoPath) || java.nio.file.Files.size(videoPath) == 0L) {
                log.error("Video file invalid: exists={} size={} path={}",
                        java.nio.file.Files.exists(videoPath),
                        java.nio.file.Files.exists(videoPath) ? java.nio.file.Files.size(videoPath) : -1,
                        videoPath);
                return new DetectionResult(videoPath, 0, List.of(), 0.0, 0);
            }
        } catch (Exception e) {
            log.error("Video file check failed: {}", videoPath, e);
            return new DetectionResult(videoPath, 0, List.of(), 0.0, 0);
        }
        try (VideoCapture cap = new VideoCapture(videoPath.toString())) {
            if (!cap.isOpened()) {
                log.error("VideoCapture cannot open: {}", videoPath);
                return new DetectionResult(videoPath, 0, List.of(), 0.0, 0);
            }
            double fps = cap.get(opencv_videoio.CAP_PROP_FPS);
            if (!(fps > 1e-3)) fps = 25.0;
            long frameCount = (long) cap.get(opencv_videoio.CAP_PROP_FRAME_COUNT);
            if (trace) {
                try {
                    var name = videoPath.getFileName().toString().replaceAll("\\.[^.]+$","");
                    var dir = Path.of("trace");
                    Files.createDirectories(dir);
                    traceOut = new PrintWriter(Files.newBufferedWriter(
                            dir.resolve("trace_" + name + ".csv")));
                    traceOut.println("ms,frame,ratio,ema,state,event");
                    log.info("trace enabled → {}", dir.resolve("trace_" + name + ".csv").toAbsolutePath());
                } catch (Exception ex) {
                    log.warn("trace init failed: {}", ex.toString());
                }
            }

            Mat prev = new Mat();
            Mat frame = new Mat();
            Mat grayPrev = new Mat();
            Mat gray = new Mat();
            Mat diff = new Mat();
            // ядро морфологии (Mat), построенное из заданного Size
            Mat kernel = opencv_imgproc.getStructuringElement(
                    opencv_imgproc.MORPH_RECT,
                    morphKernel
            );

            List<Instant> stamps = new ArrayList<>();
            long lastEventFrame = -cooldownFrames - 1;
            enum S {IDLE, ACTIVE}
            S st = S.IDLE;
            long activeStartFrame = -1;
            double ema = 0.0;
            double thrHigh = eventRatio;
            double thrLow = Math.max(1e-6, eventRatio * thrLowFactor);
            long minActiveFrames = Math.max(1L, Math.round((minActiveMs / 1000.0) * fps));

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
                    break; // EOF/кадр пуст — выходим без WARN
                }
                // шаг через несколько кадров
                for (int s = 1; s < stepFrames; s++) {
                    if (!cap.read(frame) || frame.empty()) { break; }
                    idx++;
                }
                // могли выйти из внутреннего шага из-за EOF -> кадр пустой
                if (frame.empty()) {
                    // тихо выходим: конец файла
                    break;
                }

                opencv_imgproc.cvtColor(frame, gray, opencv_imgproc.COLOR_BGR2GRAY);
                opencv_imgproc.GaussianBlur(gray, gray, new Size(5, 5), 0);

                opencv_core.absdiff(gray, grayPrev, diff);
                opencv_imgproc.threshold(diff, diff, diffThreshold, 255, opencv_imgproc.THRESH_BINARY);
                // Морфология: убираем мелкий шум и заращиваем разрывы
                opencv_imgproc.erode(diff, diff, kernel);
                opencv_imgproc.dilate(diff, diff, kernel);
                double white = opencv_core.countNonZero(diff);
                // Отсечь мелкие всплески
                if (white < minChangedPixels) {
                    gray.copyTo(grayPrev);
                    idx++;
                    continue;
                }
                 double ratio = white / (double) (diff.rows() * diff.cols());
                ema = emaAlpha * ratio + (1.0 - emaAlpha) * ema;
                long msNow = (long) ((idx / fps) * 1000.0);
                String stStr = st.name();
                int evtMark = 0;

                switch (st) {
                    case IDLE -> {
                        if ((idx - lastEventFrame) > cooldownFrames && ema >= thrHigh) {
                            st = S.ACTIVE;
                            activeStartFrame = idx;
                        }
                    }
                    case ACTIVE -> {
                        if (ema < thrLow) {
                            long durFrames = idx - activeStartFrame;
                            if (durFrames >= minActiveFrames) {
                                long mid = activeStartFrame + durFrames/2;
                                long ms = (long) ((mid / fps) * 1000.0);
                                stamps.add(Instant.ofEpochMilli(ms));
                                lastEventFrame = idx;
                                evtMark = 1;
                            }
                            st = S.IDLE;
                            stStr = st.name();
                        }
                    }
                }

                if (traceOut != null) traceOut.printf("%d,%d,%.6f,%.6f,%s,%d%n",
                        msNow, idx, ratio, ema, stStr, evtMark);

                // старый триггер по ratio убран

                gray.copyTo(grayPrev);
                idx++;
            }

            // EOF: если остались в ACTIVE — зафиксировать интервал
            if (st == S.ACTIVE) {
                long durFrames = idx - activeStartFrame;
                if (durFrames >= minActiveFrames) {
                    long mid = activeStartFrame + durFrames/2;
                    long ms = (long) ((mid / fps) * 1000.0);
                    stamps.add(Instant.ofEpochMilli(ms));
                    if (log.isInfoEnabled()) {
                        long startMs = (long) ((activeStartFrame / fps) * 1000.0);
                        long durMs = (long) ((durFrames / fps) * 1000.0);
                        log.info("evt interval: startMs={} durMs{} midMs{}", startMs, durMs, ms);
                    }
                }
            }
            // простое подавление соседних пиков (NMS окно по времени)
            List<Instant> nms = new ArrayList<>();
            Instant winStart = null;
            for (Instant t : stamps) {
                if (winStart == null || t.toEpochMilli() - winStart.toEpochMilli() > nmsWindowMs) {
                    nms.add(t);
                    winStart = t;
                }
            }
            List<Instant> merged = mergeClose(nms, (long) effectiveMergeMs);
            if (traceOut != null) {
                traceOut.flush();
                traceOut.close();
                traceOut = null;
            }
            return new DetectionResult(videoPath, merged.size(), List.copyOf(merged), fps, frameCount);
        }

    }
}
