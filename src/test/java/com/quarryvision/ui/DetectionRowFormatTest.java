package com.quarryvision.ui;

import com.quarryvision.core.db.DbDetectionRow;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class DetectionRowFormatTest {

    @Test
    void formatAndParseRoundTripKeepsId() {
        DbDetectionRow row = new DbDetectionRow(
                42,
                10,
                "/data/videos/test.mp4",
                5000,
                3,
                OffsetDateTime.of(2025,  1, 2, 3, 4,  5, 0, ZoneOffset.UTC)
        );

        String formatted = DetectionRowFormat.formatDetectionRow(row);
        Integer parseId = DetectionRowFormat.parseDetectionId(formatted);

        assertNotNull(parseId);
        assertEquals(42, parseId.intValue());
        assertTrue(formatted.startsWith("#42"));
    }

    @Test
    void parseDetectionIdReturnsNullOnGarbage() {
        assertNull(DetectionRowFormat.parseDetectionId(null));
        assertNull(DetectionRowFormat.parseDetectionId(""));
        assertNull(DetectionRowFormat.parseDetectionId("no hash here"));
        assertNull(DetectionRowFormat.parseDetectionId("## two hashes"));
        assertNull(DetectionRowFormat.parseDetectionId("# not-a-number | x"));
        assertNull(DetectionRowFormat.parseDetectionId("#123"));        // нет пробела
        assertNull(DetectionRowFormat.parseDetectionId("prefix #123")); // hash не в начале
    }
}

