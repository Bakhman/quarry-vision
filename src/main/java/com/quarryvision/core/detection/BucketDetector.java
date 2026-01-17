package com.quarryvision.core.detection;

import com.quarryvision.app.Config;
import com.quarryvision.core.ocr.OcrService;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_imgcodecs;
import org.bytedeco.opencv.opencv_core.Rect;
import org.bytedeco.opencv.opencv_core.Size;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.global.opencv_videoio;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_videoio.VideoCapture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;


/**
 * Простейший «детектор ковшей»: анализирует разности кадров,
 * при превышении порога считает событие.
 */
public final class BucketDetector {
    private static final Logger log = LoggerFactory.getLogger(BucketDetector.class);
    private static final boolean OCR_FAST_MODE =
            "fast".equalsIgnoreCase(System.getProperty("qv.ocr.mode", "fast"));

    /**
     * Максимальное число вызовов OCR (readBestToken) на один detect(video).
     * В FAST-режиме сильно режем, в AUDIT – можно побольше.
     */
    private static final int MAX_OCR_CALLS_PER_DETECT =
            Integer.getInteger("qv.ocr.maxCallsPerDetect", OCR_FAST_MODE ? 150 : 1000);

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
        // PERF (PR-0): baseline timings/counters for video processing
        final long perfStartNs = System.nanoTime();
        // Счётчик используется как лимит на одну попытку распознавания номера (plate scan).
        this.ocrCallsThisDetect = 0;
        this.perfOcrCallsTotal = 0;
        this.perfOcrNsTotal = 0;
        this.perfOcrRoiNsTotal = 0;
        this.perfRoiAttemptsTotal = 0;
        this.perfRoiDroppedFast = 0;
        this.perfSnapReads = 0;
        this.perfStopByNormVotes = 0;
        final boolean ocrEnabled = Boolean.getBoolean("qv.ocr.init");
        final OcrService ocr = ocrEnabled ? new OcrService(
                new OcrService.Config(
                        true,
                        System.getProperty("qv.ocr.datapath", "tessdata"),
                        System.getProperty("qv.ocr.languages", "eng"),
                        Integer.getInteger("qv.ocr.psm", 7),
                        Integer.getInteger("qv.ocr.oem", 3)
                )
        ) : null;
        int effectiveMergeMs = Integer.getInteger("qv.mergeMs", this.mergeMs);
        final long maxDetectMs = Long.getLong("qv.detect.maxMs", Long.MAX_VALUE);
        log.info("Detect params: stepFrames={}, diffThreshold={}, eventRatio={}, cooldownFrames={}, minChangedPixels={}, mergeMs={}, emaAlpha={}, thrLowFactor={}, minActiveMs={}, nmsWindowMs={}, maxDetectMs={}",
                stepFrames, diffThreshold, eventRatio, cooldownFrames, minChangedPixels, effectiveMergeMs, emaAlpha, thrLowFactor, minActiveMs, nmsWindowMs, maxDetectMs);
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
            final long perfAfterOpenNs = System.nanoTime();
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

            Mat prev = new Mat(),
            frame = new Mat(),
            grayPrev = new Mat(),
            gray = new Mat(),
            diff = new Mat(),
            // ядро морфологии (Mat), построенное из заданного Size
            kernel = opencv_imgproc.getStructuringElement(
                    opencv_imgproc.MORPH_RECT,
                    morphKernel
            );

            List<Instant> stamps = new ArrayList<>();
            List<String> ocrPlates = new ArrayList<>();
            long lastEventFrame = -cooldownFrames - 1;
            enum S {IDLE, ACTIVE}
            S st = S.IDLE;
            long activeStartFrame = -1;
            double ema = 0.0;
            double thrHigh = eventRatio;
            double thrLow = Math.max(1e-6, eventRatio * thrLowFactor);
            long minActiveFrames = Math.max(1L, Math.round((minActiveMs / 1000.0) * fps));
            final long perfLoopStartNs = System.nanoTime();

