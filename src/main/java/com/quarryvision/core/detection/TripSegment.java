package com.quarryvision.core.detection;

/**
 * Один рейс (trip) внутри одной детекции:
 * последовательность событий ковшей, сгруппированных по времени
 * и (опционально) по номеру грузовика.
 */
public record TripSegment(
        int detectionId,
        String plate,       // нормализованный номер грузовика, null если undefined
        int fromIndex,      // индекс первого события в списке events
        int toIndex,        // индекс последнего события (включительно)
        long tStartMs,      // время первого события в рейсе
        long tEndMs         // время последнего события в рейсе
) {

    public TripSegment {
        if (fromIndex < 0 || toIndex < fromIndex) {
            throw new IllegalArgumentException("Invalid event index range: " + fromIndex + ".." + toIndex);
        }
        if (tEndMs < tStartMs) {
            throw new IllegalArgumentException("tEndsMs < tStartMs");
        }
    }

    /**
     * Количество событий (ковшей) в рейсе.
     */
    public int eventsCount() {
        return toIndex - fromIndex + 1;
    }
}
