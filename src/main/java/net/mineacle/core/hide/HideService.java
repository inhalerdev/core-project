package net.mineacle.core.hide;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.nametag.NametagModule;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class HideService {

    private final Core core;
    private final File file;
    private FileConfiguration config;

    private final Set<UUID> hidden = new HashSet<>();
    private final Map<UUID, ArmorStand> nameTags = new HashMap<>();

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
            updateNameTags();

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

        removeAllNameTags();
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

    public boolean visibleObfuscated() {
        return config.getBoolean("visible-obfuscated", true);
    }

    public boolean hideFromTab() {
        return config.getBoolean("hide-from-tab", false);
    }

    public boolean obfuscatedNametagEnabled() {
        return config.getBoolean("obfuscated-nametag.enabled", true);
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
        removeNameTag(player.getUniqueId());

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
        updateNameTag(hiddenPlayer);
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
        removeAllNameTags();
        NametagModule.refreshAll();
    }

    public String message(String path, String fallback) {
        return config.getString("messages." + path, fallback);
    }

    public String parsedMessage(String path, String fallback, Player player) {
        return TextColor.color(message(path, fallback)
                .replace("%player%", DisplayNames.displayName(player)));
    }

    public boolean shouldHideRealNametag(Player player) {
        return player != null
                && isHidden(player.getUniqueId())
                && visibleObfuscated()
                && obfuscatedNametagEnabled();
    }

    private void updateNameTags() {
        Set<UUID> remove = new HashSet<>(nameTags.keySet());

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!isHidden(player.getUniqueId())) {
                continue;
            }

            remove.remove(player.getUniqueId());
            updateNameTag(player);
        }

        for (UUID uuid : remove) {
            removeNameTag(uuid);
        }
    }

    private void updateNameTag(Player player) {
        if (!visibleObfuscated() || !obfuscatedNametagEnabled()) {
            removeNameTag(player.getUniqueId());
            return;
        }

        ArmorStand stand = nameTags.get(player.getUniqueId());

        if (stand == null || stand.isDead() || !stand.isValid()) {
            stand = spawnNameTag(player);
            nameTags.put(player.getUniqueId(), stand);
        }

        if (!stand.getWorld().equals(player.getWorld())) {
            removeNameTag(player.getUniqueId());
            stand = spawnNameTag(player);
            nameTags.put(player.getUniqueId(), stand);
        }

        stand.teleport(nameTagLocation(player));
        stand.customName(nameTagComponent(player));
        stand.setCustomNameVisible(true);
    }

    private ArmorStand spawnNameTag(Player player) {
        ArmorStand stand = (ArmorStand) player.getWorld().spawnEntity(nameTagLocation(player), EntityType.ARMOR_STAND);

        stand.setInvisible(true);
        stand.setMarker(true);
        stand.setSmall(true);
        stand.setGravity(false);
        stand.setInvulnerable(true);
        stand.setSilent(true);
        stand.setCollidable(false);
        stand.setPersistent(false);
        stand.setRemoveWhenFarAway(false);
        stand.customName(nameTagComponent(player));
        stand.setCustomNameVisible(true);
        stand.addScoreboardTag("mineacle_hidden_nametag");
        stand.addScoreboardTag("mineacle_hidden_" + player.getUniqueId());

        return stand;
    }

    private Location nameTagLocation(Player player) {
        double yOffset = config.getDouble("obfuscated-nametag.y-offset", 2.25D);
        return player.getLocation().clone().add(0.0D, yOffset, 0.0D);
    }

    private Component nameTagComponent(Player player) {
        return component(format("obfuscated-nametag.format", "&#ff88ff+ &k%displayname%", player));
    }

    private void removeNameTag(UUID uuid) {
        ArmorStand stand = nameTags.remove(uuid);

        if (stand != null && stand.isValid()) {
            stand.remove();
        }
    }

    private void removeAllNameTags() {
        for (ArmorStand stand : nameTags.values()) {
            if (stand != null && stand.isValid()) {
                stand.remove();
            }
        }

        nameTags.clear();

        for (org.bukkit.World world : Bukkit.getWorlds()) {
            world.getEntitiesByClass(ArmorStand.class).stream()
                    .filter(stand -> stand.getScoreboardTags().contains("mineacle_hidden_nametag"))
                    .forEach(ArmorStand::remove);
        }
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

        player.playerListName(component(format("tab.format", "&#ff88ff+ &k%displayname%", player)));
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
                .replace("%player%", DisplayNames.username(player))
                .replace("%displayname%", DisplayNames.displayName(player))
                .replace("%nickname%", DisplayNames.nickname(player))
                .replace("%rank%", DisplayNames.luckPermsPrefix(player));
    }

    private Component component(String message) {
        return LegacyComponentSerializer.legacySection().deserialize(TextColor.color(message));
    }
}
