package net.mineacle.core.duels;

import net.mineacle.core.Core;
import net.mineacle.core.bootstrap.Module;
import net.mineacle.core.duels.command.DuelCommand;
import net.mineacle.core.duels.listener.DuelListener;
import net.mineacle.core.duels.service.DuelService;
import net.mineacle.core.duels.service.FightTrackerService;
import net.mineacle.core.duels.storage.FightRepository;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;

public final class DuelsModule extends Module {

    private Core core;
    private File webProfilesFile;
    private DuelService duelService;
    private FightTrackerService fightTracker;
    private BukkitTask tickTask;

    @Override
    public String name() {
        return "Duels";
    }

    @Override
    public void enable(Core core) throws Exception {
        this.core = core;

        ensureResource(core, "duels.yml");
        webProfilesFile = ensureResource(core, "webprofiles.yml");

        FileConfiguration webProfiles = loadWebProfiles();
        FightRepository fightRepository =
                new FightRepository(core, webProfiles);

        fightTracker = new FightTrackerService(
                core,
                webProfiles,
                fightRepository
        );
        fightTracker.start();

        duelService = new DuelService(core);

        PluginCommand duel = core.getCommand("duel");

        if (duel == null) {
            throw new IllegalStateException(
                    "Missing command in plugin.yml: duel"
            );
        }

        DuelCommand command = new DuelCommand(
                core,
                duelService,
                this::reload
        );
        duel.setExecutor(command);
        duel.setTabCompleter(command);

        core.getServer().getPluginManager().registerEvents(
                new DuelListener(duelService, fightTracker),
                core
        );

        tickTask = core.getServer().getScheduler().runTaskTimer(
                core,
                () -> {
                    duelService.tick();
                    fightTracker.tick();
                },
                20L,
                20L
        );
    }

    @Override
    public void disable() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }

        if (duelService != null) {
            duelService.shutdown();
            duelService = null;
        }

        if (fightTracker != null) {
            fightTracker.shutdown();
            fightTracker = null;
        }

        core = null;
        webProfilesFile = null;
    }

    private void reload() {
        if (core == null
                || duelService == null
                || fightTracker == null) {
            return;
        }

        duelService.reload();
        fightTracker.reload(loadWebProfiles());
    }

    private FileConfiguration loadWebProfiles() {
        FileConfiguration configuration =
                YamlConfiguration.loadConfiguration(webProfilesFile);

        if (migrateWorldNames(configuration)) {
            try {
                configuration.save(webProfilesFile);
            } catch (IOException exception) {
                core.getLogger().log(
                        Level.SEVERE,
                        "Could not save migrated webprofiles.yml",
                        exception
                );
            }
        }

        return configuration;
    }

    private boolean migrateWorldNames(
            FileConfiguration configuration
    ) {
        boolean changed = false;

        changed |= migrateMapping(
                configuration,
                "origins",
                "overworld",
                "Overworld"
        );
        changed |= migrateMapping(
                configuration,
                "origins_nether",
                "overworld_nether",
                "Nether"
        );
        changed |= migrateMapping(
                configuration,
                "world_nether",
                "overworld_nether",
                "Nether"
        );
        changed |= migrateMapping(
                configuration,
                "origins_the_end",
                "overworld_the_end",
                "End"
        );
        changed |= migrateMapping(
                configuration,
                "world_the_end",
                "overworld_the_end",
                "End"
        );

        List<String> configured = configuration.getStringList(
                "web-fights.allowed-worlds"
        );
        List<String> source = configured.isEmpty()
                ? List.of(
                "overworld",
                "overworld_nether",
                "overworld_the_end"
        )
                : configured;
        List<String> migrated = new ArrayList<>();

        for (String world : source) {
            String direct = directWorldName(world);

            if (!direct.isBlank()
                    && migrated.stream().noneMatch(
                    existing -> existing.equalsIgnoreCase(direct)
            )) {
                migrated.add(direct);
            }
        }

        if (!migrated.equals(configured)) {
            configuration.set(
                    "web-fights.allowed-worlds",
                    migrated
            );
            changed = true;
        }

        return changed;
    }

    private boolean migrateMapping(
            FileConfiguration configuration,
            String oldKey,
            String newKey,
            String fallbackName
    ) {
        String oldPath = "worlds.mappings." + oldKey;
        String newPath = "worlds.mappings." + newKey;
        boolean changed = false;

        if (!configuration.isConfigurationSection(newPath)) {
            String name = configuration.getString(
                    oldPath + ".name",
                    fallbackName
            );
            String group = configuration.getString(
                    oldPath + ".group",
                    "survival"
            );

            configuration.set(newPath + ".name", name);
            configuration.set(newPath + ".group", group);
            changed = true;
        }

        if (configuration.contains(oldPath)) {
            configuration.set(oldPath, null);
            changed = true;
        }

        return changed;
    }

    private String directWorldName(String world) {
        if (world == null) {
            return "";
        }

        return switch (world.trim().toLowerCase(Locale.ROOT)) {
            case "origins" -> "overworld";
            case "origins_nether", "world_nether" ->
                    "overworld_nether";
            case "origins_the_end", "world_the_end" ->
                    "overworld_the_end";
            default -> world.trim();
        };
    }

    private File ensureResource(
            Core core,
            String resourceName
    ) {
        File file = new File(core.getDataFolder(), resourceName);

        if (!file.exists()) {
            core.saveResource(resourceName, false);
        }

        if (!file.isFile()) {
            throw new IllegalStateException(
                    "Could not initialize " + resourceName
            );
        }

        return file;
    }
}
