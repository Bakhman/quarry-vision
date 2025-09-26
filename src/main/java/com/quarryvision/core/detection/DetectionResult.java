package com.quarryvision.core.detection;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

public record DetectionResult(
        Path video,
        // оставляем поле для совместимости, но игнорируем его в геттере
        int events,
        List<Instant> timestampsMs,
        double fps,
        long frames
) {
    @Override
    public int events() {
        return timestampsMs == null ? 0 : timestampsMs.size();
    }
}
