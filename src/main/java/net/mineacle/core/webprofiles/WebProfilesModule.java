package net.mineacle.core.webprofiles;

import net.mineacle.core.Core;
import net.mineacle.core.bootstrap.Module;
import net.mineacle.core.webprofiles.listener.WebProfileListener;
import net.mineacle.core.webprofiles.service.WebProfileSyncService;
import net.mineacle.core.webprofiles.storage.WebProfileRepository;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;

public final class WebProfilesModule extends Module {

    private WebProfileSyncService syncService;

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

        core.getServer().getPluginManager().registerEvents(new WebProfileListener(core, syncService), core);
    }

    @Override
    public void disable() {
        if (syncService != null) {
            syncService.stop();
            syncService = null;
        }
    }
}
