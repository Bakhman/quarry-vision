package com.quarryvision.core.detection;

import java.util.List;

public class TripSegmenter {

    private TripSegmenter() {}

    public static List<TripSegment> segment(
            int detectionId,
            List<Long> eventTimesMs,
            List<String> normalizedPlates, // та же длина, элементы могут быть null
            long gapMs
    ) {
        // пока заглушка: выбрасываем UnsupportedOperationException
        throw new UnsupportedOperationException("Not implemented yet");
    }
}
