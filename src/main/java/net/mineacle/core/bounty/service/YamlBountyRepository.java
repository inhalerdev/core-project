package net.mineacle.core.bounty;

import net.mineacle.core.Core;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class YamlBountyRepository implements BountyRepository {

    private final Core core;
    private final File file;
    private final Map<UUID, BountyRecord> records =
            new LinkedHashMap<>();

    public YamlBountyRepository(Core core) {
        this.core = core;
        this.file = new File(core.getDataFolder(), "bounties.yml");
    }

    @Override
    public synchronized void initialize() throws IOException {
        ensureStorage();
        records.clear();

        YamlConfiguration configuration =
                YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section =
                configuration.getConfigurationSection("bounties");

        if (section == null) {
            return;
        }

        for (String rawId : section.getKeys(false)) {
            try {
                UUID targetId = UUID.fromString(rawId);
                String path = "bounties." + rawId;
                long amount = configuration.getLong(
                        path + ".amount-cents",
                        0L
                );

                if (amount <= 0L) {
                    continue;
                }

                String username = configuration.getString(
                        path + ".target-username"
                );

                if (username == null || username.isBlank()) {
                    username = configuration.getString(
                            path + ".target-name",
                            targetId.toString()
                    );
                }

                long lastUpdated = configuration.getLong(
                        path + ".last-updated",
                        0L
                );

                records.put(
                        targetId,
                        new BountyRecord(
                                targetId,
                                username,
                                amount,
                                lastUpdated
                        )
                );
            } catch (IllegalArgumentException exception) {
                core.getLogger().warning(
                        "Skipped invalid bounty entry " + rawId
                );
            }
        }
    }

    @Override
    public synchronized Optional<BountyRecord> find(UUID targetId) {
        return Optional.ofNullable(records.get(targetId));
    }

    @Override
    public synchronized List<BountyRecord> listAll() {
        return List.copyOf(new ArrayList<>(records.values()));
    }

    @Override
    public synchronized void save(BountyRecord record)
            throws IOException {
        BountyRecord previous = records.put(
                record.targetId(),
                record
        );

        try {
            persist();
        } catch (IOException exception) {
            if (previous == null) {
                records.remove(record.targetId());
            } else {
                records.put(record.targetId(), previous);
            }

            throw exception;
        }
    }

    @Override
    public synchronized boolean delete(UUID targetId)
            throws IOException {
        BountyRecord removed = records.remove(targetId);

        if (removed == null) {
            return false;
        }

        try {
            persist();
            return true;
        } catch (IOException exception) {
            records.put(targetId, removed);
            throw exception;
        }
    }

    @Override
    public synchronized void flush() throws IOException {
        persist();
    }

    private void ensureStorage() throws IOException {
        File folder = core.getDataFolder();

        if (!folder.exists()
                && !folder.mkdirs()
                && !folder.exists()) {
            throw new IOException(
                    "Could not create MineacleCore data folder"
            );
        }

        if (!file.exists()
                && !file.createNewFile()
                && !file.exists()) {
            throw new IOException(
                    "Could not create bounties.yml"
            );
        }
    }

    private void persist() throws IOException {
        ensureStorage();

        YamlConfiguration configuration = new YamlConfiguration();

        for (BountyRecord record : records.values()) {
            String path = "bounties." + record.targetId();

            configuration.set(
                    path + ".target-username",
                    record.targetUsername()
            );

            /*
             * Keep the legacy key for downgrade compatibility. It now stores
             * the internal username rather than a public nickname.
             */
            configuration.set(
                    path + ".target-name",
                    record.targetUsername()
            );
            configuration.set(
                    path + ".amount-cents",
                    record.amountCents()
            );
            configuration.set(
                    path + ".last-updated",
                    record.lastUpdated()
            );
        }

        File temporary = new File(
                file.getParentFile(),
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
}
