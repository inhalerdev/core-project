package net.mineacle.core.auctionhouse.storage;

import net.mineacle.core.Core;
import net.mineacle.core.auctionhouse.model.AuctionHistoryEntry;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.logging.Level;

public final class AuctionHistoryStorage {

    private static final String EXTENSION = ".yml";
    private static final int DEFAULT_MAX_ENTRIES = 100;
    private static final int MIN_MAX_ENTRIES = 25;
    private static final int MAX_MAX_ENTRIES = 500;
    private static final int WORK_QUEUE_CAPACITY = 256;
    private static final int MAX_CACHED_PLAYERS = 2_048;
    private static final long MAX_HISTORY_FILE_BYTES = 1_048_576L;

    private final Core core;
    private final File folder;
    private final Object ioLock =
            new Object();
    private final Map<UUID, List<AuctionHistoryEntry>> cache =
            new ConcurrentHashMap<>();
    private final Map<UUID, Long> cacheAccess =
            new ConcurrentHashMap<>();
    private final java.util.Set<UUID> loaded =
            ConcurrentHashMap.newKeySet();

    private int maximumEntries = DEFAULT_MAX_ENTRIES;
    private volatile long generation;
    private boolean initialized;
    private boolean directorySyncWarningLogged;
    private volatile ThreadPoolExecutor worker;

    public AuctionHistoryStorage(
            Core core
    ) {
        this.core = core;
        this.folder =
                new File(
                        new File(
                                core.getDataFolder(),
                                "auctionhouse"
                        ),
                        "history"
                );
    }

    public synchronized void configureMaximumEntries(
            int maximumEntries
    ) {
        this.maximumEntries =
                Math.clamp(
                        maximumEntries,
                        MIN_MAX_ENTRIES,
                        MAX_MAX_ENTRIES
                );
    }

    public synchronized void initialize() {
        if (initialized) {
            return;
        }

        ensureDirectory(folder);
        generation++;

        worker =
                new ThreadPoolExecutor(
                        1,
                        1,
                        0L,
                        TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(
                                WORK_QUEUE_CAPACITY
                        ),
                        runnable -> {
                            Thread thread =
                                    new Thread(
                                            runnable,
                                            "Mineacle-AuctionHistory"
                                    );
                            thread.setDaemon(true);
                            return thread;
                        },
                        new ThreadPoolExecutor.AbortPolicy()
                );

        initialized = true;
    }

    public synchronized void shutdown() {
        generation++;

        ThreadPoolExecutor current =
                worker;
        worker = null;
        initialized = false;

        if (current != null) {
            current.shutdownNow();
        }

        cache.clear();
        cacheAccess.clear();
        loaded.clear();
    }

    public List<AuctionHistoryEntry> snapshot(
            UUID playerId
    ) {
        if (playerId == null) {
            return List.of();
        }

        List<AuctionHistoryEntry> snapshot =
                cache.getOrDefault(
                        playerId,
                        List.of()
                );

        if (loaded(playerId)) {
            cacheAccess.put(
                    playerId,
                    System.nanoTime()
            );
        }

        return snapshot;
    }

    private boolean loaded(
            UUID playerId
    ) {
        return playerId != null
                && loaded.contains(
                playerId
        );
    }

