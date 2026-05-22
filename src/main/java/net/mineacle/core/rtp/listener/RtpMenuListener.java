package net.mineacle.core.rtp.listener;

import net.mineacle.core.Core;
import net.mineacle.core.common.gui.MenuHistory;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.rtp.gui.RtpMenuGui;
import net.mineacle.core.rtp.service.OriginRtpQueueService;
import net.mineacle.core.rtp.service.RtpMenuItem;
import net.mineacle.core.rtp.service.RtpMenuService;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Locale;

public final class RtpMenuListener implements Listener {

    private final Core core;
    private final RtpMenuService menuService;
    private final OriginRtpQueueService queueService;

    public RtpMenuListener(Core core, RtpMenuService menuService, OriginRtpQueueService queueService) {
        this.core = core;
        this.menuService = menuService;
        this.queueService = queueService;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        String title = ChatColor.stripColor(event.getView().getTitle());

        if (!RtpMenuGui.isMain(title, menuService) && !RtpMenuGui.isOrigins(title, menuService)) {
            return;
        }

        event.setCancelled(true);

        int slot = event.getRawSlot();
        int topSize = event.getView().getTopInventory().getSize();

        if (slot < 0 || slot >= topSize) {
            return;
        }

        if (RtpMenuGui.isMain(title, menuService)) {
            handleMain(player, slot);
            return;
        }

        handleOrigins(player, slot);
    }

    private void handleMain(Player player, int slot) {
        RtpMenuItem item = menuService.itemAt(RtpMenuGui.MAIN, slot);

        if (item == null) {
            return;
        }

        if (item.key().equalsIgnoreCase("origins")) {
            SoundService.guiClick(player, core);
            MenuHistory.openChild(
                    core,
                    player,
                    () -> RtpMenuGui.open(player, menuService, RtpMenuGui.MAIN),
                    () -> RtpMenuGui.open(player, menuService, RtpMenuGui.ORIGINS)
            );
            return;
        }

        if (item.key().equalsIgnoreCase("trials")) {
            SoundService.guiClick(player, core);
            sendTrials(player, item);
        }
    }

    private void handleOrigins(Player player, int slot) {
        RtpMenuItem item = menuService.itemAt(RtpMenuGui.ORIGINS, slot);

        if (item == null || item.destination() == null || item.destination().isBlank()) {
            return;
        }

        SoundService.guiClick(player, core);
        MenuHistory.openWithoutBackTrigger(core, player, player::closeInventory);
        queueService.request(player, item.destination());
    }

    private void sendTrials(Player player, RtpMenuItem item) {
        String type = item.actionType() == null ? "" : item.actionType().toUpperCase(Locale.ROOT);

        if (type.equals("BUNGEE_SERVER")) {
            if (connectBungee(player, item.actionServer())) {
                player.sendMessage(TextColor.color(menuService.message("trials-connecting", "&#bbbbbbSending you to &cTrials")));
                MenuHistory.openWithoutBackTrigger(core, player, player::closeInventory);
                return;
            }

            runFallback(player, item);
            return;
        }

        if (type.equals("PLAYER_COMMAND")) {
            player.performCommand(parseCommand(player, item.fallbackCommand()));
            MenuHistory.openWithoutBackTrigger(core, player, player::closeInventory);
            return;
        }

        if (type.equals("CONSOLE_COMMAND")) {
            core.getServer().dispatchCommand(core.getServer().getConsoleSender(), parseCommand(player, item.fallbackCommand()));
            MenuHistory.openWithoutBackTrigger(core, player, player::closeInventory);
            return;
        }

        runFallback(player, item);
    }

    private void runFallback(Player player, RtpMenuItem item) {
        if (item.fallbackCommand() == null || item.fallbackCommand().isBlank()) {
            player.sendMessage(TextColor.color(menuService.message("trials-failed", "&cCould not send you to Trials")));
            SoundService.guiError(player, core);
            return;
        }

        core.getServer().dispatchCommand(core.getServer().getConsoleSender(), parseCommand(player, item.fallbackCommand()));
        player.sendMessage(TextColor.color(menuService.message("trials-connecting", "&#bbbbbbSending you to &cTrials")));
        MenuHistory.openWithoutBackTrigger(core, player, player::closeInventory);
    }

    private boolean connectBungee(Player player, String server) {
        if (server == null || server.isBlank()) {
            return false;
        }

        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);

            output.writeUTF("Connect");
            output.writeUTF(server);
            player.sendPluginMessage(core, "BungeeCord", bytes.toByteArray());
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    private String parseCommand(Player player, String command) {
        return command
                .replace("%player%", player.getName())
                .replace("%player_name%", player.getName())
                .replace("/", "");
    }
}
