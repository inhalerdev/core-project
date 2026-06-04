package net.mineacle.core.servermessages.service;

import net.mineacle.core.Core;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerLoginEvent;

import java.io.File;
import java.io.IOException;
import java.util.List;

public final class ServerMessageService {

    private final Core core;
    private final File file;
    private FileConfiguration config;

    private String activeShutdownMessageKey;
    private long activeShutdownMessageUntil;

    public ServerMessageService(Core core) {
        this.core = core;
        this.file = new File(core.getDataFolder(), "servermessages.yml");
        reload();
    }

    public void reload() {
        this.config = YamlConfiguration.loadConfiguration(file);
    }

    public void save() {
        if (config == null) {
            return;
        }

        try {
            config.save(file);
        } catch (IOException exception) {
            core.getLogger().severe("Could not save servermessages.yml");
            exception.printStackTrace();
        }
    }

    public FileConfiguration config() {
        return config;
    }

    public boolean enabled() {
        return config.getBoolean("enabled", true);
    }

    public boolean maintenanceEnabled() {
        return config.getBoolean("maintenance.enabled", false);
    }

    public void setMaintenance(boolean enabled) {
        config.set("maintenance.enabled", enabled);
        save();
    }

    public boolean bypass(Player player) {
        return player.hasPermission(config.getString("maintenance.bypass-permission", "mineacleservermessages.bypass"));
    }

    public String message(String key) {
        List<String> lines = config.getStringList("messages." + key);

        if (lines == null || lines.isEmpty()) {
            return TextColor.color("&d&lMineacle\n\n&#bbbbbbPlease reconnect in a moment");
        }

        return TextColor.color(String.join("\n", lines));
    }

    public String chat(String key) {
        return TextColor.color(config.getString("chat." + key, "&dMineacle &8» &#bbbbbbDone"));
    }

    public void beginServerControl(String key) {
        this.activeShutdownMessageKey = key.equalsIgnoreCase("restart") ? "restart" : "shutdown";
        this.activeShutdownMessageUntil = System.currentTimeMillis() + (config.getLong("shutdown-message-active-seconds", 30L) * 1000L);
    }

    public String activeShutdownMessage() {
        if (activeShutdownMessageKey == null || activeShutdownMessageKey.isBlank()) {
            return null;
        }

        if (System.currentTimeMillis() > activeShutdownMessageUntil) {
            activeShutdownMessageKey = null;
            activeShutdownMessageUntil = 0L;
            return null;
        }

        return message(activeShutdownMessageKey);
    }

    public boolean handleAsyncPreLogin(AsyncPlayerPreLoginEvent event) {
        if (!enabled()) {
            return false;
        }

        if (event.getLoginResult() == AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST, message("whitelist"));
            return true;
        }

        if (event.getLoginResult() == AsyncPlayerPreLoginEvent.Result.KICK_FULL) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_FULL, message("full"));
            return true;
        }

        return false;
    }

    public boolean handleLogin(PlayerLoginEvent event) {
        if (!enabled()) {
            return false;
        }

        Player player = event.getPlayer();

        if (maintenanceEnabled() && !bypass(player)) {
            event.disallow(PlayerLoginEvent.Result.KICK_OTHER, message("maintenance"));
            return true;
        }

        PlayerLoginEvent.Result result = event.getResult();

        if (result == PlayerLoginEvent.Result.KICK_WHITELIST) {
            event.disallow(PlayerLoginEvent.Result.KICK_WHITELIST, message("whitelist"));
            return true;
        }

        if (result == PlayerLoginEvent.Result.KICK_FULL) {
            event.disallow(PlayerLoginEvent.Result.KICK_FULL, message("full"));
            return true;
        }

        return false;
    }
}