            try {// первый кадр
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
                        if (!cap.read(frame) || frame.empty()) {
                            break;
                        }
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

                    if (msNow >= maxDetectMs) {
                        log.info("Detect: reached qv.detect.maxMs={}ms (msNow={}), stop early", maxDetectMs, msNow);
                        break;
                    }
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
                                    long mid = activeStartFrame + durFrames / 2;
                                    long ms = (long) ((mid / fps) * 1000.0);

                                    String plate = null;
                                    // OCR-хук: один снимок в середине события
                                    if (ocr != null) {
                                        try {
                                            plate = tryOcrPlateAroundEvent(ocr, cap, mid, fps, frameCount);
                                            if (plate != null && !plate.isBlank()) {
                                                log.info("OCR plate@{}ms: {}", ms, plate);
                                            } else {
                                                log.debug("OCR no plate @{}ms", ms);
                                            }
                                        } catch (Throwable t) {
                                            log.debug("OCR hook failed: {}", t.toString());
                                        }
                                    }

                                    stamps.add(Instant.ofEpochMilli(ms));
                                    ocrPlates.add(plate);

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
                        long mid = activeStartFrame + durFrames / 2;
                        long ms = (long) ((mid / fps) * 1000.0);
                        stamps.add(Instant.ofEpochMilli(ms));
                        ocrPlates.add(null); // пока без OCR на хвосте
                        if (log.isInfoEnabled()) {
                            long startMs = (long) ((activeStartFrame / fps) * 1000.0);
                            long durMs = (long) ((durFrames / fps) * 1000.0);
                            log.info("evt interval: startMs={} durMs{} midMs{}", startMs, durMs, ms);
                        }
                    }
                }
                // простое подавление соседних пиков (NMS окно по времени)
                List<Instant> nmsTimes = new ArrayList<>();
                List<String> nmsPlates = new ArrayList<>();
                Instant winStart = null;
                for (int i = 0; i < stamps.size(); i++) {
                    Instant t = stamps.get(i);
                    String plate = (i < ocrPlates.size()) ? ocrPlates.get(i) : null;
                    if (winStart == null || t.toEpochMilli() - winStart.toEpochMilli() > nmsWindowMs) {
                        nmsTimes.add(t);
                        nmsPlates.add(plate);
                        winStart = t;
                    }
                }
                MergedEvents merged = mergeCloseWithPlates(nmsTimes, nmsPlates, (long) effectiveMergeMs);
                // times можно копировать через List.copyOf (там нет null)
                List<Instant> mergedTimes = List.copyOf(merged.times);
                // а в plates могут быть null → делаем обычную копию
                List<String> mergedPlates = new ArrayList<>(merged.plates);
                DetectionResult out = new DetectionResult(videoPath, mergedTimes.size(), mergedTimes, fps, frameCount, mergedPlates);

                final long perfEndNs = System.nanoTime();
                long totalMs = (perfEndNs - perfStartNs) / 1_000_000L;
                long openMs  = (perfAfterOpenNs - perfStartNs) / 1_000_000L;
                long loopMs  = (perfEndNs - perfLoopStartNs) / 1_000_000L;

                long ocrRoiMs = this.perfOcrRoiNsTotal / 1_000_000L;
                long ocrMs    = this.perfOcrNsTotal / 1_000_000L;
                long ocrCalls = this.perfOcrCallsTotal;
                long ocrAvgMs = (ocrCalls > 0) ? (ocrMs / ocrCalls) : 0;

                final int maxRoiPerScan = Integer.getInteger(
                        "qv.ocr.maxRoiPerScan",
                        OCR_FAST_MODE ? 80 : Integer.MAX_VALUE);
                final String eventOffsetsSec = System.getProperty("qv.ocr.eventOffsetsSec", "0,-4,4");

                log.info("PERF {{video='{}', totalMs={}, openMs={}, loopMs={}, fps={}, frames={}, events={}, ocrEnabled={}, snapReads={}, roiAttempts={}, roiDroppedFast={}, ocrCalls={}, ocrRoiMs={}, ocrMs={}, ocrAvgMs={}, ocrStopByVotes={}, stepFrames={}, maxRoiPerScan={}, eventOffsetsSec='{}'}}",
                        videoPath.getFileName(),
                        totalMs, openMs, loopMs,
                        fps, frameCount, mergedTimes.size(),
                        (ocr != null),
                        this.perfSnapReads,
                        this.perfRoiAttemptsTotal,
                        this.perfRoiDroppedFast,
                        ocrCalls,
                        ocrRoiMs,
                        ocrMs,
                        ocrAvgMs,
                        this.perfStopByNormVotes,
                        stepFrames,
                        maxRoiPerScan,
                        eventOffsetsSec);
                return out;
            } finally {
                // гарантированное освобождение нативной памяти
                release(prev, frame, grayPrev, gray, diff, kernel);
                if (traceOut != null) {
                    traceOut.flush();
                    traceOut.close();
                    traceOut = null;
                }
            }
        }
    }

    /**
     * Попытка OCR не только в mid кадре события, а на нескольких кадрах вокруг него.
     * Управление через system property:
     *   -Dqv.ocr.eventOffsetsSec=0,-4,4   (по умолчанию)
     *
     * Идея: на реальном видео mid кадр часто не лучший (перекрытия/смаз/угол),
     * а за 3–5 секунд до/после номер бывает читаемее.
     */
    private String tryOcrPlateAroundEvent(OcrService ocr, VideoCapture cap,
                                          long midFrame, double fps, long frameCount) {
        String offs = System.getProperty("qv.ocr.eventOffsetsSec", "0,-4,4");
        // Бюджет OCR — на событие целиком (на все offsets), а не на каждый кадр отдельно
        this.ocrCallsThisDetect = 0;
        String[] parts = offs.split(",");
        for (String p : parts) {
            int sec;
            try {
                sec = Integer.parseInt(p.trim());
            } catch (Exception ignore) {
                continue;
            }
            long f = midFrame + Math.round(sec * fps);
            f = clampLong(f, 0, Math.max(0, frameCount - 1));
            Mat snap = null;
            try {
                this.perfSnapReads++;
                snap = readFrameAt(cap, f);
                if (snap == null || snap.empty()) continue;
                // Если бюджет исчерпан — дальше offsets не читаем
                if (this.ocrCallsThisDetect >= MAX_OCR_CALLS_PER_DETECT) {
                    if ( log.isDebugEnabled()) {
                        log.debug("OCR: event budget reached (MAX_OCR_CALLS_PER_DETECT={}), stop offsets scan", MAX_OCR_CALLS_PER_DETECT);
                    }
                    break;
                }
                String plate = tryOcrPlate(ocr, snap);
                if (plate != null && !plate.isBlank()) {
                    if (log.isDebugEnabled()) {
                        log.debug("OCR: plate candidate '{}' at offsetSec={} (frame={})", plate, sec, f);
                    }
                    return plate;
                }
            } catch (Throwable ignore) {
                // молча продолжаем на следующий offset
            } finally {
                if (snap != null) snap.release();
            }
        }
        return null;
    }

    private static long clampLong(long v, long min, long max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }

    private record MergedEvents(List<Instant> times, List<String> plates) {}

    private static MergedEvents mergeCloseWithPlates(List<Instant> srcTimes,
                                                     List<String> srcPlates,
                                                     long mergeMs) {
        if (srcTimes.isEmpty()) {
            return new MergedEvents(List.of(), List.of());
        }

        if (srcPlates == null || srcPlates.size() != srcTimes.size()) {
            throw new IllegalArgumentException("srcPlates size must match srcTimes size");
        }

        List<Instant> outTimes = new ArrayList<>();
        List<String> outPlates = new ArrayList<>();

        Instant last = null;
        for (int i = 0; i < srcTimes.size(); i++) {
            Instant t = srcTimes.get(i);
            String p = srcPlates.get(i);

            if (last == null || (t.toEpochMilli() - last.toEpochMilli()) > mergeMs) {
                // новая группа
                outTimes.add(t);
                outPlates.add(p);
                last = t;
            } else {
                // внутри той же группы: если номер ещё пустой, а сейчас есть — обновляем
                if (p != null && !p.isBlank()) {
                    int idx = outPlates.size() - 1;
                    String existing = outPlates.get(idx);
                    if (existing == null || existing.isBlank()) {
                        outPlates.set(idx, p);
                    }
                }
            }
        }

        return new MergedEvents(outTimes, outPlates);
    }

    /** Читает кадр по индексу (без изменения текущих Mat), возвращает новый Mat или null. */
    private static Mat readFrameAt(VideoCapture cap, long frameIndex) {
        double posBackup = cap.get(opencv_videoio.CAP_PROP_POS_FRAMES);
        try {
            cap.set(opencv_videoio.CAP_PROP_POS_FRAMES, frameIndex);
            Mat m = new Mat();
            if (cap.read(m) && !m.empty()) return m;
            return null;
        } finally {
            cap.set(opencv_videoio.CAP_PROP_POS_FRAMES, posBackup);
        }
    }

    /** Сканирует несколько ROI в нижней полосе и выбирает лучший результат OCR. */
    private String tryOcrPlate(OcrService ocr, Mat bgr) {

        if (log.isDebugEnabled()) {
            log.debug("OCR: start tryOcrPlate, OCR_FAST_MODE={}, MAX_OCR_CALLS_PER_DETECT={}",
                    OCR_FAST_MODE, MAX_OCR_CALLS_PER_DETECT);
        }

        int h = bgr.rows(), w = bgr.cols();
        // сетка по X и размерам: центр и соседние позиции
        // Важно для fast-режима: первые N ROI должны покрывать разные fy/fh,
        // иначе при maxRoiPerScan мы можем вообще не дойти до "правильной" высоты номера.
        // Поэтому: (1) сначала центр/типовые размеры, (2) перестраиваем порядок циклов ниже.
        double[] fxList = {0.45, 0.50, 0.40, 0.55, 0.35, 0.60, 0.30, 0.65, 0.25};
        double[] fwList = {0.18, 0.22, 0.14};
        double[] fyList = {0.92, 0.90, 0.93, 0.88};
        double[] fhList = {0.06, 0.07, 0.05};

        String best = null;
        int bestScore = -1;
        int roiIdx = 0;
        // Early-stop: если один и тот же нормализованный номер встретился N раз,
        // прекращаем сканирование ROI.
        // Управление:
        // -Dqv.ocr.stopVotes=2   (рекомендуется для FAST)
        final int stopVotes = Integer.getInteger("qv.ocr.stopVotes", OCR_FAST_MODE ? 2 : Integer.MAX_VALUE);
        final HashMap<String, Integer> normVotes = new HashMap<>();


        // Лимит ROI (Region of Interest, область интереса) на один скан номера.
        // В FAST-режиме значение по умолчанию = 80, но его можно переопределить через -Dqv.ocr.maxRoiPerScan=...
        final int maxRoiPerScan = Integer.getInteger(
                "qv.ocr.maxRoiPerScan",
                OCR_FAST_MODE ? 80 : Integer.MAX_VALUE);
        if (log.isDebugEnabled()) {
            log.debug("OCR: scanning plate ROI, maxRoiPerScan={}, grid sizes: fx={}, fw={}, fy={}, fh={}",
                    maxRoiPerScan, fxList.length, fwList.length, fyList.length, fhList.length);
        }

        outer:
        for (double fx : fxList)
            for (double fw : fwList)
                for (double fy : fyList)
                    for (double fh : fhList) {
                        if (roiIdx >= maxRoiPerScan) {
                            if (log.isDebugEnabled()) {
                                log.debug("OCR: reached maxRoiPerScan={} (roiIdx={}), stop scanning plate ROI, best='{}', bestScore={}",
                                        maxRoiPerScan, roiIdx, best, bestScore);
                            }
                            break outer;
                        }

                        int rx = clamp((int) Math.round(w * fx), 0, w - 1);
                        int ry = clamp((int) Math.round(h * fy), 0, h - 1);
                        int rw = clamp((int) Math.round(w * fw), 1, w - rx);
                        int rh = clamp((int) Math.round(h * fh), 1, h - ry);
                        Rect r = new Rect(rx, ry, rw, rh);
                        String got = ocrOnceOnRoi(ocr, bgr, r, "roi_" + (roiIdx++));
                        if (got == null) continue;
                        // Не выбрасываем кириллицу — оставляем A-Z, 0-9 и А-ЯЁ, как в OcrService
                        String cleaned = got.toUpperCase().replaceAll("[^A-Z0-9А-ЯЁ]", "");
                        String norm = normalizePlate(cleaned);
                        // Голосование по нормализованному номеру: если повторился N раз — early stop
                        // но только для "достаточно длинных" номеров, чтобы не залипать на коротких ложняках.
                        if (norm != null && stopVotes != Integer.MAX_VALUE) {
                            int v = normVotes.merge(norm, 1, Integer::sum);
                            final int minLenForEarlyStop = Integer.getInteger("qv.ocr.stopVotesMinLen", 8);
                            if (v >= stopVotes && norm.length() >= minLenForEarlyStop) {
                                this.perfStopByNormVotes++;
                                if (log.isDebugEnabled()) {
                                    log.debug("OCR: early-stop by normVotes={} for norm='{}' (len>={})",
                                            v, norm, minLenForEarlyStop);
                                }
                                return norm;
                            } else if (v >= stopVotes && log.isDebugEnabled()) {
                                log.debug("OCR: votes reached (v={}) but skip early-stop: norm='{}' len={} < {}",
                                        v, norm, norm.length(), minLenForEarlyStop);
                            }
                        }
                        // Важно: score=100 слишком "плоский" — любой валидный шаблон (включая ложный RU)
                        // мгновенно обрывает сканирование. Поэтому:
                        // 1) даём валидным номерам базу 100 + длина (8-символьный выигрывает у 6-символьного)
                        // 2) ранний выход допускаем только для "длинного" номера (>= 8), иначе продолжаем скан.
                        int score = (norm != null ? 100 + norm.length() : cleaned.length()); // валидный шаблон — максимум
                        String candidate = (norm != null ? norm : cleaned);
                        if (log.isDebugEnabled()) {
                            log.debug("OCR roi#{} rect=({}, {}, {}, {}): raw='{}' cleaned='{}' norm='{}' score='{}'",
                                    roiIdx - 1, rx, ry, rw, rh, got, cleaned, norm, score);
                        }
                        if (score > bestScore
                                || (score == bestScore && (best == null
                                || candidate.length() > best.length()
                                || (candidate.length() == best.length() && preferOnTie(candidate, best))))) {
                            bestScore = score;
                            best = candidate;
                            if (log.isDebugEnabled()) {
                                log.debug("OCR: new best='{}' score={} at roi#{}", best, bestScore, roiIdx - 1);
                            }
                        }

                        // Ранний выход — только если нашли "длинный" номер (например, LLDDDDLL = 8),
                        // иначе продолжаем сканировать ROI, чтобы не зафиксировать ложный 6-символьный RU.
                        if (norm != null && norm.length() >= 8) return norm;
                    }
        if (log.isDebugEnabled()) {
            log.debug("OCR best='{}' score={}", best, bestScore);
        }
        // Для бизнес-логики принимаем только нормализованный номер (score=100),
        // иначе считаем, что номер не найден.
        return (bestScore >= 100) ? best : null;
    }

    private int ocrCallsThisDetect = 0;

    private String ocrOnceOnRoi(OcrService ocr, Mat bgr, Rect r, String tag) {
        final long roiStartNs = System.nanoTime();
        this.perfRoiAttemptsTotal++;
        try {
            if (this.ocrCallsThisDetect >= MAX_OCR_CALLS_PER_DETECT) {
                if (log.isDebugEnabled()) {
                    log.debug("OCR: skip ROI {} because MAX_OCR_CALLS_PER_DETECT={} reached (rect({}, {}, {}, {}))",
                            tag, MAX_OCR_CALLS_PER_DETECT, r.x(), r.y(), r.width(), r.height());
                }
                return null;
            }
            Mat roi = new Mat(bgr, r).clone();
            Mat gray = new Mat();
            opencv_imgproc.cvtColor(roi, gray, opencv_imgproc.COLOR_BGR2GRAY);
            var clahe = opencv_imgproc.createCLAHE(2.0, new Size(8, 8));
            Mat eq = new Mat();
            clahe.apply(gray, eq);
            Mat den = new Mat();
            opencv_imgproc.bilateralFilter(eq, den, 5, 75, 75);
            Mat up = new Mat();
            opencv_imgproc.resize(den, up, new Size(den.cols() * 2, den.rows() * 2));
            // первичная бинаризация (управляется флагами)
            int b1 = Integer.getInteger("qv.ocr.adaptBlock", 31);
            int c1 = Integer.getInteger("qv.ocr.adaptC", 5);
            if ((b1 & 1) == 0) b1++; // blockSize должен быть нечётным
            Mat bin = new Mat();
            opencv_imgproc.adaptiveThreshold(
                    up, bin, 255,
                    opencv_imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                    opencv_imgproc.THRESH_BINARY, b1, c1);
            // морфология для склейки разрывов символов
            Mat k = opencv_imgproc.getStructuringElement(opencv_imgproc.MORPH_RECT, new Size(3, 3));
            opencv_imgproc.morphologyEx(bin, bin, opencv_imgproc.MORPH_CLOSE, k);
            double fill = opencv_core.countNonZero(bin) / (double) (bin.rows() * bin.cols());
            // оценка контраста ROI через стандартное отклонение яркости
            Mat mean = new Mat();
            Mat stddev = new Mat();
            // В FAST-режиме решение "дропать ROI" зависит от контраста.
            // Контраст корректнее считать ПОСЛЕ CLAHE, иначе ROI с номером часто ошибочно попадает в lowContrast.
            opencv_core.meanStdDev(eq, mean, stddev);
            double contrast = stddev.createIndexer().getDouble(0) / 255.0;
            // пороги управляемы через -Dqv.ocr.minContrast / -Dqv.ocr.fillMin / -Dqv.ocr.fillMax
            double minContrast = Double.parseDouble(System.getProperty("qv.ocr.minContrast", "0.10"));
            double fillMin = Double.parseDouble(System.getProperty("qv.ocr.fillMin", "0.01"));
            double fillMax = Double.parseDouble(System.getProperty("qv.ocr.fillMax", "0.90"));
            boolean lowContrast = contrast < minContrast;
            boolean badFill = (fill < fillMin || fill > fillMax);
            boolean needFallback = badFill || lowContrast;
            if (needFallback && OCR_FAST_MODE) {
                // FAST-режим: ROI явно "плохой" по заполнению/контрасту — даже не зовём OCR
                this.perfRoiDroppedFast++;
                if (log.isDebugEnabled()) {
                    log.debug("OCR fast: drop ROI after primary bin (badFill={}, lowContrast={}, " +
                                    "fill={}, contrast={}, minC={}, fMin={}, fMax={}) rect({}, {}, {}, {})",
                            badFill, lowContrast,
                            String.format("%.3f", fill),
                            String.format("%.3f", contrast),
                            String.format("%.3f", minContrast),
                            String.format("%.3f", fillMin),
                            String.format("%.3f", fillMax),
                            r.x(), r.y(), r.width(), r.height());
                }
                release(k, bin, up, den, eq, gray, roi);
                return null;
            }
            if (needFallback) {
                // альтернативная адаптивная бинаризация с более крупным окном
                int b2 = Integer.getInteger("qv.ocr.adaptBlock2", 41);
                int c2 = Integer.getInteger("qv.ocr.adaptC2", 2);
                if ((b2 & 1) == 0) b2++;
                Mat bin2 = new Mat();
                opencv_imgproc.adaptiveThreshold(
                        up, bin2, 255,
                        opencv_imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                        opencv_imgproc.THRESH_BINARY, b2, c2);
                double fill2 = opencv_core.countNonZero(bin2) / (double) (bin2.rows() * bin2.cols());
                if (fill2 >= fillMin && fill2 <= fillMax) {
                    if (log.isDebugEnabled()) {
                        log.debug("OCR fallback#1 OK (fill2={}) rect=({}, {}, {}, {})",
                                String.format("%.3f", fill2), r.x(), r.y(), r.width(), r.height());
                    }
                    bin.release();
                    bin = bin2;
                    fill = fill2;
                    needFallback = false;
                } else {
                    bin2.release();
                }
            }
            if (needFallback) {
                // Otsu как последний вариант
                Mat bin3 = new Mat();
                opencv_imgproc.threshold(up, bin3, 0, 255, opencv_imgproc.THRESH_BINARY + opencv_imgproc.THRESH_OTSU);
                double fill3 = opencv_core.countNonZero(bin3) / (double) (bin3.rows() * bin3.cols());
                if (fill3 >= fillMin && fill3 <= fillMax) {
                    if (log.isDebugEnabled()) {
                        log.debug("OCR fallback#2 Otsu OK (fill3={}) rect({}, {}, {}, {})",
                                String.format("%.3f", fill3), r.x(), r.y(), r.width(), r.height());
                    }
                    bin.release();
                    bin = bin3;
                    fill = fill3;
                    needFallback = false;
                } else {
                    bin3.release();
                }

            }
            if (needFallback) {
                boolean badFillFinal = (fill < fillMin || fill > fillMax);
                boolean lowContrastFinal = contrast < minContrast;
                if (log.isDebugEnabled()) {
                    log.debug("OCR drop ROI after fallbacks (badFill={}, lowContrast={}, "
                                    + "fill={}, contrast={}, minC={}, fMin={}, fMax={}) rect({}, {}, {}, {})",
                            badFillFinal, lowContrastFinal,
                            String.format("%.3f", fill),
                            String.format("%.3f", contrast),
                            String.format("%.3f", minContrast),
                            String.format("%.3f", fillMin),
                            String.format("%.3f", fillMax),
                            r.x(), r.y(), r.width(), r.height());
                }
                release(k, bin, up, den, eq, gray, roi);
                return null;
            }
            BytePointer buf = new BytePointer();
            boolean ok = opencv_imgcodecs.imencode(".png", bin, buf);
            if (!ok) {
                release(k, bin, up, den, eq, gray, roi);
                return null;
            }
            byte[] bytes = new byte[(int) buf.limit()];
            buf.get(bytes);
            try {
                BufferedImage bi = ImageIO.read(new ByteArrayInputStream(bytes));
                // сначала пробуем самый уверенный токен, потом общий текст
                this.ocrCallsThisDetect++;
                this.perfOcrCallsTotal++;
                if (log.isDebugEnabled()) {
                    log.debug("OCR call #{} for ROI {} (rect=({}, {}, {}, {}))",
                            this.ocrCallsThisDetect,
                            tag, r.x(), r.y(), r.width(), r.height());
                }
                final long ocrStartNs = System.nanoTime();
                String raw = ocr.readBestToken(bi).orElse(null);
                this.perfOcrNsTotal += (System.nanoTime() - ocrStartNs);
                if (raw == null) {
                    if (!OCR_FAST_MODE) {
                        // Fallback: инверсия бинарного изображения и повторная попытка
                        Mat binInv = new Mat();
                        opencv_core.bitwise_not(bin, binInv);
                        BytePointer buf2 = new BytePointer();
                        boolean ok2 = opencv_imgcodecs.imencode(".png", binInv, buf2);
                        if (ok2) {
                            if (log.isDebugEnabled()) {
                                log.debug("OCR fallback invert: call #{} for ROI {} (rect=({}, {}, {}, {}))",
                                        this.ocrCallsThisDetect + 1,
                                        tag, r.x(), r.y(), r.width(), r.height());
                            }
                            byte[] b2 = new byte[(int) buf2.limit()];
                            buf2.get(b2);
                            BufferedImage bi2 = ImageIO.read(new ByteArrayInputStream(b2));
                            this.ocrCallsThisDetect++;
                            this.perfOcrCallsTotal++;
                            final long ocrStartNs2 = System.nanoTime();
                            raw = ocr.readBestToken(bi2).orElse(null);
                            this.perfOcrNsTotal += (System.nanoTime() - ocrStartNs2);
                        }
                        buf2.deallocate();
                        binInv.release();
                    }
                    if (raw == null) return null;
                }
                return raw.trim();
            } catch (Exception ignore) {
                return null;
            } finally {
                buf.deallocate();
                release(k, bin, up, den, eq, gray, roi);
            }
        } finally {
            this.perfOcrRoiNsTotal += (System.nanoTime() - roiStartNs);
        }
    }

    // PERF counters (PR-0)
    private long perfOcrCallsTotal = 0;
    private long perfOcrNsTotal = 0;
    private long perfOcrRoiNsTotal = 0;
    private long perfRoiAttemptsTotal = 0;
    private long perfRoiDroppedFast = 0;
    private long perfSnapReads = 0;
    private long perfStopByNormVotes = 0;
    private static int clamp(int v, int lo, int hi){ return Math.max(lo, Math.min(hi, v)); }

    // Управление: включать ли регион в результат нормализации, default=false
    private static final boolean INCLUDE_REGION = Boolean.getBoolean("qv.ocr.includeRegion");
    /** Пробует привести строку к шаблону госномера.
     * Поддержка ru: LDDDLL[region], где L ∈ {А,В,Е,К,М,Н,О,Р,С,Т,У,Х} (кириллица или латиница-двойник).
     * Возвращает канонический латинский вид, например: "О793РР" или "О793РР123" */
    private static String normalizePlate(String s) {
        if (s == null || s.isEmpty()) return null;
        // 0) базовая нормализация регистра + кириллицы
        String base = translitRuPlateLettersToLatin(s.toUpperCase());
        // Убираем разделители/мусор, но сохраняем буквы/цифры (включая кириллицу),
        // чтобы корректно обрабатывать варианты типа "ВЕ 8624" или "О793РР 123".
        String compact = base.replaceAll("[^\\p{L}\\p{N}]", "");

        // 1) две гипотезы путаниц:
        //    a) буквенная: цифры, похожие на буквы → буквы (0→O,1→I,5→S,2→Z,8→B)
        String lettersBias = compact
                .replace('0','O').replace('1','I').replace('5','S').replace('2','Z').replace('8','B');
        //    b) цифровая: буквы, похожие на цифры → цифры (O→0,I→1,S→5,Z→2,B→8)
        String digitsBias = compact
                .replace('O','0').replace('I','1').replace('S','5').replace('Z','2').replace('B','8');

        // 2) СНАЧАЛА пробуем чистый generic на исходной строке
        String p;
        if ((p = extractGenericPlate(compact))  != null) return p;
        // затем RU на исходной и цифровой
        if ((p = extractRuPlate(compact))       != null) return p;
        if ((p = extractRuPlate(digitsBias)) != null) return p;

        // 3) generic на bias-вариантах
        if ((p = extractGenericPlate(lettersBias)) != null) return p;
        if ((p = extractGenericPlate(digitsBias))  != null) return p;

        // 4) короткий generic LLDDDD (например, BE8624) — когда хвостовые 2 буквы отсутствуют/нечитаемы
        if ((p = extractGenericShortPlate(compact))     != null) return p;
        if ((p = extractGenericShortPlate(lettersBias)) != null) return p;
        if ((p = extractGenericShortPlate(digitsBias))  != null) return p;
        return null;
    }

    /** Транслитерация разрешённых кириллических букв к латинским двойникам. Остальное оставляем как есть. */
    private static String translitRuPlateLettersToLatin(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            // кириллица → латиница-двойник
            switch (s.charAt(i)) {
                case 'А': sb.append('A'); break;
                case 'В': sb.append('B'); break;
                case 'Е': sb.append('E'); break;
                case 'К': sb.append('K'); break;
                case 'М': sb.append('M'); break;
                case 'Н': sb.append('H'); break;
                case 'О': sb.append('O'); break;
                case 'Р': sb.append('P'); break;
                case 'С': sb.append('C'); break;
                case 'Т': sb.append('T'); break;
                case 'У': sb.append('Y'); break;
                case 'Х': sb.append('X'); break;
                default:  sb.append(s.charAt(i));
            }
        }
        return sb.toString();
    }

    /** RU-шаблон: L D D D L L [D{2,3}] с позиционной нормализацией. По умолчанию регион отбрасываем. */
    private static String extractRuPlate(String s) {
        // сканируем окно от длины 6 до 9
        for (int i = 0; i + 6 <= s.length(); i++) {
            // базовая часть: L D D D L L
            char c0 = toRuLetterSlot(safe(s, i));
            char c1 = toDigitSlot(safe(s, i+1));
            char c2 = toDigitSlot(safe(s, i+2));
            char c3 = toDigitSlot(safe(s, i+3));
            char c4 = toRuLetterSlot(safe(s, i+4));
            char c5 = toRuLetterSlot(safe(s, i+5));
            if (c0!=0 && c1!=0 && c2!=0 && c3!=0 && c4!=0 && c5!=0) {
                // регион: 2 или 3 цифры, опционально
                String core = "" + c0+c1+c2+c3+c4+c5;
                if (!INCLUDE_REGION) return core;
                char ch6 = toDigitSlot(safe(s,i+6));
                char ch7 = toDigitSlot(safe(s,i+7));
                char ch8 = toDigitSlot(safe(s,i+8));
                if (ch6!=0 && ch7!=0 && ch8!=0) return core + ch6 + ch7 + ch8;
                if (ch6!=0 && ch7!=0) return core + ch6 + ch7;
                return core;
            }
        }
        return null;
    }

    /** Общий шаблон: LL DDDD LL с позиционной нормализацией по слотам. */
    private static String extractGenericPlate(String s) {
        for (int i = 0; i + 8 <= s.length(); i++) {
            char L0 = toLatLetterSlotGeneric(safe(s,i));
            char L1 = toLatLetterSlotGeneric(safe(s,i+1));
            char D2 = toDigitSlotGeneric(safe(s,i+2));
            char D3 = toDigitSlotGeneric(safe(s,i+3));
            char D4 = toDigitSlotGeneric(safe(s,i+4));
            char D5 = toDigitSlotGeneric(safe(s,i+5));
            char L6 = toLatLetterSlotGenericTail1(safe(s,i+6));
            char L7 = toLatLetterSlotGenericTail2(safe(s,i+7));
            if (L0!=0 && L1!=0 && D2!=0 && D3!=0 && D4!=0 && D5!=0 && L6!=0 && L7!=0) {
                return ""+L0+L1+D2+D3+D4+D5+L6+L7;
            }
        }
        return null;
    }

    /** Короткий generic: LL DDDD (например, BE8624). Буквы ограничиваем RU_LAT_SET для снижения ложных срабатываний. */
    private static String extractGenericShortPlate(String s) {
        if (s == null) return null;
        for (int i = 0; i + 6 <= s.length(); i++) {
            char L0 = toLatLetterSlotGeneric(safe(s,i));
            char L1 = toLatLetterSlotGeneric(safe(s,i+1));
            if (L0==0 || L1==0) continue;
            if (!isRuLatLetter(L0) || !isRuLatLetter(L1)) continue;
            char D2 = toDigitSlotGeneric(safe(s,i+2));
            char D3 = toDigitSlotGeneric(safe(s,i+3));
            char D4 = toDigitSlotGeneric(safe(s,i+4));
            char D5 = toDigitSlotGeneric(safe(s, i+5));
            if (D2!=0 && D3!=0 && D4!=0 && D5!=0) {
                return "" + L0 + L1 + D2 + D3 + D4 + D5;
            }
        }
        return null;
    }

    /** Разрешённые буквы для RU (латинские двойники): A B E K M H O P C T Y X */
    private static final String RU_LAT_SET = "ABEKMHOPCTYX";
    private static boolean isDigit(char c) { return c >= '0' && c <= '9'; }
    private static boolean isRuLatLetter(char c) { return RU_LAT_SET.indexOf(c) >= 0; }
    private static char safe(String s, int i) { return i < s.length() ? s.charAt(i) : '\0'; }

    /** Слот букв RU: кириллица→латиница, цифры-похожие→буквы; возвращает 0 если неподходящее. */
    private static char toRuLetterSlot(char ch) {
        if (ch == '\0') return 0;
        // кириллица → латиница
        switch (ch) {
            case 'А' -> ch='A';
            case 'В' -> ch='B';
            case 'Е' -> ch='E';
            case 'К' -> ch='K';
            case 'М' -> ch='M';
            case 'Н' -> ch='H';
            case 'О' -> ch='O';
            case 'Р' -> ch='P';
            case 'С' -> ch='C';
            case 'Т' -> ch='T';
            case 'У' -> ch='Y';
            case 'Х' -> ch='X';
        }
        // цифры, похожие на буквы
        if (ch=='0') ch='O';
        else if (ch=='1') ch='I';
        else if (ch=='5') ch='S';
        else if (ch=='2') ch='Z';
        else if (ch=='8') ch='B';
        return isRuLatLetter(ch) ? ch : 0;
    }


    /** Слот цифры 0–9 с коррекцией похожих букв. */
    private static char toDigitSlot(char ch) {
        if (ch=='\0') return 0;
        // Частые OCR-подмены в размытых кадрах:
        // O→0, I→1, S→5, Z→2, B→8 уже были.
        // Дополнительно: E→6 (плоский шрифт), A→1 (тонкий шрифт/пересвет).
        if (ch=='O') ch='0';
        else if (ch=='I') ch='1';
        else if (ch=='S') ch='5';
        else if (ch=='Z') ch='2';
        else if (ch=='B') ch='8';
        else if (ch=='E') ch='6';
        else if (ch=='A') ch='1';
        return isDigit(ch) ? ch : 0;
    }

    /** Буквенный слот (общий) для generic LLDDDDLL: 0→O, 1→I, 5→S, 2→Z, 8→B, 4→A. */
    private static char toLatLetterSlotGeneric(char ch) {
        if (ch=='\0') return 0;
        if (ch=='0') ch='O';
        else if (ch=='1') ch='I';
        else if (ch=='5') ch='S';
        else if (ch=='2') ch='Z';
        else if (ch=='4') ch='A';
        else if (ch == '8') ch = 'B'; // дефолтно 8→B (исключение — хвост)
        return (ch >= 'A' && ch <= 'Z') ? ch : 0;
    }

   /** Хвостовая буква #1 (L6): допускаем 8→A. */
   private static char toLatLetterSlotGenericTail1(char ch) {
       if (ch == '\0') return 0;
       if (ch == '0') ch = 'O';
       else if (ch == '1') ch = 'I';
       else if (ch == '5') ch = 'S';
       else if (ch == '2') ch = 'Z';
       else if (ch == '4') ch = 'A';
       else if (ch == '8') ch = 'A'; // ключевая разница
       return (ch >= 'A' && ch <= 'Z') ? ch : 0;
   }

   /** Хвостовая буква #2 (L7): допускаем 8→O. */
   private static char toLatLetterSlotGenericTail2(char ch) {
       if (ch == '\0') return 0;
       if (ch == '0') ch = 'O';
       else if (ch == '1') ch = 'I';
       else if (ch == '5') ch = 'S';
       else if (ch == '2') ch = 'Z';
       else if (ch == '4') ch = 'A';
       else if (ch == '8') ch = 'O'; // ключевая разница
       return (ch >= 'A' && ch <= 'Z') ? ch : 0;
   }

    /** Цифровой слот для generic LLDDDDLL: O→0, I→1, S→5, Z→2, B→8, E→6, A→4. */
    private static char toDigitSlotGeneric(char ch){
        if (ch=='\0') return 0;
        if (ch=='O') ch='0';
        else if (ch=='I') ch='1';
        else if (ch=='S') ch='5';
        else if (ch=='Z') ch='2';
        else if (ch=='B') ch='8';
        else if (ch=='E') ch='6';
        else if (ch=='A') ch='4';
        return isDigit(ch) ? ch : 0;
    }

    /**
     * Tie-breaker при равном score и равной длине:
     * generic (LLDDDDLL / LLDDDD) > RU core (LDDDLL).
     * Нужен, чтобы при одинаковом "весе" не закреплялся случайный RU-ложняк,
     * если найден осмысленный generic-кандидат.
     */
    private static boolean preferOnTie(String candidate, String best) {
        return plateKind(candidate) > plateKind(best);
    }

    // 3 = generic full (LLDDDDLL), 2 = generic short (LLDDDD), 1 = RU core (LDDDLL), 0 = unknown
    private static int plateKind(String p) {
        if (p == null) return 0;
        if (isGenericFull(p))   return 3;
        if (isGenericShort(p))  return 2;
        if (isRuCore(p))        return 1;
        return 0;
    }

    private static boolean isAz(char c) {
        return c >= 'A' && c <= 'Z';
    }

    private static boolean isGenericShort(String p) {
        if (p.length() != 6) return false;
        return isAz(p.charAt(0)) && isAz(p.charAt(1))
                && isDigit(p.charAt(2)) && isDigit(p.charAt(3)) && isDigit(p.charAt(4)) && isDigit(p.charAt(5));
    }

    private static boolean isGenericFull(String p) {
        if (p.length() != 8) return false;
        return isAz(p.charAt(0)) && isAz(p.charAt(1))
                && isDigit(p.charAt(2)) && isDigit(p.charAt(3)) && isDigit(p.charAt(4)) && isDigit(p.charAt(5))
                && isAz(p.charAt(6)) && isAz(p.charAt(7));
    }

    private static boolean isRuCore(String p) {
        if (p.length() != 6) return false;
        return isRuLatLetter(p.charAt(0))
                && isDigit(p.charAt(1)) && isDigit(p.charAt(2)) && isDigit(p.charAt(3))
                && isRuLatLetter(p.charAt(4)) && isRuLatLetter(p.charAt(5));
    }

    public int effectiveMergeMs() {
        return Integer.getInteger("qv.mergeMs", this.mergeMs);
    }

    private static void release(Mat... mats) {
        for (Mat m : mats) if (m != null) m.release();
    }
}
