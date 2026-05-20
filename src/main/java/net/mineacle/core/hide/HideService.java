package net.mineacle.core.hide;

import net.mineacle.core.Core;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class HideService {

    private final Core core;
    private final File file;
    private FileConfiguration config;
    private final Set<UUID> hidden = new HashSet<>();

    public HideService(Core core) {
        this.core = core;
        this.file = new File(core.getDataFolder(), "hide.yml");
        reload();
    }

    public void reload() {
        if (!core.getDataFolder().exists()) {
            core.getDataFolder().mkdirs();
        }

        if (!file.exists()) {
            core.saveResource("hide.yml", false);
        }

        config = YamlConfiguration.loadConfiguration(file);
    }

    public boolean enabled() {
        return config.getBoolean("enabled", true);
    }

    public boolean canUse(Player player) {
        return player.hasPermission(permission()) || player.hasPermission("mineaclehide.admin");
    }

    public String permission() {
        return config.getString("permission", "mineacle.plus");
    }

    public String adminPermission() {
        return config.getString("admin-see-permission", "mineaclehide.admin");
    }

    public boolean hideFromTab() {
        return config.getBoolean("hide-from-tab", true);
    }

    public boolean disablePickup() {
        return config.getBoolean("disable-pickup", true);
    }

    public boolean disableEntityTarget() {
        return config.getBoolean("disable-entity-target", true);
    }

    public boolean isHidden(UUID uuid) {
        return hidden.contains(uuid);
    }

    public Set<UUID> hiddenPlayers() {
        return Collections.unmodifiableSet(hidden);
    }

    public boolean toggle(Player player) {
        if (isHidden(player.getUniqueId())) {
            show(player);
            return false;
        }

        hide(player);
        return true;
    }

    public void hide(Player player) {
        hidden.add(player.getUniqueId());
        apply(player);
    }

    public void show(Player player) {
        hidden.remove(player.getUniqueId());

        for (Player viewer : Bukkit.getOnlinePlayers()) {
            viewer.showPlayer(core, player);
        }
    }

    public void apply(Player hiddenPlayer) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            applyViewer(viewer, hiddenPlayer);
        }
    }

    public void applyAll() {
        for (Player hiddenPlayer : Bukkit.getOnlinePlayers()) {
            if (!isHidden(hiddenPlayer.getUniqueId())) {
                continue;
            }

            apply(hiddenPlayer);
        }
    }

    public void applyViewer(Player viewer) {
        for (Player hiddenPlayer : Bukkit.getOnlinePlayers()) {
            if (!isHidden(hiddenPlayer.getUniqueId())) {
                continue;
            }

            applyViewer(viewer, hiddenPlayer);
        }
    }

    public void applyViewer(Player viewer, Player hiddenPlayer) {
        if (viewer.getUniqueId().equals(hiddenPlayer.getUniqueId())) {
            return;
        }

        if (viewer.hasPermission(adminPermission())) {
            viewer.showPlayer(core, hiddenPlayer);
            return;
        }

        viewer.hidePlayer(core, hiddenPlayer);
    }

    public void showAll() {
        for (Player hiddenPlayer : Bukkit.getOnlinePlayers()) {
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                viewer.showPlayer(core, hiddenPlayer);
            }
        }

        hidden.clear();
    }

    public String message(String path, String fallback) {
        return config.getString("messages." + path, fallback);
    }

    public String parsedMessage(String path, String fallback, Player player) {
        return TextColor.color(message(path, fallback)
                .replace("%player%", DisplayNames.displayName(player)));
    }
}
