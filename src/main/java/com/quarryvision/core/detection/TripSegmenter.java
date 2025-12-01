package com.quarryvision.core.detection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Сегментация списка событий (t_ms + номера) в рейсы по таймауту tripTimeout.
 *
 * Допущения:
 * - eventTimesMs отсортирован по возрастанию (как в Pg.listEventsMs: ORDER BY t_ms).
 * - normalizedPlates либо null, либо того же размера, что и eventTimesMs.
 * - plate может быть null/пустой, если номер не прочитан.
 *
 * Первая версия:
 * - рейс = непрерывный по времени участок событий;
 * - разрыв > tripTimeoutMs → закрываем текущий рейс, начинаем новый;
 * - plate выбирается как "доминирующий" номер в рейсе (по количеству вхождений),
 *   при этом нужен минимум MIN_PLATE_VOTES совпадений, иначе plate = null (undefined trip).
 *
 * TODO: позже добавить разбиение рейса по смене номера (если нужно).
 */
public final class TripSegmenter {

    // базовый таймаут для разделения рейсов (30–60 сек из аудита; начнём с 60)
    private static final long DEFAULT_TRIP_TIMEOUT_MS = 60_000L;

    // минимум совпадений номера, чтобы считать его номером рейса
    private static final int MIN_PLATE_VOTES = 2;
    private TripSegmenter() {
        // no-op
    }

    public static List<TripSegment> segment(
            int detectionId,
            List<Long> eventTimesMs,
            List<String> normalizedPlates // та же длина, элементы могут быть null
    ) {
        return segment(detectionId, eventTimesMs, normalizedPlates, DEFAULT_TRIP_TIMEOUT_MS);
    }



    /**
     * Основной метод сегментации.
     *
     * @param detectionId     id детекции (для обратной связи/отчётов)
     * @param eventTimesMs    времена событий (t_ms), отсортированы по возрастанию
     * @param normalizedPlates нормализованные номера (может быть null или empty)
     * @param tripTimeoutMs   максимальный разрыв между событиями внутри одного рейса
     */
    public static List<TripSegment> segment(
            int detectionId,
            List<Long> eventTimesMs,
            List<String> normalizedPlates,
            long tripTimeoutMs
    ) {
        if(eventTimesMs == null || eventTimesMs.isEmpty()) {
            return List.of();
        }
        final int n = eventTimesMs.size();

        List<String> plates = normalizedPlates;
        if (plates == null || plates.isEmpty()) {
            plates = null;
        } else if (plates.size() != n) {
            throw  new IllegalArgumentException("normalizedPlates.size() must be be either 0 or equal to eventTimesMs.size()");
        }

        List<TripSegment> segments = new ArrayList<>();
        int startIdx = 0;
        Map<String, Integer> plateCounts = new HashMap<>();

        for (int i = 0; i < n; i++) {
            if (i > 0) {
                long prevTime = eventTimesMs.get(i - 1);
                long curTime = eventTimesMs.get(i);
                long gap = curTime - prevTime;
                if (gap > tripTimeoutMs) {
                    // закрываем рейс [startIdx .. i-1]
                    long tStart = eventTimesMs.get(startIdx);
                    long tEnd = prevTime;
                    String plate = chooseDominantPlate(plateCounts);
                    segments.add(new TripSegment(detectionId, plate, startIdx, i - 1, tStart, tEnd));

                    // начинаем новый рейс с i-го события
                    startIdx = i;
                    plateCounts.clear();
                }
            }

            // копим статистику по номеру для текущего рейса
            if (plates != null) {
                String p = plates.get(i);
                if (p != null && !p.isBlank()) {
                    plateCounts.merge(p, 1, Integer::sum);
                }
            }
        }

        // закрываем последний рейс [startIdx .. n-1]
        long tStart = eventTimesMs.get(startIdx);
        long tEnd = eventTimesMs.get(n - 1);
        String plate = chooseDominantPlate(plateCounts);
        segments.add(new TripSegment(detectionId, plate, startIdx, n - 1, tStart, tEnd));

        return segments;
    }

    private static String chooseDominantPlate(Map<String, Integer> plateCounts) {
        if (plateCounts.isEmpty()) {
            return null;
        }
        String bestPlate = null;
        int bestCount = 0;
        for (Map.Entry<String, Integer> e : plateCounts.entrySet()) {
            int cnt = e.getValue();
            if (cnt > bestCount) {
                bestCount = cnt;
                bestPlate = e.getKey();
            }
        }
        if (bestCount >= MIN_PLATE_VOTES) {
            return bestPlate;
        }
        return null;
    }

    /**
     * Упрощённый вызов: сегментация рейсов по DetectionResult
     * с таймаутом по умолчанию.
     */
    public static List<TripSegment> segmentFromDetectionResult(
            int detectionId,
            DetectionResult detection
    ) {
        return segmentFromDetectionResult(detectionId, detection, DEFAULT_TRIP_TIMEOUT_MS);
    }

    /**
     * Сегментация рейсов по DetectionResult:
     * берём timestampsMs как времена событий и plates/platesOrEmpty как номера.
     *
     * @param detectionId   id детекции (БД id, если есть; иначе можно передавать 0/-1)
     * @param detection     результат детекции ковшей
     * @param tripTimeoutMs таймаут между событиями одного рейса
     */
    private static List<TripSegment> segmentFromDetectionResult(
            int detectionId,
            DetectionResult detection,
            long tripTimeoutMs
    ) {
        if (detection == null || detection.timestampsMs() == null || detection.timestampsMs().isEmpty()) {
            return List.of();
        }

        // конвертируем Instant → long (ms)
        List<Long> times = detection.timestampsMs().stream()
                .map(Instant::toEpochMilli)
                .toList();

        // берём plates из DetectionResult; если пустой список — считаем, что plates нет
        List<String> plates = detection.platesOrEmpty();
        List<String> normalizedPlates = plates.isEmpty() ? null : plates;

        return segment(detectionId, times, normalizedPlates, tripTimeoutMs);
    }
}
