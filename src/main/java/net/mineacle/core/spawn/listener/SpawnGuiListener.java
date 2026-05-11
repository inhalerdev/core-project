package net.mineacle.core.spawn.listener;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.spawn.model.SpawnPoint;
import net.mineacle.core.spawn.service.SpawnService;
import net.mineacle.core.spawn.service.SpawnTeleportService;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;

public final class SpawnGuiListener implements Listener {

    private final SpawnService spawnService;
    private final SpawnTeleportService teleportService;

    public SpawnGuiListener(SpawnService spawnService, SpawnTeleportService teleportService) {
        this.spawnService = spawnService;
        this.teleportService = teleportService;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        String title = ChatColor.stripColor(event.getView().getTitle());
        String spawnTitle = ChatColor.stripColor(spawnService.title());

        if (title == null || spawnTitle == null || !title.equals(spawnTitle)) {
            return;
        }

        event.setCancelled(true);

        int slot = event.getRawSlot();
        int topSize = event.getView().getTopInventory().getSize();

        if (slot < 0 || slot >= topSize) {
            return;
        }

        if (spawnService.randomEnabled() && slot == spawnService.randomSlot()) {
            handleRandom(player);
            return;
        }

        SpawnPoint point = spawnService.spawnPointBySlot(slot);

        if (point == null) {
            return;
        }

        handleSpawnClick(player, point);
    }

    private void handleSpawnClick(Player player, SpawnPoint point) {
        if (spawnService.isCurrentWorld(player, point)) {
            String message = spawnService.message("already-here")
                    .replace("%spawn%", TextColor.color(point.displayName()));

            player.sendActionBar(actionBar(message));
            player.sendMessage(message);
            player.closeInventory();
            return;
        }

        if (spawnService.location(point) == null) {
            String message = spawnService.message("world-missing")
                    .replace("%world%", point.worldName())
                    .replace("%spawn%", TextColor.color(point.displayName()));

            player.sendActionBar(actionBar(message));
            player.sendMessage(message);
            player.closeInventory();
            return;
        }

        player.closeInventory();
        teleportService.begin(player, point);
    }

    private void handleRandom(Player player) {
        SpawnPoint point = spawnService.selectRandomPoint();

        if (point == null) {
            String message = spawnService.message("random-missing");

            player.sendActionBar(actionBar(message));
            player.sendMessage(message);
            player.closeInventory();
            return;
        }

        handleSpawnClick(player, point);
    }

    private Component actionBar(String message) {
        return LegacyComponentSerializer.legacySection().deserialize(TextColor.color(message));
    }
}