package net.mineacle.core;

import net.mineacle.core.admininspect.AdminInspectModule;
import net.mineacle.core.auctionhouse.AuctionHouseModule;
import net.mineacle.core.baltop.BalTopModule;
import net.mineacle.core.bootstrap.Module;
import net.mineacle.core.bootstrap.ModuleManager;
import net.mineacle.core.bounty.BountyModule;
import net.mineacle.core.chat.ChatModule;
import net.mineacle.core.collision.CollisionModule;
import net.mineacle.core.common.gui.MenuCloseListener;
import net.mineacle.core.common.sound.GuiSoundListener;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.storage.DebouncedYamlPersistence;
import net.mineacle.core.common.teleport.TeleportLifecycleListener;
import net.mineacle.core.common.teleport.TeleportService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.doublejump.DoubleJumpModule;
import net.mineacle.core.duels.DuelsModule;
import net.mineacle.core.economy.EconomyModule;
import net.mineacle.core.enchant.EnchantModule;
import net.mineacle.core.gamemode.GamemodeModule;
import net.mineacle.core.hide.HideModule;
import net.mineacle.core.homes.HomesModule;
import net.mineacle.core.links.LinksModule;
import net.mineacle.core.market.MarketModule;
import net.mineacle.core.nametag.NametagModule;
import net.mineacle.core.orders.OrdersModule;
import net.mineacle.core.placeholders.PlaceholdersModule;
import net.mineacle.core.rtp.RtpModule;
import net.mineacle.core.security.SecurityModule;
import net.mineacle.core.sell.SellModule;
import net.mineacle.core.shulkerpreview.ShulkerPreviewModule;
import net.mineacle.core.spawn.SpawnModule;
import net.mineacle.core.spawnprotection.SpawnProtectionModule;
import net.mineacle.core.stats.StatsModule;
import net.mineacle.core.teams.TeamsModule;
import net.mineacle.core.tpa.TpaModule;
import net.mineacle.core.warp.WarpModule;
import net.mineacle.core.webprofiles.WebProfilesModule;
import net.mineacle.core.worldmaintenance.WorldMaintenanceModule;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

public final class Core extends JavaPlugin {

    private static Core instance;

    private FileConfiguration messagesConfig;
    private File homesFile;
    private FileConfiguration homesConfig;
    private File teamsFile;
    private FileConfiguration teamsConfig;
    private FileConfiguration economyConfig;

    private TeleportService teleportService;
    private DebouncedYamlPersistence yamlPersistence;
    private ModuleManager moduleManager;

    public static Core instance() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        long startedAt = System.nanoTime();

        try {
            initializeCoreFiles();

            yamlPersistence =
                    new DebouncedYamlPersistence(this);

            teleportService =
                    new TeleportService(this);
            teleportService.start();

            getServer()
                    .getPluginManager()
                    .registerEvents(
                            new TeleportLifecycleListener(
                                    teleportService
                            ),
                            this
                    );
            getServer()
                    .getPluginManager()
                    .registerEvents(
                            new MenuCloseListener(this),
                            this
                    );
            getServer()
                    .getPluginManager()
                    .registerEvents(
                            new GuiSoundListener(this),
                            this
                    );

            moduleManager =
                    new ModuleManager(this);
            registerModules();

            getLogger().info(
                    "MineacleCore enabled successfully with "
                            + moduleManager.size()
                            + " modules ("
                            + elapsedMillis(startedAt)
                            + "ms)"
            );
        } catch (Exception exception) {
            getLogger().log(
                    Level.SEVERE,
                    "MineacleCore failed during startup",
                    exception
            );

            shutdownRuntime();
            getServer()
                    .getPluginManager()
                    .disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        shutdownRuntime();
    }

    public TeleportService teleports() {
        return teleportService;
    }

    public void registerModule(
            Module module
    ) throws Exception {
        if (moduleManager == null) {
            throw new IllegalStateException(
                    "ModuleManager is not initialized"
            );
        }

        moduleManager.register(module);
    }

    public void reloadCoreFiles() {
        if (yamlPersistence != null) {
            yamlPersistence.flushNow(
                    persistenceTargets()
            );
        }

        reloadConfig();
        loadMessagesFile();
        loadHomesFile();
        loadTeamsFile();
        loadEconomyFile();
        SoundService.clearCache();
    }

    public FileConfiguration getMessagesConfig() {
        return messagesConfig;
    }

    public FileConfiguration getHomesConfig() {
        return homesConfig;
    }

    public FileConfiguration getTeamsConfig() {
        return teamsConfig;
    }

    public FileConfiguration getEconomyConfig() {
        return economyConfig;
    }

    public String getMessage(String path) {
        if (messagesConfig == null) {
            return TextColor.color(
                    "&cMissing message: " + path
            );
        }

        return TextColor.color(
                messagesConfig.getString(
                        path,
                        "&cMissing message: " + path
                )
        );
    }

    public String getMessageText(String value) {
        return TextColor.color(value);
    }

    public List<Module> modules() {
        if (moduleManager == null) {
            return List.of();
        }

        return moduleManager.modules();
    }

