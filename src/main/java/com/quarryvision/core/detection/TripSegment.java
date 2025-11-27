package com.quarryvision.core.detection;

public record TripSegment(
        int detectionId,
        String plate,       // nullable, если undefined
        int fromIndex,      // индекс первого события в списке
        int toIndex,        // индекс последнего события (включительно)
        long tStartMs,
        long tEndMs,
        int eventsCount
) {
}