    public void loadAsync(
            UUID playerId,
            Consumer<Boolean> callback
    ) {
        if (playerId == null) {
            complete(
                    callback,
                    false
            );
            return;
        }

        try {
            initialize();
        } catch (RuntimeException exception) {
            core.getLogger().log(
                    Level.SEVERE,
                    "[AuctionHouse] Transaction history storage is unavailable",
                    exception
            );
            complete(
                    callback,
                    false
            );
            return;
        }

        long taskGeneration =
                generation;

        if (loaded(playerId)) {
            complete(
                    callback,
                    true
            );
            return;
        }

        submit(
                () -> {
                    synchronized (ioLock) {
                        if (taskGeneration
                                != generation) {
                            complete(
                                    callback,
                                    false
                            );
                            return;
                        }

                        if (loaded(playerId)) {
                            complete(
                                    callback,
                                    true
                            );
                            return;
                        }

                        HistoryLoad result =
                                loadInternal(
                                        playerId,
                                        file(playerId)
                                );

                        if (result.valid()
                                && taskGeneration
                                == generation) {
                            cache.put(
                                    playerId,
                                    result.entries()
                            );
                            loaded.add(
                                    playerId
                            );
                            cacheAccess.put(
                                    playerId,
                                    System.nanoTime()
                            );
                            trimCache();
                        }

                        complete(
                                callback,
                                result.valid()
                                        && taskGeneration
                                        == generation
                        );
                    }
                },
                callback
        );
    }

    public void appendAllAsync(
            List<AuctionHistoryEntry> additions,
            BooleanSupplier prerequisite,
            Consumer<Boolean> callback
    ) {
        if (additions == null
                || additions.isEmpty()) {
            complete(
                    callback,
                    true
            );
            return;
        }

        List<AuctionHistoryEntry> immutable =
                List.copyOf(additions);

        try {
            initialize();
        } catch (RuntimeException exception) {
            core.getLogger().log(
                    Level.SEVERE,
                    "[AuctionHouse] Transaction history storage is unavailable",
                    exception
            );
            complete(
                    callback,
                    false
            );
            return;
        }

        long taskGeneration =
                generation;

        submit(
                () -> {
                    synchronized (ioLock) {
                        if (taskGeneration
                                != generation) {
                            complete(
                                    callback,
                                    false
                            );
                            return;
                        }

                        boolean prerequisiteComplete;

                        try {
                            prerequisiteComplete =
                                    prerequisite == null
                                            || prerequisite.getAsBoolean();
                        } catch (RuntimeException exception) {
                            core.getLogger().log(
                                    Level.SEVERE,
                                    "[AuctionHouse] Transaction finalization prerequisite failed",
                                    exception
                            );
                            prerequisiteComplete =
                                    false;
                        }

                        if (!prerequisiteComplete) {
                            complete(
                                    callback,
                                    false
                            );
                            return;
                        }

                        complete(
                                callback,
                                appendAllNow(
                                        immutable,
                                        taskGeneration
                                )
                        );
                    }
                },
                callback
        );
    }

    private boolean appendAllNow(
            List<AuctionHistoryEntry> additions,
            long taskGeneration
    ) {
        Map<UUID, List<AuctionHistoryEntry>> byPlayer =
                new LinkedHashMap<>();

        for (AuctionHistoryEntry entry : additions) {
            if (!tradeEntry(entry)) {
                return false;
            }

            byPlayer.computeIfAbsent(
                            entry.playerId(),
                            ignored ->
                                    new ArrayList<>()
                    )
                    .add(entry);
        }

        for (Map.Entry<UUID, List<AuctionHistoryEntry>> group
                : byPlayer.entrySet()) {
            UUID playerId =
                    group.getKey();
            List<AuctionHistoryEntry> current;

            if (loaded(playerId)) {
                current =
                        new ArrayList<>(
                                snapshot(playerId)
                        );
            } else {
                HistoryLoad load =
                        loadInternal(
                                playerId,
                                file(playerId)
                        );

                if (!load.valid()) {
                    return false;
                }

                current =
                        new ArrayList<>(
                                load.entries()
                        );
            }

            boolean changed = false;

            for (AuctionHistoryEntry addition
                    : group.getValue()) {
                if (contains(
                        current,
                        addition
                )) {
                    continue;
                }

                current.add(addition);
                changed = true;
            }

            current.sort(
                    Comparator
                            .comparingLong(
                                    AuctionHistoryEntry::timestamp
                            )
                            .reversed()
                            .thenComparing(
                                    historyEntry ->
                                            historyEntry
                                                    .transactionId()
                                                    .toString()
                            )
                            .thenComparing(
                                    historyEntry ->
                                            historyEntry
                                                    .type()
                                                    .name()
                            )
            );

            if (current.size()
                    > maximumEntries) {
                current =
                        new ArrayList<>(
                                current.subList(
                                        0,
                                        maximumEntries
                                )
                        );
                changed = true;
            }

            List<AuctionHistoryEntry> immutable =
                    List.copyOf(current);

            if (changed
                    && !save(
                    file(playerId),
                    immutable
            )) {
                return false;
            }

            if (taskGeneration
                    != generation) {
                return false;
            }

            cache.put(
                    playerId,
                    immutable
            );
            loaded.add(
                    playerId
            );
            cacheAccess.put(
                    playerId,
                    System.nanoTime()
            );
            trimCache();
        }

        return true;
    }

