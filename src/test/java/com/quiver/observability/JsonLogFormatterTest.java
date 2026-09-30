package com.quiver.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.Test;

class JsonLogFormatterTest {

    private static final Gson GSON = new Gson();

    @Test
    void formatsAsASingleValidJsonLine() {
        JsonLogFormatter formatter = new JsonLogFormatter("A");
        LogRecord record = new LogRecord(Level.INFO, "node started");
        record.setLoggerName("com.quiver.network.NodeServer");

        String line = formatter.format(record);

        assertTrue(line.endsWith(System.lineSeparator()));
        JsonObject json = GSON.fromJson(line, JsonObject.class);
        assertEquals("INFO", json.get("level").getAsString());
        assertEquals("NodeServer", json.get("logger").getAsString(),
                "the fully-qualified logger name should be shortened to just the class name");
        assertEquals("A", json.get("node").getAsString());
        assertEquals("node started", json.get("message").getAsString());
        assertTrue(json.has("time"));
    }

    @Test
    void timeFieldIsTheRecordsExactInstantInIso8601() {
        JsonLogFormatter formatter = new JsonLogFormatter("A");
        LogRecord record = new LogRecord(Level.INFO, "x");
        record.setInstant(java.time.Instant.parse("2023-11-14T22:13:20Z"));

        JsonObject json = GSON.fromJson(formatter.format(record), JsonObject.class);

        assertEquals("2023-11-14T22:13:20Z", json.get("time").getAsString());
    }

    @Test
    void exceptionDetailsAreIncludedWhenPresent() {
        JsonLogFormatter formatter = new JsonLogFormatter("B");
        LogRecord record = new LogRecord(Level.WARNING, "something failed");
        record.setThrown(new IllegalStateException("bad state"));

        JsonObject json = GSON.fromJson(formatter.format(record), JsonObject.class);

        assertEquals("java.lang.IllegalStateException", json.get("exceptionType").getAsString());
        assertEquals("bad state", json.get("exceptionMessage").getAsString());
    }

    @Test
    void noExceptionFieldsWhenNothingWasThrown() {
        JsonLogFormatter formatter = new JsonLogFormatter("A");
        LogRecord record = new LogRecord(Level.INFO, "all fine");

        JsonObject json = GSON.fromJson(formatter.format(record), JsonObject.class);

        assertFalse(json.has("exceptionType"));
        assertFalse(json.has("exceptionMessage"));
    }

    @Test
    void messageParametersAreSubstituted() {
        JsonLogFormatter formatter = new JsonLogFormatter("A");
        LogRecord record = new LogRecord(Level.INFO, "peer {0} unreachable");
        // A String, deliberately not a number: java.util.logging formats parameters with
        // MessageFormat, which renders an Integer like 9001 as "9,001" using the locale's
        // grouping separator. Testing that would make this test depend on the machine's
        // locale, which says nothing about whether the formatter itself is right.
        record.setParameters(new Object[] {"B"});

        JsonObject json = GSON.fromJson(formatter.format(record), JsonObject.class);

        assertEquals("peer B unreachable", json.get("message").getAsString());
    }

    @Test
    void nullLoggerNameDoesNotThrow() {
        JsonLogFormatter formatter = new JsonLogFormatter("A");
        LogRecord record = new LogRecord(Level.INFO, "x");
        record.setLoggerName(null);

        JsonObject json = GSON.fromJson(formatter.format(record), JsonObject.class);

        assertEquals("", json.get("logger").getAsString());
    }
}
