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
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;

public final class AuctionHistoryStorage {

    private static final String EXTENSION = ".yml";
    private static final int DEFAULT_MAX_ENTRIES = 100;
    private static final int MIN_MAX_ENTRIES = 25;
    private static final int MAX_MAX_ENTRIES = 500;
    private static final long MAX_HISTORY_FILE_BYTES = 1_048_576L;

    private final Core core;
    private final File folder;

    private int maximumEntries = DEFAULT_MAX_ENTRIES;
    private boolean initialized;
    private boolean directorySyncWarningLogged;

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

    public void configureMaximumEntries(
            int maximumEntries
    ) {
        this.maximumEntries =
                Math.clamp(
                        maximumEntries,
                        MIN_MAX_ENTRIES,
                        MAX_MAX_ENTRIES
                );
    }

    public void initialize() {
        if (initialized) {
            return;
        }

        ensureDirectory(folder);
        initialized = true;
    }

    public synchronized boolean append(
            AuctionHistoryEntry entry
    ) {
        if (entry == null) {
            return false;
        }

        initialize();

        File file =
                file(
                        entry.playerId()
                );

        HistoryLoad loaded =
                loadInternal(
                        entry.playerId(),
                        file
                );

        if (!loaded.valid()) {
            return false;
        }

        List<AuctionHistoryEntry> entries =
                new ArrayList<>(
                        loaded.entries()
                );

        for (AuctionHistoryEntry existing : entries) {
            if (existing.transactionId()
                    .equals(
                            entry.transactionId()
                    )
                    && existing.type()
                    == entry.type()) {
                return true;
            }
        }

        entries.add(entry);
        entries.sort(
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

        return save(
                file,
                entries
        );
    }

    public synchronized List<AuctionHistoryEntry> load(
            UUID playerId
    ) {
        if (playerId == null) {
            return List.of();
        }

        initialize();

        return loadInternal(
                playerId,
                file(playerId)
        ).entries();
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

                if (entry != null) {
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
            );

            if (entries.size()
                    > maximumEntries) {
                return new HistoryLoad(
                        List.copyOf(
                                entries.subList(
                                        0,
                                        maximumEntries
                                )
                        ),
                        true
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
                    "[AuctionHouse] Could not read transaction history for "
                            + playerId,
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
        try {
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
                    Material.matchMaterial(
                            section.getString(
                                    base + "material",
                                    ""
                            )
                    );

            if (material == null
                    || !material.isItem()) {
                return null;
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
        } catch (RuntimeException exception) {
            core.getLogger().log(
                    Level.WARNING,
                    "[AuctionHouse] Skipped malformed transaction history entry "
                            + rawId
                            + " for "
                            + playerId,
                    exception
            );
            return null;
        }
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
