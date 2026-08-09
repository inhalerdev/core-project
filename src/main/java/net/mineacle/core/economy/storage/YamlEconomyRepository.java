package net.mineacle.core.economy.storage;

import net.mineacle.core.Core;
import net.mineacle.core.economy.service.OfflinePaymentNotice;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class YamlEconomyRepository {

    public record Snapshot(
            Map<UUID, Long> balances,
            Map<UUID, OfflinePaymentNotice> offlinePayments
    ) {
    }

    private final Core core;
    private final File file;

    public YamlEconomyRepository(Core core) {
        this.core = core;
        this.file = new File(core.getDataFolder(), "economy.yml");
    }

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

        return new Snapshot(
                Map.copyOf(balances),
                copyNotices(notices)
        );
    }

    public synchronized void save(
            Map<UUID, Long> balances,
            Map<UUID, OfflinePaymentNotice> offlinePayments
    ) throws IOException {
        FileConfiguration configuration =
                core.getEconomyConfig();

        configuration.set("balances", null);
        configuration.set("offline-payments", null);

        List<Map.Entry<UUID, Long>> balanceEntries =
                new ArrayList<>(balances.entrySet());
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
                new ArrayList<>(offlinePayments.entrySet());
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

        atomicSave(configuration);
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

        File temporary = new File(
                folder,
                file.getName() + ".tmp"
        );

        configuration.save(temporary);

        try {
            Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
            );
        } finally {
            Files.deleteIfExists(temporary.toPath());
        }
    }

    private Map<UUID, OfflinePaymentNotice> copyNotices(
            Map<UUID, OfflinePaymentNotice> source
    ) {
        Map<UUID, OfflinePaymentNotice> copy = new HashMap<>();

        for (Map.Entry<UUID, OfflinePaymentNotice> entry
                : source.entrySet()) {
            copy.put(entry.getKey(), entry.getValue().copy());
        }

        return Map.copyOf(copy);
    }
}
