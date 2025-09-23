package com.quarryvision.core.importer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.bytedeco.opencv.global.opencv_videoio;
import org.bytedeco.opencv.opencv_videoio.VideoCapture;

/** Сканирует srcRoot, фильтрует по маскам, передаёт каждый файл в IngestProcessor.
 * Дедупликация по SHA-256 уже реализована в IngestProcessor (manifest.txt).
 * */
public final class UsbIngestService {
    private static final Logger log = LoggerFactory.getLogger(UsbIngestService.class);

    private final IngestProcessor ingest;       // копирование + manifest.txt
    private final List<PathMatcher> matchers;   // glob-маски (*.mp4 и m.n)

    public UsbIngestService(IngestProcessor ingest, List<String> patterns) {
        this.ingest = Objects.requireNonNull(ingest);
        var pats = (patterns == null || patterns.isEmpty())
                ? List.of("**/*.mp4", "**/*.mkv", "**/*.ts")
                : patterns;
        this.matchers = pats.stream()
                .map(p -> FileSystems.getDefault().getPathMatcher("glob:" + p))
                .collect(Collectors.toList());
    }

    /** Возвращает число новых файлов (не дубликатов). Ошибки по отдельным файлам — в лог. */
    public int scanAndIngest(Path srcRoot) {
        List<Path> accepted = new ArrayList<>();
        try {
            Files.walk(srcRoot)
                    .filter(Files::isRegularFile)
                    .filter(this::matchesAny)
                    .forEach(p -> {
                        try {
                            var dst = ingest.ingest(p);
                            if (dst != null) {
                                accepted.add(dst);
                                log.info("ingested: {} -> {}", p, dst);
                            } else {
                                log.debug("skip duplicate: {}", p);
                            }
                        } catch (Exception e) {
                            log.warn("failed to ingest {}: {}", p, e.toString());
                        }
                    });
        } catch (IOException e) {
            throw new RuntimeException("USB scan failed: " + srcRoot, e);
        }
        log.info("USB ingest done: {} new files, root={}", accepted.size(), srcRoot);
        return accepted.size();
    }

    private boolean matchesAny(Path path) {
        var name = path.getFileName();
        for (var m : matchers) {
            if (m.matches(path) || (name != null && m.matches(name))) {
                return true;
            }
        }
        return false;
    }

    /** Получить длительность видео в миллисекундах. Возвращфет 0 при ошибке. */
    public static long getDurationsMs(Path file) {
        try (VideoCapture cap = new VideoCapture(file.toString())) {
            if (!cap.isOpened()) return 0L;
            double fps = cap.get(opencv_videoio.CAP_PROP_FPS);
            if (fps <= 0) fps = 25.0;
            double frames = cap.get(opencv_videoio.CAP_PROP_FRAME_COUNT);
            return (long) ((frames / fps) * 1000);
        } catch (Exception e) {
            log.warn("cannot read duration {}: {}", file, e.toString());
            return 0L;
        }
    }

}
