package com.quarryvision.core.detection;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PlateNormalizeTest {
    static Method normalize;

    @BeforeAll
    static void find() throws Exception {
        normalize = BucketDetector.class.getDeclaredMethod("normalizePlate", String.class);
        normalize.setAccessible(true);
    }

    private String norm(String s) {
        try {
            return (String) normalize.invoke(null, s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test void exact_ok_generic() { assertEquals("AB1234CD", norm("AB1234CD")); }
    @Test void mixed_confusions_letters_bias() { assertEquals("AB1234CD", norm("A81234CD")); } // 8->B
    @Test void mixed_confusions_digits_bias()  { assertEquals("AB1254CD", norm("ABIZS4CD".replace('Z','2'))); }
    @Test void o_to_zero() { assertEquals("AB1034CD", norm("ABI034CD".replace('I', '1'))); }
    @Test void too_short_returns_null() { assertNull(norm("A1C")); }
    @Test void garbage_returns_null() { assertNull(norm("###***")); }
    @Test void ru_plate_basic()  { assertEquals("O793PP",     norm("о793рр")); }
    @Test void ru_with_region()  { assertEquals("O793PP",  norm("О793РР123")); }
    @Test void generic_short_ok() {assertEquals("BE8624", norm("BE 8624"));} // пробелы/разделители допускаются
    @Test void ru_plate_cant_start_with_A() {assertEquals("A123BC", norm("A123BC"));} // проверка isRuLatLetter
}
