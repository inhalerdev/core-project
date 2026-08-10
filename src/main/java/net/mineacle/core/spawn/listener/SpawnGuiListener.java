package net.mineacle.core.spawn.listener;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.spawn.model.SpawnPoint;
import net.mineacle.core.spawn.service.SpawnService;
import net.mineacle.core.spawn.service.SpawnTeleportService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;

@SuppressWarnings("unused")
public final class SpawnGuiListener
        implements Listener {

    private final SpawnService spawnService;
    private final SpawnTeleportService teleportService;

    public SpawnGuiListener(
            SpawnService spawnService,
            SpawnTeleportService teleportService
    ) {
        this.spawnService = spawnService;
        this.teleportService = teleportService;
    }

    @EventHandler
    public void onInventoryClick(
            InventoryClickEvent event
    ) {
        if (!(event.getWhoClicked()
                instanceof Player player)) {
            return;
        }

        String title =
                PlainTextComponentSerializer
                        .plainText()
                        .serialize(
                                event.getView().title()
                        );
        String expected =
                TextColor.strip(
                        spawnService.title()
                );

        if (expected == null
                || !title.equals(expected)) {
            return;
        }

        event.setCancelled(true);

        int slot = event.getRawSlot();
        int topSize = event.getView()
                .getTopInventory()
                .getSize();

        if (slot < 0 || slot >= topSize) {
            return;
        }

        if (spawnService.randomEnabled()
                && slot
                == spawnService.randomSlot()) {
            SpawnPoint point =
                    spawnService
                            .selectRandomPoint();

            if (point == null) {
                String message =
                        spawnService.message(
                                "random-missing"
                        );
                player.sendMessage(message);
                SoundService.guiError(
                        player,
                        spawnService.core()
                );
                return;
            }

            startTeleport(
                    player,
                    point
            );
            return;
        }

        SpawnPoint point =
                spawnService
                        .spawnPointBySlot(slot);

        if (point != null) {
            startTeleport(
                    player,
                    point
            );
        }
    }

    private void startTeleport(
            Player player,
            SpawnPoint point
    ) {
        SoundService.guiSelect(
                player,
                spawnService.core()
        );
        player.closeInventory();
        teleportService.begin(
                player,
                point
        );
    }
}