    private void trimCache() {
        int excess =
                cache.size()
                        - MAX_CACHED_PLAYERS;

        if (excess <= 0) {
            return;
        }

        List<Map.Entry<UUID, Long>> ordered =
                new ArrayList<>(
                        cacheAccess.entrySet()
                );
        ordered.sort(
                Map.Entry.comparingByValue()
        );

        for (int index = 0;
             index < excess
                     && index < ordered.size();
             index++) {
            UUID playerId =
                    ordered.get(index)
                            .getKey();
            cache.remove(
                    playerId
            );
            cacheAccess.remove(
                    playerId
            );
            loaded.remove(
                    playerId
            );
        }
    }

    private boolean tradeEntry(
            AuctionHistoryEntry entry
    ) {
        return entry != null
                && (
                entry.type()
                        == AuctionHistoryEntry.Type.PURCHASED
                        || entry.type()
                        == AuctionHistoryEntry.Type.SOLD
        );
    }

    private boolean contains(
            List<AuctionHistoryEntry> entries,
            AuctionHistoryEntry candidate
    ) {
        for (AuctionHistoryEntry existing : entries) {
            if (existing.transactionId()
                    .equals(
                            candidate.transactionId()
                    )
                    && existing.type()
                    == candidate.type()) {
                return true;
            }
        }

        return false;
    }

    private HistoryLoad loadInternal(
            UUID playerId,
            File file
    ) {
        if (!file.isFile()) {
            return new HistoryLoad(
                    List.of(),
                    true
            );
        }

        if (historyFileTooLarge(file)) {
            core.getLogger().severe(
                    "[AuctionHouse] Refused oversized transaction history file "
                            + file.getName()
            );
            return new HistoryLoad(
                    List.of(),
                    false
            );
        }

        try {
            YamlConfiguration yaml =
                    new YamlConfiguration();
            yaml.load(file);

            ConfigurationSection section =
                    yaml.getConfigurationSection(
                            "entries"
                    );

            if (section == null) {
                return new HistoryLoad(
                        List.of(),
                        true
                );
            }

            List<AuctionHistoryEntry> entries =
                    new ArrayList<>();

            for (String rawId
                    : section.getKeys(false)) {
                AuctionHistoryEntry entry =
                        readEntry(
                                playerId,
                                section,
                                rawId
                        );

                if (tradeEntry(entry)) {
                    entries.add(entry);
                }
            }

            entries.sort(
                    Comparator
                            .comparingLong(
                                    AuctionHistoryEntry::timestamp
                            )
                            .reversed()
                            .thenComparing(
                                    entry ->
                                            entry.transactionId()
                                                    .toString()
                            )
                            .thenComparing(
                                    entry ->
                                            entry.type()
                                                    .name()
                            )
            );

            if (entries.size()
                    > maximumEntries) {
                entries =
                        new ArrayList<>(
                                entries.subList(
                                        0,
                                        maximumEntries
                                )
                        );
            }

            return new HistoryLoad(
                    List.copyOf(entries),
                    true
            );
        } catch (
                IOException
                | InvalidConfigurationException
                | RuntimeException exception
        ) {
            core.getLogger().log(
                    Level.WARNING,
                    "[AuctionHouse] Could not safely read transaction history for "
                            + playerId
                            + "; the ledger was left untouched",
                    exception
            );
            return new HistoryLoad(
                    List.of(),
                    false
            );
        }
    }

