package com.quiver.observability;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.function.Supplier;

/**
 * A minimal Prometheus-compatible metrics registry.
 *
 * <p>Deliberately not the official Prometheus Java client: pulling in that dependency
 * would need Maven Central access to verify, and the actual wire format it produces —
 * the
 * <a href="https://prometheus.io/docs/instrumenting/exposition_formats/">text exposition
 * format</a> — is a simple, stable, line-oriented text format that is not hard to
 * render correctly by hand. A real Prometheus server scraping {@link
 * MetricsHttpServer}'s {@code /metrics} endpoint cannot tell the difference.
 *
 * <p>Two kinds of series:
 * <ul>
 *   <li><b>Counters</b> — monotonically increasing; callers push updates with {@link
 *       #incCounter} / {@link #addCounter} as events happen.</li>
 *   <li><b>Gauges</b> — a point-in-time value; callers register a {@link Supplier} once
 *       with {@link #registerGauge} and this class pulls the current value only when
 *       {@link #renderPrometheusText()} is called (i.e. when something actually
 *       scrapes it), the same pull-based pattern real Prometheus client libraries use
 *       for values like "current queue depth" that have no natural "add 1" moment.</li>
 * </ul>
 *
 * <p>Thread-safe: counters use {@link DoubleAdder} (built for exactly this — many
 * threads incrementing, occasional reads), and both registries are backed by {@link
 * ConcurrentHashMap}.
 */
public final class Metrics {

    private record SeriesKey(String name, String labelText) implements Comparable<SeriesKey> {
        @Override
        public int compareTo(SeriesKey other) {
            int byName = name.compareTo(other.name);
            return byName != 0 ? byName : labelText.compareTo(other.labelText);
        }
    }

    private record MetricMeta(String help, String type) { }

    private final Map<String, MetricMeta> metaByName = new ConcurrentHashMap<>();
    private final Map<SeriesKey, DoubleAdder> counters = new ConcurrentHashMap<>();
    private final Map<SeriesKey, Supplier<Double>> gauges = new ConcurrentHashMap<>();

    public void incCounter(String name, String help, Map<String, String> labels) {
        addCounter(name, help, labels, 1.0);
    }

    public void addCounter(String name, String help, Map<String, String> labels, double amount) {
        metaByName.putIfAbsent(name, new MetricMeta(help, "counter"));
        SeriesKey key = new SeriesKey(name, renderLabels(labels));
        counters.computeIfAbsent(key, k -> new DoubleAdder()).add(amount);
    }

    /**
     * Registers a gauge whose value is pulled from {@code valueSupplier} at scrape
     * time. Registering the same name+labels again replaces the supplier, so callers
     * don't need to guard against double-registration on their own.
     */
    public void registerGauge(String name, String help, Map<String, String> labels,
                              Supplier<Double> valueSupplier) {
        metaByName.putIfAbsent(name, new MetricMeta(help, "gauge"));
        SeriesKey key = new SeriesKey(name, renderLabels(labels));
        gauges.put(key, valueSupplier);
    }

    /** Renders the full registry in Prometheus text exposition format. */
    public String renderPrometheusText() {
        // Group every series (counter or gauge) under its metric name so HELP/TYPE
        // lines are emitted exactly once per name, as the format requires.
        Map<String, StringBuilder> bodyByName = new TreeMap<>();

        for (Map.Entry<SeriesKey, DoubleAdder> e : new TreeMap<>(counters).entrySet()) {
            appendSeries(bodyByName, e.getKey(), e.getValue().sum());
        }
        for (Map.Entry<SeriesKey, Supplier<Double>> e : new TreeMap<>(gauges).entrySet()) {
            Double value;
            try {
                value = e.getValue().get();
            } catch (RuntimeException ex) {
                continue; // a broken gauge supplier should not take the whole endpoint down
            }
            if (value != null) {
                appendSeries(bodyByName, e.getKey(), value);
            }
        }

        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, StringBuilder> entry : bodyByName.entrySet()) {
            String name = entry.getKey();
            MetricMeta meta = metaByName.get(name);
            if (meta != null) {
                out.append("# HELP ").append(name).append(' ').append(meta.help()).append('\n');
                out.append("# TYPE ").append(name).append(' ').append(meta.type()).append('\n');
            }
            out.append(entry.getValue());
        }
        return out.toString();
    }

    private void appendSeries(Map<String, StringBuilder> bodyByName, SeriesKey key, double value) {
        StringBuilder line = bodyByName.computeIfAbsent(key.name(), n -> new StringBuilder());
        line.append(key.name()).append(key.labelText()).append(' ').append(formatValue(value)).append('\n');
    }

    /** Prometheus renders whole numbers without a trailing ".0"; fractions keep full precision. */
    private static String formatValue(double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value)) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }

    /**
     * Renders a label set as {@code {k="v",k2="v2"}}, sorted for stable output, with
     * quotes and backslashes in values escaped per the exposition format spec. An empty
     * label set renders as an empty string (no braces), which is also valid.
     */
    private static String renderLabels(Map<String, String> labels) {
        if (labels == null || labels.isEmpty()) {
            return "";
        }
        Map<String, String> sorted = new LinkedHashMap<>(new TreeMap<>(labels));
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(e.getKey()).append("=\"").append(escape(e.getValue())).append('"');
        }
        return sb.append('}').toString();
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
