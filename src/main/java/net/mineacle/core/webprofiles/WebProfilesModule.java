package net.mineacle.core.webprofiles;

import net.mineacle.core.Core;
import net.mineacle.core.bootstrap.Module;
import net.mineacle.core.webprofiles.listener.WebProfileListener;
import net.mineacle.core.webprofiles.service.WebProfileSyncService;
import net.mineacle.core.webprofiles.service.WebTeamSyncService;
import net.mineacle.core.webprofiles.storage.WebProfileRepository;
import net.mineacle.core.webprofiles.storage.WebTeamRepository;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;

public final class WebProfilesModule extends Module {

    private WebProfileSyncService syncService;
    private WebTeamSyncService teamSyncService;

    @Override
    public String name() {
        return "WebProfiles";
    }

    @Override
    public void enable(Core core) {
        File file = new File(core.getDataFolder(), "webprofiles.yml");

        if (!file.exists()) {
            core.saveResource("webprofiles.yml", false);
        }

        FileConfiguration config = YamlConfiguration.loadConfiguration(file);

        WebProfileRepository repository = new WebProfileRepository(core, config);
        syncService = new WebProfileSyncService(core, config, repository);
        syncService.start();

        WebTeamRepository teamRepository = new WebTeamRepository(core, config);
        teamSyncService = new WebTeamSyncService(core, config, teamRepository);
        teamSyncService.start();

        core.getServer().getPluginManager().registerEvents(new WebProfileListener(core, syncService), core);
    }

    @Override
    public void disable() {
        if (teamSyncService != null) {
            teamSyncService.stop();
            teamSyncService = null;
        }

        if (syncService != null) {
            syncService.stop();
            syncService = null;
        }
    }
}
