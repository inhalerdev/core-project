package net.mineacle.core.sell.storage;

import net.mineacle.core.Core;
import net.mineacle.core.sell.model.SellCatalogEntry;
import net.mineacle.core.sell.model.SellCatalogSnapshot;
import net.mineacle.core.sell.service.SellCatalogV10Compiler;
import net.mineacle.core.sell.service.SellCatalogV10Compiler.Compilation;
import net.mineacle.core.sell.service.SellCatalogV10Compiler.Summary;
import net.mineacle.core.sell.service.SellPricingPolicy;
import net.mineacle.core.sell.service.SellService;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Revision-10 Sell catalog lifecycle.
 *
 * <p>The catalog is compiled from the post-enable recipe registry on the
 * server thread and atomically activated as one immutable snapshot. SQL is an
 * asynchronous audit mirror only; database availability never decides whether
 * players can use a catalog that already passed runtime compilation.</p>
 */
@SuppressWarnings("SqlNoDataSourceInspection")
public final class SellCatalogV10BootstrapService {

    private static final String DEFAULT_PREFIX =
            "mineacle_sell";

    private final Core core;
    private final SellService sellService;
    private final AtomicBoolean started =
            new AtomicBoolean();

    public SellCatalogV10BootstrapService(
            Core core,
            SellService sellService
    ) {
        this.core = core;
        this.sellService = sellService;
    }

    public void start() {
        if (!started.compareAndSet(
                false,
                true
        )) {
            return;
        }

        /*
         * Defer one server tick so plugins that register recipes during enable
         * have completed before v10 snapshots the recipe registry.
         */
        core.getServer()
                .getScheduler()
                .runTask(
                        core,
                        this::buildAndActivate
                );
    }

    private void buildAndActivate() {
        if (!core.isEnabled()) {
            return;
        }

        try {
            FileConfiguration sellConfig =
                    YamlConfiguration
                            .loadConfiguration(
                                    new File(
                                            core.getDataFolder(),
                                            "sell.yml"
                                    )
                            );

            SellCatalogV10Compiler compiler =
                    new SellCatalogV10Compiler(
                            sellService
                    );
            Compilation compilation =
                    compiler.compile(
                            sellConfig
                    );
            SellCatalogSnapshot snapshot =
                    compilation.snapshot();

            if (!compilation.ready()) {
                core.getLogger().severe(
                        "Sell catalog v10 candidate rejected before activation — "
                                + String.join(
                                "; ",
                                compilation.failures()
                        )
                );
                return;
            }

            if (!sellService
                    .activateCatalogSnapshot(
                            snapshot
                    )) {
                core.getLogger().severe(
                        "Sell catalog v10 activation rejected — previous pricing authority remains active"
                );
                return;
            }

            Summary summary =
                    compilation.summary();

            core.getLogger().info(
                    "Sell catalog v10 READY — "
                            + summary.sellable()
                            + "/"
                            + summary.total()
                            + " server-sellable, "
                            + summary.references()
                            + " direct references, "
                            + summary.commodity()
                            + " commodity-normalized, "
                            + summary.derived()
                            + " forward-derived, "
                            + summary.fallback()
                            + " fallback-derived, "
                            + summary.variants()
                            + " variant families, "
                            + summary.cappedReferences()
                            + " references recipe-capped, "
                            + summary.oneCent()
                            + " one-cent values, "
                            + summary.unsafe()
                            + " unsafe, "
                            + summary.commodityGroups()
                            + " commodity groups, "
                            + summary.cyclicRecipes()
                            + " cyclic recipe edge(s) at strict no-profit"
            );

            core.getLogger().info(
                    "Sell catalog v10 live market movement is frozen at 1.0x — shadow learning remains non-authoritative"
            );

            core.getServer()
                    .getScheduler()
                    .runTaskAsynchronously(
                            core,
                            () -> mirrorSafely(
                                    sellConfig,
                                    snapshot,
                                    summary
                            )
                    );
        } catch (RuntimeException exception) {
            core.getLogger().log(
                    Level.SEVERE,
                    "Sell catalog v10 build failed — previous pricing authority remains active",
                    exception
            );
        }
    }

