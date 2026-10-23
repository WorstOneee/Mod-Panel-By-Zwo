package dev.zwo.modpanel.util;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DurationsTest {

    @Test
    void parsesCommonFormats() {
        assertEquals(Duration.ofMinutes(10), Durations.parse("10").orElseThrow());
        assertEquals(Duration.ofMinutes(90), Durations.parse("1h30m").orElseThrow());
        assertEquals(Duration.ofDays(2), Durations.parse("2d").orElseThrow());
        assertEquals(Duration.ofMinutes(15), Durations.parse("15 minutos").orElseThrow());
        assertEquals(Duration.ofDays(21), Durations.parse("3 semanas").orElseThrow());
        assertEquals(Duration.ofDays(30), Durations.parse("1mo").orElseThrow());
    }

    @Test
    void rejectsInvalidInput() {
        assertTrue(Durations.parse("1h30").isEmpty());
        assertTrue(Durations.parse("abc").isEmpty());
        assertTrue(Durations.parse("0m").isEmpty());
        assertTrue(Durations.parse("").isEmpty());
    }

    @Test
    void minutesAndFormatting() {
        assertEquals(120, Durations.parseMinutes("2h"));
        assertEquals(1, Durations.parseMinutes("30s"));
        assertEquals(-1, Durations.parseMinutes("x"));
        assertEquals("1d 1h 2m", Durations.format(90_061_000L));
    }
}
