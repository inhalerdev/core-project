package net.mineacle.core.sell.storage;

import net.mineacle.core.Core;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class YamlSellMarketRepository
        implements SellMarketRepository {

    private final Core core;
    private final File file;
    private YamlConfiguration configuration;

    public YamlSellMarketRepository(Core core) {
        this.core = core;
        this.file = new File(
                core.getDataFolder(),
                "sell-market.yml"
        );
    }

    @Override
    public synchronized void initialize() {
        configuration = YamlConfiguration.loadConfiguration(file);
    }

    @Override
    public synchronized Snapshot load(long bucketsSince) {
        ensureLoaded();

        List<MarketStateData> states = new ArrayList<>();
        List<BucketData> buckets = new ArrayList<>();
        List<HistoryData> history = new ArrayList<>();

        ConfigurationSection stateSection =
                configuration.getConfigurationSection("market");

        if (stateSection != null) {
            for (String material : stateSection.getKeys(false)) {
                String path = "market." + material;

                states.add(new MarketStateData(
                        material.toUpperCase(Locale.ROOT),
                        configuration.getDouble(
                                path + ".market-multiplier",
                                1.0D
                        ),
                        configuration.getDouble(
                                path + ".featured-multiplier",
                                1.0D
                        ),
                        configuration.getLong(
                                path + ".featured-until",
                                0L
                        ),
                        configuration.getLong(
                                path + ".last-repriced-at",
                                0L
                        ),
                        configuration.getLong(
                                path + ".target-units-per-day",
                                1L
                        )
                ));
            }
        }

        ConfigurationSection bucketMaterials =
                configuration.getConfigurationSection("buckets");

        if (bucketMaterials != null) {
            for (String material
                    : bucketMaterials.getKeys(false)) {
                ConfigurationSection materialSection =
                        configuration.getConfigurationSection(
                                "buckets." + material
                        );

                if (materialSection == null) {
                    continue;
                }

                for (String rawStart
                        : materialSection.getKeys(false)) {
                    try {
                        long start = Long.parseLong(rawStart);

                        if (start < bucketsSince) {
                            continue;
                        }

                        String path = "buckets."
                                + material
                                + "."
                                + rawStart;

                        buckets.add(new BucketData(
                                material.toUpperCase(Locale.ROOT),
                                start,
                                configuration.getLong(
                                        path + ".units-sold",
                                        0L
                                ),
                                configuration.getLong(
                                        path + ".payout-cents",
                                        0L
                                )
                        ));
                    } catch (NumberFormatException ignored) {
                        core.getLogger().warning(
                                "Skipped invalid Sell market bucket "
                                        + material
                                        + "/"
                                        + rawStart
                        );
                    }
                }
            }
        }

        ConfigurationSection players =
                configuration.getConfigurationSection("history");

        if (players != null) {
            for (String rawPlayer : players.getKeys(false)) {
                UUID playerId;

                try {
                    playerId = UUID.fromString(rawPlayer);
                } catch (IllegalArgumentException exception) {
                    core.getLogger().warning(
                            "Skipped invalid Sell history player "
                                    + rawPlayer
                    );
                    continue;
                }

                ConfigurationSection materials =
                        configuration.getConfigurationSection(
                                "history." + rawPlayer
                        );

                if (materials == null) {
                    continue;
                }

                for (String material : materials.getKeys(false)) {
                    String path = "history."
                            + rawPlayer
                            + "."
                            + material;

                    history.add(new HistoryData(
                            playerId,
                            material.toUpperCase(Locale.ROOT),
                            configuration.getLong(
                                    path + ".amount",
                                    0L
                            ),
                            configuration.getLong(
                                    path + ".total-cents",
                                    0L
                            ),
                            configuration.getLong(
                                    path + ".last-sold-at",
                                    0L
                            )
                    ));
                }
            }
        }

        return new Snapshot(
                List.copyOf(states),
                List.copyOf(buckets),
                List.copyOf(history)
        );
    }

    @Override
    public synchronized void save(SaveBatch batch)
            throws IOException {
        ensureLoaded();

        for (MarketStateData state : batch.states()) {
            String path = "market." + state.material();

            configuration.set(
                    path + ".market-multiplier",
                    state.marketMultiplier()
            );
            configuration.set(
                    path + ".featured-multiplier",
                    state.featuredMultiplier()
            );
            configuration.set(
                    path + ".featured-until",
                    state.featuredUntil()
            );
            configuration.set(
                    path + ".last-repriced-at",
                    state.lastRepricedAt()
            );
            configuration.set(
                    path + ".target-units-per-day",
                    state.targetUnitsPerDay()
            );
        }

        for (BucketData bucket : batch.buckets()) {
            String path = "buckets."
                    + bucket.material()
                    + "."
                    + bucket.bucketStart();

            configuration.set(
                    path + ".units-sold",
                    bucket.unitsSold()
            );
            configuration.set(
                    path + ".payout-cents",
                    bucket.payoutCents()
            );
        }

        for (HistoryData entry : batch.history()) {
            String path = "history."
                    + entry.playerId()
                    + "."
                    + entry.material();

            configuration.set(
                    path + ".amount",
                    entry.amount()
            );
            configuration.set(
                    path + ".total-cents",
                    entry.totalCents()
            );
            configuration.set(
                    path + ".last-sold-at",
                    entry.lastSoldAt()
            );
        }

        pruneBuckets(batch.pruneBucketsBefore());
        atomicSave();
    }

    @Override
    public String name() {
        return "YAML fallback";
    }

    private void pruneBuckets(long before) {
        if (before <= 0L) {
            return;
        }

        ConfigurationSection materials =
                configuration.getConfigurationSection("buckets");

        if (materials == null) {
            return;
        }

        for (String material : materials.getKeys(false)) {
            ConfigurationSection buckets =
                    configuration.getConfigurationSection(
                            "buckets." + material
                    );

            if (buckets == null) {
                continue;
            }

            for (String rawStart
                    : new ArrayList<>(buckets.getKeys(false))) {
                try {
                    if (Long.parseLong(rawStart) < before) {
                        configuration.set(
                                "buckets."
                                        + material
                                        + "."
                                        + rawStart,
                                null
                        );
                    }
                } catch (NumberFormatException exception) {
                    configuration.set(
                            "buckets."
                                    + material
                                    + "."
                                    + rawStart,
                            null
                    );
                }
            }
        }
    }

    private void atomicSave() throws IOException {
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

    private void ensureLoaded() {
        if (configuration == null) {
            initialize();
        }
    }
}
