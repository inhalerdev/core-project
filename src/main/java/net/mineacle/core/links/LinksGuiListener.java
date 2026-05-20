package net.mineacle.core.links;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

public final class LinksGuiListener implements Listener {

    private final Core core;
    private final LinksService service;

    public LinksGuiListener(Core core, LinksService service) {
        this.core = core;
        this.service = service;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        String title = ChatColor.stripColor(event.getView().getTitle());

        if (!LinksGui.isTitle(title, service)) {
            return;
        }

        event.setCancelled(true);
        event.setResult(org.bukkit.event.Event.Result.DENY);

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        int slot = event.getRawSlot();
        int topSize = event.getView().getTopInventory().getSize();

        if (slot < 0 || slot >= topSize) {
            return;
        }

        LinkItem item = service.itemAt(slot);

        if (item == null || item.url() == null || item.url().isBlank()) {
            return;
        }

        SoundService.guiClick(player, core);
        player.closeInventory();
        sendPrompt(player, item);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        String title = ChatColor.stripColor(event.getView().getTitle());

        if (!LinksGui.isTitle(title, service)) {
            return;
        }

        event.setCancelled(true);
        event.setResult(org.bukkit.event.Event.Result.DENY);
    }

    private void sendPrompt(Player player, LinkItem item) {
        if (service.blankLines()) {
            player.sendMessage(Component.empty());
        }

        if (item.promptHeader() != null && !item.promptHeader().isBlank()) {
            player.sendMessage(TextColor.color(item.promptHeader()));
        }

        for (String line : item.promptLines()) {
            player.sendMessage(TextColor.color(line));
        }

        player.sendMessage(clickLine(item.clickText(), item.url(), item.hover()));

        if (service.fallbackUrl()) {
            player.sendMessage(TextColor.color(item.fallbackColor() + item.url()));
        }

        if (service.blankLines()) {
            player.sendMessage(Component.empty());
        }
    }

    private Component clickLine(String text, String url, String hover) {
        return LegacyComponentSerializer.legacySection().deserialize(TextColor.color(text))
                .clickEvent(ClickEvent.openUrl(url))
                .hoverEvent(HoverEvent.showText(
                        LegacyComponentSerializer.legacySection().deserialize(TextColor.color(hover))
                ));
    }
}
