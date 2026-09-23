package com.quiver.node;

import com.google.gson.Gson;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * An append-only, crash-durable log of {@link ObjectStore.ObjectSnapshot} entries.
 *
 * <p>Every successful mutation on a node's {@link ObjectStore} is appended here as a full
 * snapshot of the object it touched, via {@link ObjectStore#setChangeListener}. Replaying
 * this file back through {@link ObjectStore#applyOneSnapshot} on startup is safe in any
 * order and any number of times: that method only ever goes through create/grant/merge
 * operations that are already commutative, associative and idempotent, so a torn last
 * line from a crash mid-append, or the same line appearing twice, changes nothing.
 *
 * <p>What this does <em>not</em> give you: compaction. The file grows by one line per
 * mutation forever. For a portfolio-scale demo that is a non-issue; a real deployment
 * would periodically rewrite the file down to one snapshot per object (exactly what
 * {@link ObjectStore#snapshot()} already produces).
 */
public final class JournalStore implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(JournalStore.class.getName());
    private static final Gson GSON = new Gson();

    private final RandomAccessFile file;
    private final FileChannel channel;

    private JournalStore(RandomAccessFile file) {
        this.file = file;
        this.channel = file.getChannel();
    }

    /** Opens (creating if necessary) the journal file at {@code path} for appending. */
    public static JournalStore open(Path path) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        RandomAccessFile raf = new RandomAccessFile(path.toFile(), "rw");
        raf.seek(raf.length());
        return new JournalStore(raf);
    }

    /**
     * Replays every well-formed line in {@code path} into {@code store}. A missing file
     * is not an error: there is simply nothing to replay yet (first run).
     *
     * <p>Call this <em>before</em> attaching this journal as {@code store}'s change
     * listener, otherwise replay would immediately re-append every line it just read.
     */
    public static void replayInto(Path path, ObjectStore store) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        int applied = 0;
        for (String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            try {
                ObjectStore.ObjectSnapshot snapshot =
                        GSON.fromJson(line, ObjectStore.ObjectSnapshot.class);
                if (snapshot != null && snapshot.name() != null && !snapshot.name().isBlank()) {
                    store.applyOneSnapshot(snapshot, snapshot.owner());
                    applied++;
                }
            } catch (RuntimeException e) {
                // Most likely a torn line from a crash mid-write. Skip it and keep going
                // rather than fail the whole startup over one bad line.
                LOG.warning("Skipping unreadable journal line in " + path + ": " + e.getMessage());
            }
        }
        int total = applied;
        LOG.info(() -> "Replayed " + total + " journal entries from " + path);
    }

    /** Appends one snapshot as a JSON line and forces it to disk before returning. */
    public synchronized void append(ObjectStore.ObjectSnapshot snapshot) {
        try {
            String line = GSON.toJson(snapshot) + "\n";
            file.write(line.getBytes(StandardCharsets.UTF_8));
            channel.force(false); // fsync data (not metadata) so a crash right after this call loses nothing
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Failed to persist journal entry for '" + snapshot.name() + "'", e);
        }
    }

    /** A ready-to-use listener for {@link ObjectStore#setChangeListener}. */
    public java.util.function.Consumer<ObjectStore.ObjectSnapshot> asChangeListener() {
        return this::append;
    }

    @Override
    public void close() {
        try {
            channel.force(true);
            file.close();
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Error closing journal", e);
        }
    }
}
