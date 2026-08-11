package net.mineacle.core.common.storage;

import net.mineacle.core.Core;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;

/**
 * Coalesces hot YAML saves and moves filesystem I/O off the server thread.
 * <p>
 * Bukkit's FileConfiguration is serialized only on the primary thread. The
 * resulting immutable String snapshot is written by one ordered background
 * writer using temp-file plus atomic replace where supported.
 */
public final class DebouncedYamlPersistence {

    private static final long DEFAULT_DEBOUNCE_TICKS =
            5L;
    private static final long FLUSH_TIMEOUT_SECONDS =
            5L;

    private final Core core;
    private final long debounceTicks;
    private final ExecutorService writer;
    private final Map<String, Slot> slots =
            new HashMap<>();

    private volatile boolean closed;

    public DebouncedYamlPersistence(
            Core core
    ) {
        this.core = core;
        this.debounceTicks = Math.max(
                1L,
                core.getConfig().getLong(
                        "persistence.yaml-debounce-ticks",
                        DEFAULT_DEBOUNCE_TICKS
                )
        );

        this.writer =
                Executors
                        .newSingleThreadExecutor(
                                runnable -> {
                                    Thread thread =
                                            new Thread(
                                                    runnable,
                                                    "Mineacle-YamlPersistence"
                                            );
                                    thread.setDaemon(
                                            true
                                    );
                                    return thread;
                                }
                        );
    }

    public void request(
            String label,
            FileConfiguration configuration,
            File file
    ) {
        if (configuration == null
                || file == null
                || label == null
                || label.isBlank()) {
            return;
        }

        if (!Bukkit.isPrimaryThread()) {
            if (!closed
                    && core.isEnabled()) {
                core.getServer()
                        .getScheduler()
                        .runTask(
                                core,
                                () -> request(
                                        label,
                                        configuration,
                                        file
                                )
                        );
            }
            return;
        }

        if (closed) {
            return;
        }

        String key = label.trim();
        Slot slot =
                slots.computeIfAbsent(
                        key,
                        Slot::new
                );

        slot.configuration =
                configuration;
        slot.file = file;
        slot.dirty = true;

        if (slot.snapshotTask != null) {
            return;
        }

        slot.snapshotTask =
                core.getServer()
                        .getScheduler()
                        .runTaskLater(
                                core,
                                () -> snapshot(slot),
                                debounceTicks
                        );
    }

    /**
     * Flushes the supplied current in-memory state without shutting down the
     * ordered writer.
     */
    public void flushNow(
            List<Target> targets
    ) {
        if (closed
                || targets == null
                || targets.isEmpty()) {
            return;
        }

        requirePrimaryThread("flushNow");
        cancelPendingSnapshots();

        waitFor(
                submitCurrentSnapshots(
                        targets
                )
        );
    }

    /**
     * Stops accepting new writes, snapshots the supplied current state, waits
     * for the ordered writer, then shuts it down.
     */
    public void flushAndShutdown(
            List<Target> targets
    ) {
        if (closed) {
            return;
        }

        requirePrimaryThread(
                "flushAndShutdown"
        );

        closed = true;
        cancelPendingSnapshots();

        waitFor(
                submitCurrentSnapshots(
                        targets == null
                                ? List.of()
                                : targets
                )
        );

        writer.shutdown();

        try {
            if (!writer.awaitTermination(
                    FLUSH_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
            )) {
                writer.shutdownNow();
                core.getLogger().warning(
                        "Timed out shutting down YAML persistence writer"
                );
            }
        } catch (InterruptedException exception) {
            Thread.currentThread()
                    .interrupt();
            writer.shutdownNow();
        }

        slots.clear();
    }

    private void snapshot(
            Slot slot
    ) {
        slot.snapshotTask = null;

        if (closed || !slot.dirty) {
            return;
        }

        if (slot.configuration == null
                || slot.file == null) {
            slot.dirty = false;
            return;
        }

        slot.dirty = false;

        String content = serialize(
                slot.label,
                slot.configuration
        );

        if (content == null) {
            return;
        }

        submitWrite(
                slot,
                content,
                slot.file.toPath()
        );
    }

