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
            long lastEventFrame = -cooldownFrames - 1;
            enum S {IDLE, ACTIVE}
            S st = S.IDLE;
            long activeStartFrame = -1;
            double ema = 0.0;
            double thrHigh = eventRatio;
            double thrLow = Math.max(1e-6, eventRatio * thrLowFactor);
            long minActiveFrames = Math.max(1L, Math.round((minActiveMs / 1000.0) * fps));

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
                                    stamps.add(Instant.ofEpochMilli(ms));
                                    // OCR-хук: один снимок в середине события
                                    if (ocr != null) {
                                        try {
                                            Mat snap = readFrameAt(cap, mid);
                                            if (snap != null && !snap.empty()) {
                                                String plate = tryOcrPlate(ocr, snap);
                                                if (plate != null && !plate.isBlank()) {
                                                    log.info("OCR plate@{}ms: {}", ms, plate);
                                                } else {
                                                    log.debug("OCR no plate @{}ms", ms);
                                                }
                                            }
                                        } catch (Throwable t) {
                                            log.debug("OCR hook failed: {}", t.toString());
                                        }
                                    }
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
                return new DetectionResult(videoPath, merged.size(), List.copyOf(merged), fps, frameCount);
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
    private static String tryOcrPlate(OcrService ocr, Mat bgr) {
        int h = bgr.rows(), w = bgr.cols();
        // сетка по X и размерам: центр и соседние позиции
        double[] fxList = {0.25, 0.30, 0.35, 0.40, 0.45, 0.50, 0.55, 0.60, 0.65};
        double[] fwList = {0.14, 0.18, 0.22};
        double[] fyList = {0.88, 0.90, 0.92, 0.93};
        double[] fhList = {0.05, 0.06, 0.07};

        String best = null;
        int bestScore = -1;
        int roiIdx = 0;
        for (double fy : fyList) for (double fh : fhList)
            for (double fx : fxList) for (double fw : fwList) {
                int rx = clamp((int)Math.round(w*fx), 0, w-1);
                int ry = clamp((int)Math.round(h*fy), 0, h-1);
                int rw = clamp((int)Math.round(w*fw), 1, w-rx);
                int rh = clamp((int)Math.round(h*fh), 1, h-ry);
                Rect r = new Rect(rx, ry, rw, rh);
                String got = ocrOnceOnRoi(ocr, bgr, r, "roi_"+(roiIdx++));
                if (got == null) continue;

                String cleaned = got.toUpperCase().replaceAll("[^A-Z0-9]","");
                String norm = normalizePlate(cleaned);
                int score = (norm != null ? 100 : cleaned.length()); // валидный шаблон — максимум
                String candidate = (norm != null ? norm : cleaned);
                if (log.isDebugEnabled()) {
                    log.debug("OCR roi#{} rect=({}, {}, {}, {}): raw='{}' cleaned='{}' norm='{}' score='{}'",
                            roiIdx - 1, rx, ry, rw, rh, got, cleaned, norm, score);
                }
                if (score > bestScore) { bestScore = score; best = candidate; }
                if (bestScore >= 100) return best; // нашли валидный LLDDDDLL
            }

        if (log.isDebugEnabled()) log.debug("OCR best='{}' score={}", best, bestScore);
        return best;
    }

    private static String ocrOnceOnRoi(OcrService ocr, Mat bgr, Rect r, String tag) {
        Mat roi = new Mat(bgr, r).clone();
        Mat gray = new Mat(); opencv_imgproc.cvtColor(roi, gray, opencv_imgproc.COLOR_BGR2GRAY);
        var clahe = opencv_imgproc.createCLAHE(2.0, new Size(8,8));
        Mat eq = new Mat(); clahe.apply(gray, eq);
        Mat den = new Mat(); opencv_imgproc.bilateralFilter(eq, den, 5, 75, 75);
        Mat up = new Mat(); opencv_imgproc.resize(den, up, new Size(den.cols()*2, den.rows()*2));
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
        Mat k = opencv_imgproc.getStructuringElement(opencv_imgproc.MORPH_RECT, new Size(3,3));
        opencv_imgproc.morphologyEx(bin, bin, opencv_imgproc.MORPH_CLOSE, k);
        double fill = opencv_core.countNonZero(bin)/(double)(bin.rows()*bin.cols());
        // оценка контраста ROI через стандартное отклонение яркости
        Mat mean = new Mat();
        Mat stddev = new Mat();
        opencv_core.meanStdDev(gray, mean, stddev);
        double contrast = stddev.createIndexer().getDouble(0) / 255.0;
        // пороги управляемы через -Dqv.ocr.minContrast / -Dqv.ocr.fillMin / -Dqv.ocr.fillMax
        double minContrast = Double.parseDouble(System.getProperty("qv.ocr.minContrast", "0.10"));
        double fillMin     = Double.parseDouble(System.getProperty("qv.ocr.fillMin", "0.01"));
        double fillMax     = Double.parseDouble(System.getProperty("qv.ocr.fillMax", "0.90"));
        boolean needFallback = (fill < fillMin || fill > fillMax || contrast < minContrast);
        if (needFallback) {
            // альтернативная адаптивная бинаризация с более крупным окном
            int b2 = Integer.getInteger("qv.ocr.adaptBlock2", 41);
            int c2 = Integer.getInteger("qv.ocr.adaptC2", 2);
            if ((b2 & 1)== 0) b2++;
            Mat bin2 = new Mat();
            opencv_imgproc.adaptiveThreshold(
                    up, bin2, 255,
                    opencv_imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                    opencv_imgproc.THRESH_BINARY, b2, c2);
            double fill2 = opencv_core.countNonZero(bin2)/(double)(bin2.rows()*bin2.cols());
            if (fill2 >= fillMin && fill2 <= fillMax) {
                if (log.isDebugEnabled()) {
                    log.debug("OCR fallback#1 OK (fill2={}) rect=({}, {}, {}, {})",
                            String.format("%.3f", fill2), r.x(), r.y(), r.width(), r.height());
                }
                bin.release(); bin = bin2; fill = fill2;
                needFallback = false;
            } else {
                bin2.release();
            }
        }
        if (needFallback) {
            // Otsu как последний вариант
            Mat bin3 = new Mat();
            opencv_imgproc.threshold(up, bin3, 0, 255, opencv_imgproc.THRESH_BINARY + opencv_imgproc.THRESH_OTSU);
            double fill3 = opencv_core.countNonZero(bin3)/(double)(bin3.rows()*bin3.cols());
            if (fill3 >= fillMin && fill3 <= fillMax) {
                if (log.isDebugEnabled()) {
                    log.debug("OCR fallback#2 Otsu OK (fill3={}) rect({}, {}, {}, {})",
                            String.format("%.3f", fill3), r.x(), r.y(), r.width(), r.height());
                }
                bin.release(); bin = bin3; fill = fill3;
                needFallback = false;
            } else {
                bin3.release();
            }

        }
        if (needFallback) {
            if (log.isDebugEnabled()) {
                log.debug("OCR skip after fallbacks (fill={}, contrast={}, minC={}, fMin={}, fMax={}) rect({}, {}, {}, {}",
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
        if (!ok) { release(k, bin, up, den, eq, gray, roi); return null; }
        byte[] bytes = new byte[(int)buf.limit()]; buf.get(bytes);
        try {
            BufferedImage bi = ImageIO.read(new ByteArrayInputStream(bytes));
            // сначала пробуем самый уверенный токен, потом общий текст
            String raw = ocr.readBestToken(bi).orElse(null);
            if (raw == null) {
                // Fallback: инверсия бинарного изображения и повторная попытка
                Mat binInv = new Mat();
                opencv_core.bitwise_not(bin, binInv);
                BytePointer buf2 = new BytePointer();
                boolean ok2 = opencv_imgcodecs.imencode(".png", binInv, buf2);
                if (ok2) {
                    byte[] b2 = new byte[(int)buf2.limit()];
                    buf2.get(b2);
                    BufferedImage bi2 = ImageIO.read(new ByteArrayInputStream(b2));
                    raw = ocr.readBestToken(bi2).orElse(null);
                }
                buf2.deallocate();
                binInv.release();
                if (raw == null) return null;
            }
            return raw.trim();
        } catch (Exception ignore) {
            return null;
        } finally {
            buf.deallocate();
            release(k, bin, up, den, eq, gray, roi);
        }
    }

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

        // 1) две гипотезы путаниц:
        //    a) буквенная: цифры, похожие на буквы → буквы (0→O,1→I,5→S,2→Z,8→B)
        String lettersBias = base
                .replace('0','O').replace('1','I').replace('5','S').replace('2','Z').replace('8','B');
        //    b) цифровая: буквы, похожие на цифры → цифры (O→0,I→1,S→5,Z→2,B→8)
        String digitsBias = base
                .replace('O','0').replace('I','1').replace('S','5').replace('Z','2').replace('B','8');

        // 2) СНАЧАЛА пробуем чистый generic на исходной строке
        String p;
        if ((p = extractGenericPlate(base))  != null) return p;
        // затем RU на исходной и цифровой
        if ((p = extractRuPlate(base))       != null) return p;
        if ((p = extractRuPlate(digitsBias)) != null) return p;

        // 3) generic на bias-вариантах
        if ((p = extractGenericPlate(lettersBias)) != null) return p;
        if ((p = extractGenericPlate(digitsBias))  != null) return p;

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

    /** Разрешённые буквы для RU (латинские двойники): A B E K M H O P C T Y X */
    private static final String RU_LAT_SET = "ABEKMHOPCTYX";
    private static boolean isDigit(char c) { return c >= '0' && c <= '9'; }
    private static boolean isRuLatLetter(char c) { return RU_LAT_SET.indexOf(c) > 0; }
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

    public int effectiveMergeMs() {
        return Integer.getInteger("qv.mergeMs", this.mergeMs);
    }

    private static void release(Mat... mats) {
        for (Mat m : mats) if (m != null) m.release();
    }
}
