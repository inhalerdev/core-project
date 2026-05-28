package net.mineacle.core.guide.listener;

import net.mineacle.core.Core;
import net.mineacle.core.common.gui.MenuHistory;
import net.mineacle.core.guide.gui.GuideMenuHolder;
import net.mineacle.core.guide.service.GuideMenuService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

import java.util.List;
import java.util.Locale;

public final class GuideMenuListener implements Listener {

    private final Core core;
    private final GuideMenuService service;

    public GuideMenuListener(Core core, GuideMenuService service) {
        this.core = core;
        this.service = service;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        if (!(event.getInventory().getHolder() instanceof GuideMenuHolder holder)) {
            return;
        }

        event.setCancelled(true);

        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getInventory()) {
            return;
        }

        List<String> commands = holder.commands(event.getRawSlot());

        if (commands.isEmpty()) {
            return;
        }

        if (holder.menuKey().equalsIgnoreCase("guide") && opensRules(commands)) {
            MenuHistory.openChild(
                    core,
                    player,
                    () -> service.open(player, "guide"),
                    () -> service.open(player, "rules")
            );
            return;
        }

        service.execute(player, commands);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof GuideMenuHolder) {
            event.setCancelled(true);
        }
    }

    private boolean opensRules(List<String> commands) {
        for (String raw : commands) {
            if (raw == null) {
                continue;
            }

            String command = raw.trim().toLowerCase(Locale.ROOT);

            if (command.startsWith("[player]")) {
                command = command.substring("[player]".length()).trim();
            }

            while (command.startsWith("/")) {
                command = command.substring(1);
            }

            if (command.equals("rules")) {
                return true;
            }
        }

        return false;
    }
}