    private AuctionHistoryEntry readEntry(
            UUID playerId,
            ConfigurationSection section,
            String rawId
    ) {
        UUID transactionId =
                UUID.fromString(rawId);
        String base =
                rawId + ".";

        AuctionHistoryEntry.Type type =
                AuctionHistoryEntry.Type
                        .valueOf(
                                section
                                        .getString(
                                                base + "type",
                                                ""
                                        )
                                        .trim()
                                        .toUpperCase(
                                                Locale.ROOT
                                        )
                        );
        UUID counterpartId =
                optionalUuid(
                        section.getString(
                                base + "counterpart"
                        )
                );
        Material material =
                Material.valueOf(
                        section.getString(
                                        base + "material",
                                        ""
                                )
                                .trim()
                                .toUpperCase(
                                        Locale.ROOT
                                )
                );

        if (!material.isItem()) {
            throw new IllegalStateException(
                    "History material is not an item: "
                            + material
            );
        }

        return new AuctionHistoryEntry(
                transactionId,
                type,
                playerId,
                counterpartId,
                material,
                section.getString(
                        base + "item-name",
                        "Item"
                ),
                Math.max(
                        1,
                        section.getInt(
                                base + "amount",
                                1
                        )
                ),
                Math.max(
                        0L,
                        section.getLong(
                                base + "price-cents",
                                0L
                        )
                ),
                Math.max(
                        0L,
                        section.getLong(
                                base + "timestamp",
                                0L
                        )
                )
        );
    }

    private boolean save(
            File file,
            List<AuctionHistoryEntry> entries
    ) {
        YamlConfiguration yaml =
                new YamlConfiguration();

        for (AuctionHistoryEntry entry : entries) {
            String base =
                    "entries."
                            + entry.transactionId()
                            + ".";

            yaml.set(
                    base + "type",
                    entry.type().name()
            );
            yaml.set(
                    base + "counterpart",
                    entry.counterpartId() == null
                            ? null
                            : entry.counterpartId()
                            .toString()
            );
            yaml.set(
                    base + "material",
                    entry.material().name()
            );
            yaml.set(
                    base + "item-name",
                    entry.itemName()
            );
            yaml.set(
                    base + "amount",
                    entry.amount()
            );
            yaml.set(
                    base + "price-cents",
                    entry.priceCents()
            );
            yaml.set(
                    base + "timestamp",
                    entry.timestamp()
            );
        }

        return atomicSave(
                yaml,
                file
        );
    }

    private boolean atomicSave(
            YamlConfiguration yaml,
            File target
    ) {
        ensureDirectory(
                target.getParentFile()
        );

        Path targetPath =
                target.toPath();
        Path temporary =
                targetPath.resolveSibling(
                        target.getName()
                                + ".tmp"
                );

        try {
            writeAndForce(
                    temporary,
                    yaml.saveToString()
            );

            try {
                Files.move(
                        temporary,
                        targetPath,
                        StandardCopyOption
                                .REPLACE_EXISTING,
                        StandardCopyOption
                                .ATOMIC_MOVE
                );
            } catch (
                    AtomicMoveNotSupportedException ignored
            ) {
                Files.move(
                        temporary,
                        targetPath,
                        StandardCopyOption
                                .REPLACE_EXISTING
                );
            }

            forceDirectory(
                    targetPath.getParent()
            );
            return true;
        } catch (IOException exception) {
            core.getLogger().log(
                    Level.WARNING,
                    "[AuctionHouse] Could not save transaction history "
                            + target.getName(),
                    exception
            );

            try {
                Files.deleteIfExists(
                        temporary
                );
            } catch (IOException ignored) {
            }

            return false;
        }
    }