    private List<Future<?>>
    submitCurrentSnapshots(
            List<Target> targets
    ) {
        List<Future<?>> futures =
                new ArrayList<>(
                        targets.size()
                );

        for (Target target : targets) {
            if (target == null
                    || target.configuration()
                    == null
                    || target.file() == null
                    || target.label() == null
                    || target.label()
                    .isBlank()) {
                continue;
            }

            String label =
                    target.label().trim();

            String content = serialize(
                    label,
                    target.configuration()
            );

            if (content == null) {
                continue;
            }

            Slot slot =
                    slots.computeIfAbsent(
                            label,
                            Slot::new
                    );

            slot.configuration =
                    target.configuration();
            slot.file = target.file();
            slot.dirty = false;

            futures.add(
                    submitWrite(
                            slot,
                            content,
                            target.file()
                                    .toPath()
                    )
            );
        }

        return futures;
    }

    private Future<?> submitWrite(
            Slot slot,
            String content,
            Path target
    ) {
        long generation =
                slot.latestGeneration
                        .incrementAndGet();

        return writer.submit(
                () -> {
                    /*
                     * If a newer snapshot was queued before this write starts,
                     * this generation is obsolete and can be skipped safely.
                     */
                    if (generation
                            < slot.latestGeneration
                            .get()) {
                        return;
                    }

                    try {
                        writeAtomically(
                                target,
                                content
                        );
                    } catch (IOException exception) {
                        core.getLogger().log(
                                Level.SEVERE,
                                "Could not persist "
                                        + slot.label,
                                exception
                        );
                    }
                }
        );
    }

    private String serialize(
            String label,
            FileConfiguration configuration
    ) {
        try {
            return configuration
                    .saveToString();
        } catch (RuntimeException exception) {
            core.getLogger().log(
                    Level.SEVERE,
                    "Could not serialize "
                            + label,
                    exception
            );
            return null;
        }
    }

    private void cancelPendingSnapshots() {
        for (Slot slot
                : slots.values()) {
            if (slot.snapshotTask != null) {
                slot.snapshotTask.cancel();
                slot.snapshotTask = null;
            }
        }
    }

    /**
     * Uses one total timeout budget for the complete flush rather than giving
     * every file its own full timeout window.
     */
    private void waitFor(
            List<Future<?>> futures
    ) {
        if (futures.isEmpty()) {
            return;
        }

        long deadline =
                System.nanoTime()
                        + TimeUnit.SECONDS
                        .toNanos(
                                FLUSH_TIMEOUT_SECONDS
                        );

        for (Future<?> future : futures) {
            long remaining =
                    deadline
                            - System.nanoTime();

            if (remaining <= 0L) {
                core.getLogger().warning(
                        "Timed out flushing YAML persistence"
                );
                return;
            }

            try {
                future.get(
                        remaining,
                        TimeUnit.NANOSECONDS
                );
            } catch (InterruptedException exception) {
                Thread.currentThread()
                        .interrupt();
                return;
            } catch (ExecutionException exception) {
                core.getLogger().log(
                        Level.SEVERE,
                        "YAML persistence task failed",
                        exception.getCause()
                );
            } catch (TimeoutException exception) {
                core.getLogger().warning(
                        "Timed out flushing YAML persistence"
                );
                return;
            }
        }
    }

    private void writeAtomically(
            Path target,
            String content
    ) throws IOException {
        Path absolute =
                target.toAbsolutePath();
        Path parent =
                absolute.getParent();

        if (parent != null) {
            Files.createDirectories(parent);
        }

        Path temporary =
                absolute.resolveSibling(
                        absolute.getFileName()
                                + ".tmp"
                );

        Files.writeString(
                temporary,
                content,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption
                        .TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        );

        try {
            Files.move(
                    temporary,
                    absolute,
                    StandardCopyOption
                            .ATOMIC_MOVE,
                    StandardCopyOption
                            .REPLACE_EXISTING
            );
        } catch (
                AtomicMoveNotSupportedException ignored
        ) {
            Files.move(
                    temporary,
                    absolute,
                    StandardCopyOption
                            .REPLACE_EXISTING
            );
        }
    }

    private void requirePrimaryThread(
            String operation
    ) {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException(
                    operation
                            + " must run on the server thread"
            );
        }
    }

    public record Target(
            String label,
            FileConfiguration configuration,
            File file
    ) {
    }

    private static final class Slot {

        private final String label;
        private FileConfiguration configuration;
        private File file;
        private boolean dirty;
        private BukkitTask snapshotTask;
        private final AtomicLong latestGeneration =
                new AtomicLong();

        private Slot(String label) {
            this.label = label;
        }
    }
}
