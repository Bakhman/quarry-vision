package com.quarryvision.ui;

import com.quarryvision.core.db.DbDetectionRow;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

public class MainControllerFormatTest {

    private String invokeFormat(MainController mc, DbDetectionRow row) throws Exception {
        Method fmt = MainController.class.getDeclaredMethod("formatDetectionRow", DbDetectionRow.class);
        fmt.setAccessible(true);
        return (String) fmt.invoke(mc, row);
    }

    private Integer invokeParse(MainController mc, String s) throws Exception {
        Method parse = MainController.class.getDeclaredMethod("parseDetectionId", String.class);
        parse.setAccessible(true);
        return (Integer) parse.invoke(mc, s);
    }

    @Test
    void formatAndParseRoundTripKeepsId() throws Exception {
        MainController mc = new MainController();
        DbDetectionRow row = new DbDetectionRow(
                42,
                10,
                "/data/videos/test.mp4",
                5000,
                3,
                OffsetDateTime.of(2025, 1, 2, 3, 4, 5, 0, ZoneOffset.UTC)
        );
        String formatted = invokeFormat(mc, row);
        Integer parseId = invokeParse(mc, formatted);

        assertNotNull(parseId, "parseDetectionId should not return null for valid formatted string");
        assertEquals(42, parseId.intValue(), "Parsed id must match original");
        assertTrue(formatted.startsWith("#42 "), "Formatted string must start with \"#42 \"");
    }

    @Test
    void parseDetectionIdReturnsNullOnGarbage() throws Exception {
        MainController mc = new MainController();

        assertNull(invokeParse(mc, null));
        assertNull(invokeParse(mc, ""));
        assertNull(invokeParse(mc, "no hash here"));
        assertNull(invokeParse(mc, "# not-a-number | something"));
        assertNull(invokeParse(mc, "#123")); // нет пробела/ continuation
        assertNull(invokeParse(mc, "prefix #123"));         // hash not in started
    }
}
