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
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class HideService {

    private final Core core;
    private final File file;
    private final Set<UUID> hidden = new HashSet<>();

    private FileConfiguration config;
    private BukkitTask actionbarTask;

    public HideService(Core core) {
        this.core = core;
        this.file = new File(
                core.getDataFolder(),
                "hide.yml"
        );
        reload();
    }

    public void reload() {
        ensureDataFile();
        config = YamlConfiguration.loadConfiguration(file);

        if (!enabled() && !hidden.isEmpty()) {
            showAll();
        }
    }

    public void start() {
        stop();

        actionbarTask = core.getServer()
                .getScheduler()
                .runTaskTimer(
                        core,
                        this::sendActionbars,
                        40L,
                        40L
                );
    }

    public void stop() {
        if (actionbarTask != null) {
            actionbarTask.cancel();
            actionbarTask = null;
        }
    }

    public boolean enabled() {
        return config != null
                && config.getBoolean("enabled", true);
    }

    public boolean canUse(Player player) {
        return player != null
                && player.hasPermission(permission());
    }

    public String permission() {
        return config == null
                ? "mineaclehide.admin"
                : config.getString(
                        "permission",
                        "mineaclehide.admin"
                );
    }

    /**
     * Compatibility accessor retained for callers using the former name.
     */
    public String adminPermission() {
        return permission();
    }

    public boolean isHidden(UUID playerId) {
        return playerId != null && hidden.contains(playerId);
    }

    public Set<UUID> hiddenPlayers() {
        return Set.copyOf(hidden);
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
        if (player == null || !player.isOnline()) {
            return;
        }

        if (hidden.add(player.getUniqueId())) {
            NametagModule.refresh(player);
        }
    }

    public void show(Player player) {
        if (player == null) {
            return;
        }

        if (hidden.remove(player.getUniqueId())
                && player.isOnline()) {
            NametagModule.refresh(player);
        }
    }

    /**
     * Removes temporary Hide state without changing entity visibility.
     */
    public void forget(Player player) {
        if (player != null) {
            hidden.remove(player.getUniqueId());
        }
    }

    public void apply(Player hiddenPlayer) {
        if (hiddenPlayer == null
                || !hiddenPlayer.isOnline()
                || !isHidden(hiddenPlayer.getUniqueId())) {
            return;
        }

        NametagModule.refresh(hiddenPlayer);
    }

    public void applyAll() {
        NametagModule.refreshAll();
    }

    /**
     * Compatibility no-op. Hide is nametag-only and must never override
     * visibility decisions made by vanish or moderation plugins.
     */
    public void applyViewer(Player viewer) {
        // Deliberately does not call showPlayer or hidePlayer
    }

    /**
     * Compatibility no-op. Hide is nametag-only and must never override
     * visibility decisions made by vanish or moderation plugins.
     */
    public void applyViewer(
            Player viewer,
            Player hiddenPlayer
    ) {
        // Deliberately does not call showPlayer or hidePlayer
    }

    public void showAll() {
        if (hidden.isEmpty()) {
            return;
        }

        hidden.clear();
        NametagModule.refreshAll();
    }

    public String message(
            String path,
            String fallback
    ) {
        if (config == null) {
            return fallback;
        }

        return config.getString(
                "messages." + path,
                fallback
        );
    }

    public String parsedMessage(
            String path,
            String fallback,
            Player player
    ) {
        return TextColor.color(
                message(path, fallback).replace(
                        "%player%",
                        DisplayNames.displayName(player)
                )
        );
    }

    public boolean shouldHideRealNametag(Player player) {
        return player != null
                && isHidden(player.getUniqueId());
    }

    private void sendActionbars() {
        if (hidden.isEmpty()) {
            return;
        }

        String message = message(
                "actionbar",
                "&#bbbbbbNametag hidden"
        );
        Component component = LegacyComponentSerializer
                .legacySection()
                .deserialize(TextColor.color(message));

        for (UUID playerId : Set.copyOf(hidden)) {
            Player player = Bukkit.getPlayer(playerId);

            if (player == null || !player.isOnline()) {
                hidden.remove(playerId);
                continue;
            }

            player.sendActionBar(component);
        }
    }

    private void ensureDataFile() {
        File dataFolder = core.getDataFolder();

        if (!dataFolder.exists()
                && !dataFolder.mkdirs()
                && !dataFolder.exists()) {
            throw new IllegalStateException(
                    "Could not create MineacleCore data folder"
            );
        }

        if (!file.exists()) {
            core.saveResource("hide.yml", false);
        }

        if (!file.isFile()) {
            throw new IllegalStateException(
                    "Could not initialize hide.yml"
            );
        }
    }
}
