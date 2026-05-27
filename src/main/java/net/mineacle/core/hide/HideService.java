package net.mineacle.core.hide;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.nametag.NametagModule;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

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
    private BukkitTask task;
    private int actionbarTicks;

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

    public void start() {
        stop();
        task = core.getServer().getScheduler().runTaskTimer(core, () -> {
            actionbarTicks += 2;

            if (actionbarTicks >= 40) {
                actionbarTicks = 0;
                sendActionbars();
            }
        }, 2L, 2L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    public boolean enabled() {
        return config.getBoolean("enabled", true);
    }

    public boolean canUse(Player player) {
        return player.hasPermission(permission()) || player.hasPermission(adminPermission());
    }

    public String permission() {
        return config.getString("permission", "mineacle.plus");
    }

    public String adminPermission() {
        return config.getString("admin-see-permission", "mineaclehide.admin");
    }

    public boolean visibleObfuscated() {
        return config.getBoolean("visible-obfuscated", true);
    }

    public boolean hideFromTab() {
        return config.getBoolean("hide-from-tab", false);
    }

    public boolean tabObfuscateName() {
        return config.getBoolean("tab.obfuscate-name", true);
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
        updateTabName(player);
        NametagModule.refreshAll();
    }

    public void show(Player player) {
        hidden.remove(player.getUniqueId());

        for (Player viewer : Bukkit.getOnlinePlayers()) {
            viewer.showPlayer(core, player);
        }

        restoreTabName(player);
        NametagModule.refreshAll();
    }

    public void apply(Player hiddenPlayer) {
        if (!hiddenPlayer.isOnline()) {
            return;
        }

        for (Player viewer : Bukkit.getOnlinePlayers()) {
            applyViewer(viewer, hiddenPlayer);
        }

        updateTabName(hiddenPlayer);
        NametagModule.refreshAll();
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

        if (visibleObfuscated()) {
            viewer.showPlayer(core, hiddenPlayer);
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

            restoreTabName(hiddenPlayer);
        }

        hidden.clear();
        NametagModule.refreshAll();
    }

    public String message(String path, String fallback) {
        return config.getString("messages." + path, fallback);
    }

    public String parsedMessage(String path, String fallback, Player player) {
        return TextColor.color(message(path, fallback).replace("%player%", DisplayNames.displayName(player)));
    }

    public boolean shouldHideRealNametag(Player player) {
        return player != null && isHidden(player.getUniqueId()) && visibleObfuscated();
    }

    private void updateTabName(Player player) {
        if (!isHidden(player.getUniqueId())) {
            restoreTabName(player);
            return;
        }

        if (hideFromTab()) {
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                if (!viewer.getUniqueId().equals(player.getUniqueId()) && !viewer.hasPermission(adminPermission())) {
                    viewer.hidePlayer(core, player);
                }
            }
            return;
        }

        if (!tabObfuscateName()) {
            return;
        }

        player.playerListName(component(format("tab.format", "%rank%%hidecolor%&k%displayname%", player)));
    }

    private void restoreTabName(Player player) {
        player.playerListName(component(DisplayNames.prefixedDisplayName(player)));
    }

    private void sendActionbars() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!isHidden(player.getUniqueId())) {
                continue;
            }

            player.sendActionBar(component(message("actionbar", "&#bbbbbbYou are hidden")));
        }
    }

    private String format(String path, String fallback, Player player) {
        String value = config.getString(path, fallback);

        return value
                .replace("%hidecolor%", hiddenNameColor(player))
                .replace("%player%", DisplayNames.username(player))
                .replace("%displayname%", DisplayNames.displayName(player))
                .replace("%nickname%", DisplayNames.nickname(player))
                .replace("%rank%", DisplayNames.luckPermsPrefix(player));
    }

    private String hiddenNameColor(Player player) {
        if (player.isOp() || player.hasPermission(adminPermission())) {
            return config.getString("hidden-name-colors.admin", "#ff88ff");
        }

        return config.getString("hidden-name-colors.plus", "#ffffff");
    }

    private Component component(String message) {
        return LegacyComponentSerializer.legacySection().deserialize(TextColor.color(message));
    }
}
