package com.quarryvision.core.detection;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TripSegmenterTest {

    @Test
    void segmentEmptyEventsReturnsEmptyList() {
        var trips = TripSegmenter.segment(1, List.of(), List.of(), 60_000L);
        assertNotNull(trips);
        assertTrue(trips.isEmpty());
    }

    @Test
    void singleTripWhenNoGapsAboveTimeout() {
        int detId = 10;
        List<Long> times = List.of(1_000L, 20_000L, 40_000L); // все внутри 60_000 ms
        List<String> plates = List.of("ABC123", "ABC123", "ABC123");

        var trips = TripSegmenter.segment(detId, times, plates, 60_000L);

        assertEquals(1, trips.size());
        TripSegment t = trips.get(0);
        assertEquals(detId, t.detectionId());
        assertEquals(0, t.fromIndex());
        assertEquals(2, t.toIndex());
        assertEquals(1_000L, t.tStartMs());
        assertEquals(40_000L, t.tEndMs());
        assertEquals(3, t.eventsCount());
        assertEquals("ABC123", t.plate());
    }

    @Test
    void gapSplitsIntoTwoTrips() {
        int detId = 11;
        // gap между 2 и 3: 200_000 ms > 60_000 ms → два рейса
        List<Long> times = List.of(1_000L, 10_000L, 210_000L, 215_000L);
        List<String> plates = List.of("A111AA", "A111AA", "A111AA", "A111AA");

        var trips = TripSegmenter.segment(detId, times, plates, 60_000L);

        assertEquals(2, trips.size());

        TripSegment t1 = trips.get(0);
        assertEquals(0, t1.fromIndex());
        assertEquals(1, t1.toIndex());
        assertEquals(1_000L, t1.tStartMs());
        assertEquals(10_000L, t1.tEndMs());
        assertEquals(2, t1.eventsCount());
        assertEquals("A111AA", t1.plate());

        TripSegment t2 = trips.get(1);
        assertEquals(2, t2.fromIndex());
        assertEquals(3, t2.toIndex());
        assertEquals(210_000L, t2.tStartMs());
        assertEquals(215_000L, t2.tEndMs());
        assertEquals(2, t2.eventsCount());
        assertEquals("A111AA", t2.plate());
    }

    @Test
    void plateBecomesNullIfNoDominantPlate() {
        int detId = 12;
        // все номера разные → нет доминирующего >= MIN_PLATE_VOTES (2)
        List<Long> times = List.of(1_000L, 2_000L, 3_000L);
        List<String> plates = List.of("A111AA", "B222BB", "C333CC");

        var trips = TripSegmenter.segment(detId, times, plates, 60_000L);

        assertEquals(1, trips.size());
        TripSegment t = trips.get(0);
        assertNull(t.plate());
        assertEquals(3, t.eventsCount());
    }

    @Test
    void dominantPlateSelectedWhenEnoughVotes() {
        int detId = 13;
        // "A111AA" встречается 2 раза, "B222BB" — один раз → доминирует A111AA
        List<Long> times = List.of(1_000L, 2_000L, 3_000L);
        List<String> plates = List.of("A111AA", "B222BB", "A111AA");

        var trips = TripSegmenter.segment(detId, times, plates, 60_000L);

        assertEquals(1, trips.size());
        TripSegment t = trips.get(0);
        assertEquals("A111AA", t.plate());
    }

    @Test
    void nullOrBlankPlatesAreIgnoredForDominance() {
        int detId = 14;
        List<Long> times = List.of(1_000L, 2_000L, 3_000L, 4_000L);
        List<String> plates = List.of("null", " ", "A111AA", "A111AA");

        var trips = TripSegmenter.segment(detId, times, plates, 60_000L);

        assertEquals(1, trips.size());
        TripSegment t = trips.get(0);
        assertEquals("A111AA", t.plate());
    }

    @Test
    void platesSizeMismatchThrows() {
        int detId = 15;
        List<Long> times = List.of(1_000L, 2_000L, 3_000L);
        List<String> plates = List.of("A111AA"); // размер не совпадает

        assertThrows(IllegalArgumentException.class,
                () -> TripSegmenter.segment(detId, times, plates, 60_000L));
    }

    @Test
    void noPlatesListMeansAllTripsUndefinedPlate() {
        int detId = 16;
        List<Long> times = List.of(1_000L, 2_000L, 3_000L);

        var trips = TripSegmenter.segment(detId, times, null, 60_000L);

        assertEquals(1, trips.size());
        TripSegment t = trips.get(0);
        assertNull(t.plate());
        assertEquals(3, t.eventsCount());
    }

}