    private void mirrorSafely(
            FileConfiguration sellConfig,
            SellCatalogSnapshot snapshot,
            Summary summary
    ) {
        try {
            mirror(
                    sellConfig,
                    snapshot,
                    summary
            );
        } catch (Exception exception) {
            core.getLogger().log(
                    Level.WARNING,
                    "Sell catalog v10 SQL audit mirror unavailable — runtime v10 authority remains active",
                    exception
            );
        }
    }

    private void mirror(
            FileConfiguration sellConfig,
            SellCatalogSnapshot snapshot,
            Summary summary
    ) throws Exception {
        String storage =
                nonBlank(
                        sellConfig.getString(
                                "market.storage",
                                "mysql"
                        ),
                        "mysql"
                );

        if (!storage.equalsIgnoreCase(
                "mysql"
        )
                && !storage.equalsIgnoreCase(
                "mariadb"
        )) {
            core.getLogger().warning(
                    "Sell catalog v10 SQL audit mirror skipped — MySQL/MariaDB is not configured"
            );
            return;
        }

        File databaseFile =
                new File(
                        core.getDataFolder(),
                        nonBlank(
                                sellConfig.getString(
                                        "market.database-config-file",
                                        "webprofiles.yml"
                                ),
                                "webprofiles.yml"
                        )
                );
        FileConfiguration database =
                YamlConfiguration
                        .loadConfiguration(
                                databaseFile
                        );

        String driverClass =
                nonBlank(
                        database.getString(
                                "database.driver-class",
                                "com.mysql.cj.jdbc.Driver"
                        ),
                        "com.mysql.cj.jdbc.Driver"
                );
        String jdbcUrl =
                nonBlank(
                        database.getString(
                                "database.jdbc-url",
                                "jdbc:mysql://127.0.0.1:3306/mineacle"
                        ),
                        "jdbc:mysql://127.0.0.1:3306/mineacle"
                );
        String username =
                nonBlank(
                        database.getString(
                                "database.username",
                                "mineacle_core"
                        ),
                        "mineacle_core"
                );
        String password =
                database.getString(
                        "database.password",
                        ""
                );
        int queryTimeout =
                Math.clamp(
                        database.getInt(
                                "database.query-timeout-seconds",
                                5
                        ),
                        1,
                        30
                );
        String prefix =
                safeIdentifier(
                        sellConfig.getString(
                                "market.table-prefix",
                                DEFAULT_PREFIX
                        )
                );
        String catalogTable =
                prefix
                        + "_v10_catalog";
        String metaTable =
                prefix
                        + "_v10_catalog_meta";

        Class.forName(driverClass);

        try (Connection connection =
                     DriverManager.getConnection(
                             jdbcUrl,
                             username,
                             password
                     )) {
            connection.setAutoCommit(false);

            try {
                initializeMirror(
                        connection,
                        catalogTable,
                        metaTable,
                        queryTimeout
                );
                replaceCatalogMirror(
                        connection,
                        catalogTable,
                        snapshot,
                        queryTimeout
                );
                writeMeta(
                        connection,
                        metaTable,
                        summary,
                        queryTimeout
                );
                connection.commit();
            } catch (Exception exception) {
                try {
                    connection.rollback();
                } catch (Exception rollbackFailure) {
                    exception.addSuppressed(
                            rollbackFailure
                    );
                }
                throw exception;
            } finally {
                try {
                    connection.setAutoCommit(
                            true
                    );
                } catch (Exception ignored) {
                }
            }
        }

        core.getLogger().info(
                "Sell catalog v10 SQL audit mirror updated — revision "
                        + SellPricingPolicy
                        .CATALOG_REVISION
                        + ", "
                        + snapshot.entries().size()
                        + " row(s)"
        );
    }

