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
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.doublejump.DoubleJumpModule;
import net.mineacle.core.duels.DuelsModule;
import net.mineacle.core.economy.EconomyModule;
import net.mineacle.core.enchant.EnchantModule;
import net.mineacle.core.gamemode.GamemodeModule;
import net.mineacle.core.hide.HideModule;
import net.mineacle.core.homes.HomesModule;
import net.mineacle.core.links.LinksModule;
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
import java.util.List;
import java.util.logging.Level;

public final class Core extends JavaPlugin {

    private static Core instance;

    private File messagesFile;
    private FileConfiguration messagesConfig;

    private File homesFile;
    private FileConfiguration homesConfig;

    private File teamsFile;
    private FileConfiguration teamsConfig;

    private File economyFile;
    private FileConfiguration economyConfig;

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

            getServer().getPluginManager().registerEvents(
                    new MenuCloseListener(this),
                    this
            );
            getServer().getPluginManager().registerEvents(
                    new GuiSoundListener(this),
                    this
            );

            moduleManager = new ModuleManager(this);
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

            if (moduleManager != null) {
                moduleManager.disableAll();
            }

            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        try {
            if (moduleManager != null) {
                moduleManager.disableAll();
            }
        } finally {
            saveHomesFile();
            saveTeamsFile();
            saveEconomyFile();
            SoundService.clearCache();

            moduleManager = null;
            instance = null;
        }
    }

    public void registerModule(Module module)
            throws Exception {
        if (moduleManager == null) {
            throw new IllegalStateException(
                    "ModuleManager is not initialized"
            );
        }

        moduleManager.register(module);
    }

    public void reloadCoreFiles() {
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

        String value = messagesConfig.getString(
                path,
                "&cMissing message: " + path
        );
        return TextColor.color(value);
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

    private void registerModules() throws Exception {
        registerModule(new HomesModule());
        registerModule(new TeamsModule());
        registerModule(new TpaModule());
        registerModule(new StatsModule());
        registerModule(new EconomyModule());
        registerModule(new BalTopModule());
        registerModule(new ChatModule());
        registerModule(new CollisionModule());
        registerModule(new LinksModule());
        registerModule(new SecurityModule());
        registerModule(new AdminInspectModule());
        registerModule(new EnchantModule());
        registerModule(new NametagModule());
        registerModule(new HideModule());
        registerModule(new PlaceholdersModule());
        registerModule(new WebProfilesModule());
        registerModule(new SpawnModule());
        registerModule(new WarpModule());
        registerModule(new SpawnProtectionModule());
        registerModule(new DoubleJumpModule());
        registerModule(new RtpModule());
        registerModule(new WorldMaintenanceModule());
        registerModule(new DuelsModule());
        registerModule(new OrdersModule());
        registerModule(new SellModule());
        registerModule(new AuctionHouseModule());
        registerModule(new ShulkerPreviewModule());
        registerModule(new BountyModule());
        registerModule(new GamemodeModule());
    }

    private void initializeCoreFiles() {
        ensureDataFolder();
        saveDefaultConfig();
        loadMessagesFile();
        loadHomesFile();
        loadTeamsFile();
        loadEconomyFile();
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

    private void loadMessagesFile() {
        ensureDataFolder();
        messagesFile = new File(
                getDataFolder(),
                "messages.yml"
        );

        if (!messagesFile.exists()) {
            saveResource("messages.yml", false);
        }

        messagesConfig =
                YamlConfiguration.loadConfiguration(
                        messagesFile
                );
    }

    private void loadHomesFile() {
        ensureDataFolder();
        homesFile = new File(
                getDataFolder(),
                "homes.yml"
        );

        if (!homesFile.exists()) {
            saveResource("homes.yml", false);
        }

        homesConfig =
                YamlConfiguration.loadConfiguration(
                        homesFile
                );
    }

    private void loadTeamsFile() {
        ensureDataFolder();
        teamsFile = new File(
                getDataFolder(),
                "teams.yml"
        );

        if (!teamsFile.exists()) {
            saveResource("teams.yml", false);
        }

        teamsConfig =
                YamlConfiguration.loadConfiguration(
                        teamsFile
                );
    }

    private void loadEconomyFile() {
        ensureDataFolder();
        economyFile = new File(
                getDataFolder(),
                "economy.yml"
        );

        if (!economyFile.exists()) {
            try {
                if (!economyFile.createNewFile()
                        && !economyFile.exists()) {
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

        economyConfig =
                YamlConfiguration.loadConfiguration(
                        economyFile
                );
    }

    public void saveHomesFile() {
        saveYaml(
                homesConfig,
                homesFile,
                "homes.yml"
        );
    }

    public void saveTeamsFile() {
        saveYaml(
                teamsConfig,
                teamsFile,
                "teams.yml"
        );
    }

    public void saveEconomyFile() {
        saveYaml(
                economyConfig,
                economyFile,
                "economy.yml"
        );
    }

    private void saveYaml(
            FileConfiguration configuration,
            File file,
            String fileName
    ) {
        if (configuration == null || file == null) {
            return;
        }

        try {
            configuration.save(file);
        } catch (IOException exception) {
            getLogger().log(
                    Level.SEVERE,
                    "Could not save " + fileName,
                    exception
            );
        }
    }

    private long elapsedMillis(long startedAt) {
        return Math.max(
                0L,
                (System.nanoTime() - startedAt)
                        / 1_000_000L
        );
    }
}
