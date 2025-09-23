package com.quarryvision.core.detection;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

public record DetectionResult(Path video,
                              int events,
                              List<Instant> timestampsMs,
                              double fps,
                              long frames) {
}