    private void initializeMirror(
            Connection connection,
            String catalogTable,
            String metaTable,
            int queryTimeout
    ) throws Exception {
        try (Statement statement =
                     connection.createStatement()) {
            statement.setQueryTimeout(
                    queryTimeout
            );

            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %s (
                        material VARCHAR(64) PRIMARY KEY,
                        category VARCHAR(32) NOT NULL,
                        base_price_cents BIGINT NOT NULL,
                        server_sell_enabled TINYINT(1) NOT NULL,
                        market_enabled TINYINT(1) NOT NULL,
                        market_key VARCHAR(64) NOT NULL,
                        market_units BIGINT NOT NULL,
                        price_source VARCHAR(32) NOT NULL,
                        activation_state VARCHAR(40) NOT NULL,
                        catalog_revision INT NOT NULL,
                        updated_at BIGINT NOT NULL,
                        INDEX idx_v10_catalog_category (category),
                        INDEX idx_v10_catalog_market_key (market_key),
                        INDEX idx_v10_catalog_sell_enabled (server_sell_enabled)
                    ) ENGINE=InnoDB
                    DEFAULT CHARSET=utf8mb4
                    COLLATE=utf8mb4_unicode_ci
                    """.formatted(
                    catalogTable
            ));

            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS %s (
                        singleton_id TINYINT PRIMARY KEY,
                        catalog_revision INT NOT NULL,
                        total_rows INT NOT NULL,
                        sellable_rows INT NOT NULL,
                        reference_rows INT NOT NULL,
                        commodity_rows INT NOT NULL,
                        derived_rows INT NOT NULL,
                        fallback_rows INT NOT NULL,
                        variant_rows INT NOT NULL,
                        one_cent_rows INT NOT NULL,
                        unsafe_rows INT NOT NULL,
                        commodity_groups INT NOT NULL,
                        recipe_count INT NOT NULL,
                        cyclic_recipe_count INT NOT NULL,
                        capped_reference_rows INT NOT NULL,
                        status VARCHAR(16) NOT NULL,
                        updated_at BIGINT NOT NULL
                    ) ENGINE=InnoDB
                    DEFAULT CHARSET=utf8mb4
                    COLLATE=utf8mb4_unicode_ci
                    """.formatted(
                    metaTable
            ));
        }
    }

    private void replaceCatalogMirror(
            Connection connection,
            String table,
            SellCatalogSnapshot snapshot,
            int queryTimeout
    ) throws Exception {
        try (Statement delete =
                     connection.createStatement()) {
            delete.setQueryTimeout(
                    queryTimeout
            );
            delete.executeUpdate(
                    "DELETE FROM "
                            + table
                            + " WHERE material IS NOT NULL"
            );
        }

        try (PreparedStatement statement =
                     connection.prepareStatement("""
                             INSERT INTO %s (
                                 material,
                                 category,
                                 base_price_cents,
                                 server_sell_enabled,
                                 market_enabled,
                                 market_key,
                                 market_units,
                                 price_source,
                                 activation_state,
                                 catalog_revision,
                                 updated_at
                             ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                             """.formatted(
                             table
                     ))) {
            statement.setQueryTimeout(
                    queryTimeout
            );

            long now =
                    System.currentTimeMillis();

            for (SellCatalogEntry entry
                    : snapshot.entries().values()) {
                statement.setString(
                        1,
                        entry.material().name()
                );
                statement.setString(
                        2,
                        entry.category()
                );
                statement.setLong(
                        3,
                        entry.baseCents()
                );
                statement.setBoolean(
                        4,
                        entry.serverSellEnabled()
                );
                statement.setBoolean(
                        5,
                        entry.marketEnabled()
                );
                statement.setString(
                        6,
                        entry.marketKey()
                );
                statement.setLong(
                        7,
                        entry.marketUnits()
                );
                statement.setString(
                        8,
                        entry.priceSource()
                );
                statement.setString(
                        9,
                        entry.activationState()
                );
                statement.setInt(
                        10,
                        entry.catalogRevision()
                );
                statement.setLong(
                        11,
                        now
                );
                statement.addBatch();
            }

            statement.executeBatch();
        }
    }

    private void writeMeta(
            Connection connection,
            String table,
            Summary summary,
            int queryTimeout
    ) throws Exception {
        try (PreparedStatement statement =
                     connection.prepareStatement("""
                             INSERT INTO %s (
                                 singleton_id,
                                 catalog_revision,
                                 total_rows,
                                 sellable_rows,
                                 reference_rows,
                                 commodity_rows,
                                 derived_rows,
                                 fallback_rows,
                                 variant_rows,
                                 one_cent_rows,
                                 unsafe_rows,
                                 commodity_groups,
                                 recipe_count,
                                 cyclic_recipe_count,
                                 capped_reference_rows,
                                 status,
                                 updated_at
                             ) VALUES (
                                 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                             )
                             ON DUPLICATE KEY UPDATE
                                 catalog_revision = VALUES(catalog_revision),
                                 total_rows = VALUES(total_rows),
                                 sellable_rows = VALUES(sellable_rows),
                                 reference_rows = VALUES(reference_rows),
                                 commodity_rows = VALUES(commodity_rows),
                                 derived_rows = VALUES(derived_rows),
                                 fallback_rows = VALUES(fallback_rows),
                                 variant_rows = VALUES(variant_rows),
                                 one_cent_rows = VALUES(one_cent_rows),
                                 unsafe_rows = VALUES(unsafe_rows),
                                 commodity_groups = VALUES(commodity_groups),
                                 recipe_count = VALUES(recipe_count),
                                 cyclic_recipe_count = VALUES(cyclic_recipe_count),
                                 capped_reference_rows = VALUES(capped_reference_rows),
                                 status = VALUES(status),
                                 updated_at = VALUES(updated_at)
                             """.formatted(
                             table
                     ))) {
            statement.setQueryTimeout(
                    queryTimeout
            );
            statement.setInt(
                    1,
                    SellPricingPolicy
                            .CATALOG_REVISION
            );
            statement.setInt(
                    2,
                    summary.total()
            );
            statement.setInt(
                    3,
                    summary.sellable()
            );
            statement.setInt(
                    4,
                    summary.references()
            );
            statement.setInt(
                    5,
                    summary.commodity()
            );
            statement.setInt(
                    6,
                    summary.derived()
            );
            statement.setInt(
                    7,
                    summary.fallback()
            );
            statement.setInt(
                    8,
                    summary.variants()
            );
            statement.setInt(
                    9,
                    summary.oneCent()
            );
            statement.setInt(
                    10,
                    summary.unsafe()
            );
            statement.setInt(
                    11,
                    summary.commodityGroups()
            );
            statement.setInt(
                    12,
                    summary.recipes()
            );
            statement.setInt(
                    13,
                    summary.cyclicRecipes()
            );
            statement.setInt(
                    14,
                    summary.cappedReferences()
            );
            statement.setString(
                    15,
                    summary.unsafe() == 0
                            ? "READY"
                            : "REVIEW"
            );
            statement.setLong(
                    16,
                    System.currentTimeMillis()
            );
            statement.executeUpdate();
        }
    }

    private String safeIdentifier(
            String raw
    ) {
        String normalized =
                nonBlank(
                        raw,
                        DEFAULT_PREFIX
                )
                        .toLowerCase(
                                Locale.ROOT
                        );

        return normalized.matches(
                "[a-z0-9_]+"
        )
                ? normalized
                : DEFAULT_PREFIX;
    }

    private String nonBlank(
            String value,
            String fallback
    ) {
        return value == null
                || value.isBlank()
                ? fallback
                : value.trim();
    }
}
