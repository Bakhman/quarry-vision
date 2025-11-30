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
        long frames,
        List<String> plates
) {

    /**
     * Упрощённый конструктор для существующего кода, где plates ещё не используются.
     */
    public DetectionResult(
            Path video,
            int events,
            List<Instant> timestampsMs,
            double fps,
            long frames
    ) {
        this(video, events, timestampsMs, fps, frames, List.of());
    }

    @Override
    public int events() {
        return timestampsMs == null ? 0 : timestampsMs.size();
    }

    /**
     * Гарантированно непустая коллекция для plates, даже если в конструктор передали null.
     */
    public List<String> platesOrEmpty() {
        return plates == null ? List.of() : plates;
    }
}