    private void submit(
            Runnable task,
            Consumer<Boolean> callback
    ) {
        ThreadPoolExecutor current =
                worker;

        if (current == null
                || current.isShutdown()) {
            complete(
                    callback,
                    false
            );
            return;
        }

        try {
            current.execute(task);
        } catch (RejectedExecutionException exception) {
            core.getLogger().warning(
                    "[AuctionHouse] Transaction history writer queue is full"
            );
            complete(
                    callback,
                    false
            );
        }
    }

    private static void complete(
            Consumer<Boolean> callback,
            boolean success
    ) {
        if (callback != null) {
            callback.accept(success);
        }
    }

    private static void writeAndForce(
            Path path,
            String value
    ) throws IOException {
        byte[] bytes =
                value.getBytes(
                        StandardCharsets.UTF_8
                );

        try (FileChannel channel =
                     FileChannel.open(
                             path,
                             StandardOpenOption.CREATE,
                             StandardOpenOption.TRUNCATE_EXISTING,
                             StandardOpenOption.WRITE
                     )) {
            ByteBuffer buffer =
                    ByteBuffer.wrap(bytes);

            while (buffer.hasRemaining()) {
                int written =
                        channel.write(buffer);

                if (written <= 0) {
                    throw new IOException(
                            "Could not make progress writing "
                                    + path.getFileName()
                    );
                }
            }

            channel.force(true);
        }
    }

    private void forceDirectory(
            Path directory
    ) {
        if (directory == null
                || windows()) {
            return;
        }

        try (FileChannel channel =
                     FileChannel.open(
                             directory,
                             StandardOpenOption.READ
                     )) {
            channel.force(true);
        } catch (
                IOException
                | UnsupportedOperationException
                | SecurityException exception
        ) {
            if (directorySyncWarningLogged) {
                return;
            }

            directorySyncWarningLogged = true;
            core.getLogger().log(
                    Level.WARNING,
                    "[AuctionHouse] Transaction history directory sync is unavailable; "
                            + "file contents are forced but rename durability depends on the filesystem",
                    exception
            );
        }
    }

    private boolean historyFileTooLarge(
            File file
    ) {
        try {
            return Files.size(
                    file.toPath()
            ) > MAX_HISTORY_FILE_BYTES;
        } catch (IOException exception) {
            core.getLogger().log(
                    Level.WARNING,
                    "[AuctionHouse] Could not inspect transaction history file size "
                            + file.getName(),
                    exception
            );
            return true;
        }
    }

    private UUID optionalUuid(
            String value
    ) {
        if (value == null
                || value.isBlank()) {
            return null;
        }

        return UUID.fromString(
                value.trim()
        );
    }

    private File file(
            UUID playerId
    ) {
        return new File(
                folder,
                playerId + EXTENSION
        );
    }

    private void ensureDirectory(
            File directory
    ) {
        if (directory.exists()) {
            if (!directory.isDirectory()) {
                throw new IllegalStateException(
                        directory
                                + " is not a directory"
                );
            }
            return;
        }

        if (!directory.mkdirs()
                && !directory.isDirectory()) {
            throw new IllegalStateException(
                    "Could not create "
                            + directory
            );
        }
    }

    private record HistoryLoad(
            List<AuctionHistoryEntry> entries,
            boolean valid
    ) {
        private HistoryLoad {
            entries =
                    entries == null
                            ? List.of()
                            : List.copyOf(entries);
        }
    }

    private static boolean windows() {
        return System.getProperty(
                        "os.name",
                        ""
                )
                .toLowerCase(
                        Locale.ROOT
                )
                .contains("win");
    }
}