    private void registerModules()
            throws Exception {
        List<Module> modules = List.of(
                new HomesModule(),
                new TeamsModule(),
                new TpaModule(),
                new StatsModule(),
                new EconomyModule(),
                new BalTopModule(),
                new ChatModule(),
                new CollisionModule(),
                new LinksModule(),
                new SecurityModule(),
                new AdminInspectModule(),
                new EnchantModule(),
                new NametagModule(),
                new HideModule(),
                new PlaceholdersModule(),
                new WebProfilesModule(),
                new SpawnModule(),
                new WarpModule(),
                new SpawnProtectionModule(),
                new DoubleJumpModule(),
                new RtpModule(),
                new WorldMaintenanceModule(),
                new DuelsModule(),
                /*
                 * Sell must initialize before the shared Market layer so all
                 * downstream bid/ask floors resolve from the current live v10
                 * guaranteed server liquidation price.
                 */
                new SellModule(),
                new MarketModule(),
                new OrdersModule(),
                new AuctionHouseModule(),
                new ShulkerPreviewModule(),
                new BountyModule(),
                new GamemodeModule()
        );

        for (Module module : modules) {
            registerModule(module);
        }
    }

    private void initializeCoreFiles() {
        ensureDataFolder();
        saveDefaultConfig();
        loadMessagesFile();
        loadHomesFile();
        loadTeamsFile();
        loadEconomyFile();
    }

    private void loadMessagesFile() {
        messagesConfig =
                YamlConfiguration
                        .loadConfiguration(
                                ensureBundledFile(
                                        "messages.yml"
                                )
                        );
    }

    private void loadHomesFile() {
        homesFile =
                ensureBundledFile("homes.yml");
        homesConfig =
                YamlConfiguration
                        .loadConfiguration(
                                homesFile
                        );
    }

    private void loadTeamsFile() {
        teamsFile =
                ensureBundledFile("teams.yml");
        teamsConfig =
                YamlConfiguration
                        .loadConfiguration(
                                teamsFile
                        );
    }

    private void loadEconomyFile() {
        File economyFile =
                ensureEconomyFile();

        economyConfig =
                YamlConfiguration
                        .loadConfiguration(
                                economyFile
                        );
    }

    private File ensureBundledFile(
            String resourceName
    ) {
        ensureDataFolder();

        File file = new File(
                getDataFolder(),
                resourceName
        );

        if (!file.exists()) {
            saveResource(
                    resourceName,
                    false
            );
        }

        if (!file.isFile()) {
            throw new IllegalStateException(
                    "Could not initialize "
                            + resourceName
            );
        }

        return file;
    }

    private File ensureEconomyFile() {
        ensureDataFolder();

        File file = new File(
                getDataFolder(),
                "economy.yml"
        );

        if (!file.exists()) {
            try {
                if (!file.createNewFile()
                        && !file.exists()) {
                    throw new IOException(
                            "createNewFile returned false"
                    );
                }
            } catch (IOException exception) {
                throw new IllegalStateException(
                        "Could not create economy.yml",
                        exception
                );
            }
        }

        if (!file.isFile()) {
            throw new IllegalStateException(
                    "Could not initialize economy.yml"
            );
        }

        return file;
    }

    private void ensureDataFolder() {
        if (getDataFolder().exists()) {
            return;
        }

        if (!getDataFolder().mkdirs()
                && !getDataFolder().exists()) {
            throw new IllegalStateException(
                    "Could not create MineacleCore data folder"
            );
        }
    }

    public void saveHomesFile() {
        requestYamlSave(
                "homes.yml",
                homesConfig,
                homesFile
        );
    }

    public void saveTeamsFile() {
        requestYamlSave(
                "teams.yml",
                teamsConfig,
                teamsFile
        );
    }

    private void requestYamlSave(
            String label,
            FileConfiguration configuration,
            File file
    ) {
        if (configuration == null
                || file == null) {
            return;
        }

        if (yamlPersistence != null) {
            yamlPersistence.request(
                    label,
                    configuration,
                    file
            );
            return;
        }

        try {
            configuration.save(file);
        } catch (IOException exception) {
            getLogger().log(
                    Level.SEVERE,
                    "Could not save " + label,
                    exception
            );
        }
    }

    private List<DebouncedYamlPersistence.Target>
    persistenceTargets() {
        List<DebouncedYamlPersistence.Target>
                targets =
                new ArrayList<>(2);

        if (homesConfig != null
                && homesFile != null) {
            targets.add(
                    new DebouncedYamlPersistence
                            .Target(
                            "homes.yml",
                            homesConfig,
                            homesFile
                    )
            );
        }

        if (teamsConfig != null
                && teamsFile != null) {
            targets.add(
                    new DebouncedYamlPersistence
                            .Target(
                            "teams.yml",
                            teamsConfig,
                            teamsFile
                    )
            );
        }

        return List.copyOf(targets);
    }

    /**
     * One idempotent shutdown path is used for normal disable and startup
     * rollback. Modules are stopped before shared services so module cleanup
     * can still request persistence or use the teleport service if required.
     */
    private void shutdownRuntime() {
        ModuleManager modules =
                moduleManager;

        if (modules != null) {
            modules.disableAll();
            moduleManager = null;
        }

        TeleportService teleports =
                teleportService;

        if (teleports != null) {
            teleports.shutdown();
            teleportService = null;
        }

        DebouncedYamlPersistence persistence =
                yamlPersistence;

        if (persistence != null) {
            persistence.flushAndShutdown(
                    persistenceTargets()
            );
            yamlPersistence = null;
        }

        SoundService.clearCache();
        instance = null;
    }

    private long elapsedMillis(
            long startedAt
    ) {
        return Math.max(
                0L,
                (System.nanoTime()
                        - startedAt)
                        / 1_000_000L
        );
    }
}
