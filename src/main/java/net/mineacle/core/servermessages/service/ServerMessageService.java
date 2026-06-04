package net.mineacle.core.servermessages.service;

import net.mineacle.core.Core;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerLoginEvent;

import java.io.File;
import java.io.IOException;
import java.util.List;

public final class ServerMessageService {

    private final Core core;
    private final File file;
    private FileConfiguration config;

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

    public int delaySeconds(String key) {
        return Math.max(0, config.getInt(key + ".delay-seconds", 3));
    }

    public String command(String key) {
        return config.getString(key + ".command", key.equalsIgnoreCase("restart") ? "restart" : "stop");
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

    public void kickAll(String key) {
        String message = message(key);

        for (Player player : Bukkit.getOnlinePlayers()) {
            player.kickPlayer(message);
        }
    }

    public void runServerCommandLater(String key) {
        int delayTicks = delaySeconds(key) * 20;
        String command = command(key);

        Bukkit.getScheduler().runTaskLater(core, () -> {
            Server server = Bukkit.getServer();
            server.dispatchCommand(server.getConsoleSender(), command);
        }, delayTicks);
    }
}
