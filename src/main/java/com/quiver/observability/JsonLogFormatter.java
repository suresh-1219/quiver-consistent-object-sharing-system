package com.quiver.observability;

import com.google.gson.Gson;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;

/**
 * Renders every {@link LogRecord} as one JSON line, instead of {@code
 * java.util.logging}'s default human-readable format.
 *
 * <p>Deliberately built as a {@link Formatter} rather than by rewriting the project's
 * existing {@code LOG.info(...)} / {@code LOG.warning(...)} call sites: those call
 * sites already exist, are already correct, and rewriting dozens of them across the
 * codebase to build structured fields by hand would be a lot of file churn for very
 * modest benefit, with real risk of introducing exactly the kind of small mismatch a
 * sweeping mechanical change tends to hide (see: the exception-type bug the Ed25519
 * round shipped and {@code mvn verify} caught). Installing one formatter at the root
 * logger gets every existing and future log call structured for free.
 *
 * <p>Each line carries: an ISO-8601 timestamp, the level, the originating logger name,
 * the rendered message, this node's id (fixed for the lifetime of one process — a
 * single JVM here is always exactly one node), and, if the record carries a thrown
 * exception, its class name and message.
 */
public final class JsonLogFormatter extends Formatter {

    private final String nodeId;
    private final Gson gson = new Gson();

    public JsonLogFormatter(String nodeId) {
        this.nodeId = nodeId;
    }

    @Override
    public String format(LogRecord record) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("time", Instant.ofEpochMilli(record.getMillis()).toString());
        fields.put("level", record.getLevel().getName());
        fields.put("logger", shortenLoggerName(record.getLoggerName()));
        fields.put("node", nodeId);
        fields.put("message", formatMessage(record));
        if (record.getThrown() != null) {
            fields.put("exceptionType", record.getThrown().getClass().getName());
            fields.put("exceptionMessage", record.getThrown().getMessage());
        }
        return gson.toJson(fields) + System.lineSeparator();
    }

    /** com.quiver.network.NodeServer -> NodeServer; short enough for a log line, still unambiguous here. */
    private static String shortenLoggerName(String loggerName) {
        if (loggerName == null) {
            return "";
        }
        int lastDot = loggerName.lastIndexOf('.');
        return lastDot < 0 ? loggerName : loggerName.substring(lastDot + 1);
    }
}
