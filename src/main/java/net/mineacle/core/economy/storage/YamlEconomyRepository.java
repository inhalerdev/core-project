package net.mineacle.core.economy.storage;

import net.mineacle.core.Core;
import net.mineacle.core.economy.service.OfflinePaymentNotice;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class YamlEconomyRepository {

    public record Snapshot(
            Map<UUID, Long> balances,
            Map<UUID, OfflinePaymentNotice> offlinePayments,
            UUID lastMarketTransactionId
    ) {
    }

    private final Core core;
    private final File file;
    private boolean directorySyncWarningLogged;

    public YamlEconomyRepository(Core core) {
        this.core = core;
        this.file = new File(core.getDataFolder(), "economy.yml");
    }

    /**
     * Startup loading remains synchronous because module initialization needs
     * a complete economy state before Vault is registered.
     */
    public synchronized Snapshot load() {
        Map<UUID, Long> balances = new HashMap<>();
        Map<UUID, OfflinePaymentNotice> notices = new HashMap<>();
        FileConfiguration configuration =
                core.getEconomyConfig();

        ConfigurationSection balanceSection =
                configuration.getConfigurationSection("balances");

        if (balanceSection != null) {
            for (String rawId : balanceSection.getKeys(false)) {
                try {
                    UUID playerId = UUID.fromString(rawId);
                    long cents = configuration.getLong(
                            "balances." + rawId,
                            0L
                    );

                    if (cents >= 0L) {
                        balances.put(playerId, cents);
                    }
                } catch (IllegalArgumentException ignored) {
                    core.getLogger().warning(
                            "Skipped invalid economy account " + rawId
                    );
                }
            }
        }

        ConfigurationSection offlineSection =
                configuration.getConfigurationSection(
                        "offline-payments"
                );

        if (offlineSection != null) {
            for (String rawId : offlineSection.getKeys(false)) {
                try {
                    UUID playerId = UUID.fromString(rawId);
                    String path = "offline-payments." + rawId;
                    long totalCents = configuration.getLong(
                            path + ".total-cents",
                            0L
                    );
                    List<String> senders =
                            configuration.getStringList(
                                    path + ".senders"
                            );

                    if (totalCents > 0L) {
                        notices.put(
                                playerId,
                                new OfflinePaymentNotice(
                                        totalCents,
                                        senders
                                )
                        );
                    }
                } catch (IllegalArgumentException ignored) {
                    core.getLogger().warning(
                            "Skipped invalid offline payment " + rawId
                    );
                }
            }
        }

        UUID lastMarketTransactionId = null;
        String rawMarketTransactionId = configuration.getString(
                "market.last-transaction-id",
                ""
        );

        if (!rawMarketTransactionId.isBlank()) {
            try {
                lastMarketTransactionId =
                        UUID.fromString(rawMarketTransactionId.trim());
            } catch (IllegalArgumentException exception) {
                core.getLogger().warning(
                        "Skipped invalid economy market transaction checkpoint"
                );
            }
        }

        return new Snapshot(
                Map.copyOf(balances),
                copyNotices(notices),
                lastMarketTransactionId
        );
    }

    /**
     * Serializes an immutable economy snapshot into a private
     * YamlConfiguration. The live Bukkit configuration object is never
     * mutated by the asynchronous persistence thread.
     */
    public synchronized void save(
            Snapshot snapshot
    ) throws IOException {
        if (snapshot == null) {
            return;
        }

        YamlConfiguration configuration =
                new YamlConfiguration();

        writeSnapshot(configuration, snapshot);
        atomicSave(configuration);
    }

    private void writeSnapshot(
            FileConfiguration configuration,
            Snapshot snapshot
    ) {
        List<Map.Entry<UUID, Long>> balanceEntries =
                new ArrayList<>(
                        snapshot.balances().entrySet()
                );
        balanceEntries.sort(
                Comparator.comparing(
                        entry -> entry.getKey().toString()
                )
        );

        for (Map.Entry<UUID, Long> entry : balanceEntries) {
            Long cents = entry.getValue();

            if (cents == null || cents < 0L) {
                continue;
            }

            configuration.set(
                    "balances." + entry.getKey(),
                    cents
            );
        }

        List<Map.Entry<UUID, OfflinePaymentNotice>> notices =
                new ArrayList<>(
                        snapshot.offlinePayments().entrySet()
                );
        notices.sort(
                Comparator.comparing(
                        entry -> entry.getKey().toString()
                )
        );

        for (Map.Entry<UUID, OfflinePaymentNotice> entry
                : notices) {
            OfflinePaymentNotice notice = entry.getValue();

            if (notice == null || notice.totalCents() <= 0L) {
                continue;
            }

            String path = "offline-payments." + entry.getKey();

            configuration.set(
                    path + ".total-cents",
                    notice.totalCents()
            );

            List<String> senders = new ArrayList<>(
                    notice.senders()
            );
            senders.sort(String.CASE_INSENSITIVE_ORDER);

            configuration.set(
                    path + ".senders",
                    senders
            );
        }

        UUID marketTransactionId =
                snapshot.lastMarketTransactionId();

        if (marketTransactionId != null) {
            configuration.set(
                    "market.last-transaction-id",
                    marketTransactionId.toString()
            );
        }
    }

    private void atomicSave(
            FileConfiguration configuration
    ) throws IOException {
        File folder = core.getDataFolder();

        if (!folder.exists()
                && !folder.mkdirs()
                && !folder.exists()) {
            throw new IOException(
                    "Could not create MineacleCore data folder"
            );
        }

        Path target =
                file.toPath();
        Path temporary =
                new File(
                        folder,
                        file.getName() + ".tmp"
                ).toPath();

        try {
            writeAndForce(
                    temporary,
                    configuration.saveToString()
            );

            try {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.REPLACE_EXISTING
                );
            }

            forceDirectory(
                    target.getParent()
            );
        } finally {
            Files.deleteIfExists(
                    temporary
            );
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
                    ByteBuffer.wrap(
                            bytes
                    );

            while (buffer.hasRemaining()) {
                int written =
                        channel.write(
                                buffer
                        );

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
            core.getLogger().warning(
                    "Economy directory sync is unavailable; file contents are "
                            + "forced but rename durability depends on the filesystem: "
                            + exception.getClass().getSimpleName()
            );
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

    private static Map<UUID, OfflinePaymentNotice> copyNotices(
            Map<UUID, OfflinePaymentNotice> source
    ) {
        Map<UUID, OfflinePaymentNotice> copy = new HashMap<>();

        if (source == null) {
            return Map.of();
        }

        for (Map.Entry<UUID, OfflinePaymentNotice> entry
                : source.entrySet()) {
            OfflinePaymentNotice notice = entry.getValue();

            if (notice != null) {
                copy.put(
                        entry.getKey(),
                        notice.copy()
                );
            }
        }

        return Map.copyOf(copy);
    }
}
