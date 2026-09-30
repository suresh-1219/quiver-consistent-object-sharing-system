package com.quiver.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MetricsTest {

    @Test
    void counterRendersHelpAndTypeOnceWithCorrectValue() {
        Metrics metrics = new Metrics();

        metrics.incCounter("quiver_writes_applied_total", "Writes accepted into the CRDT", Map.of());
        metrics.incCounter("quiver_writes_applied_total", "Writes accepted into the CRDT", Map.of());
        metrics.addCounter("quiver_writes_applied_total", "Writes accepted into the CRDT", Map.of(), 3);

        String text = metrics.renderPrometheusText();

        assertTrue(text.contains("# HELP quiver_writes_applied_total Writes accepted into the CRDT"));
        assertTrue(text.contains("# TYPE quiver_writes_applied_total counter"));
        assertTrue(text.contains("quiver_writes_applied_total 5"));
        // Exactly one HELP/TYPE pair, not one per increment.
        assertEquals(1, countOccurrences(text, "# TYPE quiver_writes_applied_total"));
    }

    @Test
    void wholeNumberCountersOmitTrailingDecimal() {
        Metrics metrics = new Metrics();
        metrics.incCounter("quiver_test_total", "test", Map.of());

        assertTrue(metrics.renderPrometheusText().contains("quiver_test_total 1\n"),
                "a whole-number value should render as '1', not '1.0'");
    }

    @Test
    void differentLabelSetsAreSeparateSeriesUnderOneHelpBlock() {
        Metrics metrics = new Metrics();

        metrics.incCounter("quiver_messages_rejected_total", "Rejected messages", Map.of("reason", "auth"));
        metrics.incCounter("quiver_messages_rejected_total", "Rejected messages", Map.of("reason", "malformed"));
        metrics.incCounter("quiver_messages_rejected_total", "Rejected messages", Map.of("reason", "auth"));

        String text = metrics.renderPrometheusText();

        assertEquals(1, countOccurrences(text, "# TYPE quiver_messages_rejected_total"));
        assertTrue(text.contains("quiver_messages_rejected_total{reason=\"auth\"} 2"));
        assertTrue(text.contains("quiver_messages_rejected_total{reason=\"malformed\"} 1"));
    }

    @Test
    void multipleLabelsAreSortedForStableOutput() {
        Metrics metrics = new Metrics();

        metrics.incCounter("quiver_test_total", "test", Map.of("b", "2", "a", "1"));

        assertTrue(metrics.renderPrometheusText().contains("quiver_test_total{a=\"1\",b=\"2\"}"),
                "labels should render in a fixed (sorted) order regardless of insertion order");
    }

    @Test
    void labelValuesAreEscaped() {
        Metrics metrics = new Metrics();

        metrics.incCounter("quiver_test_total", "test", Map.of("peer", "weird\"name\\here"));

        assertTrue(metrics.renderPrometheusText().contains("peer=\"weird\\\"name\\\\here\""));
    }

    @Test
    void gaugeIsPulledAtRenderTimeNotAtRegistration() {
        Metrics metrics = new Metrics();
        int[] value = {1};

        metrics.registerGauge("quiver_known_objects", "Known objects", Map.of(), () -> (double) value[0]);
        value[0] = 7; // changes after registration, before the scrape

        assertTrue(metrics.renderPrometheusText().contains("quiver_known_objects 7"));
    }

    @Test
    void reregisteringAGaugeReplacesItsSupplier() {
        Metrics metrics = new Metrics();
        metrics.registerGauge("quiver_known_objects", "Known objects", Map.of(), () -> 1.0);
        metrics.registerGauge("quiver_known_objects", "Known objects", Map.of(), () -> 2.0);

        String text = metrics.renderPrometheusText();

        // The leading "\n" matters: without it the pattern would also match the "# HELP"
        // and "# TYPE" lines, which mention the same name, and count 3 instead of 1. A
        // real sample line is the only place the name starts a line.
        assertEquals(1, countOccurrences(text, "\nquiver_known_objects "));
        assertTrue(text.contains("quiver_known_objects 2\n"));
        assertTrue(!text.contains("quiver_known_objects 1\n"),
                "the replaced supplier must not still be reporting its old value");
    }

    /** A broken gauge must not take the rest of the scrape down with it. */
    @Test
    void aGaugeThatThrowsIsSkippedNotFatal() {
        Metrics metrics = new Metrics();
        metrics.registerGauge("quiver_broken", "broken", Map.of(), () -> {
            throw new RuntimeException("boom");
        });
        metrics.incCounter("quiver_fine_total", "fine", Map.of());

        String text = metrics.renderPrometheusText();

        assertTrue(text.contains("quiver_fine_total 1"));
        assertTrue(!text.contains("quiver_broken"));
    }

    @Test
    void emptyLabelSetRendersWithoutBraces() {
        Metrics metrics = new Metrics();
        metrics.incCounter("quiver_plain_total", "plain", Map.of());

        assertTrue(metrics.renderPrometheusText().contains("quiver_plain_total 1\n"));
    }

    private static int countOccurrences(String haystack, String needle) {
        return (int) List.of(haystack.split(java.util.regex.Pattern.quote(needle), -1)).size() - 1;
    }
}
