package net.mineacle.core.links.gui;

import net.mineacle.core.Core;
import net.mineacle.core.common.gui.MenuHistory;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.links.service.GuideRulesService;
import net.mineacle.core.links.service.LinksService;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

import java.util.Locale;

public final class GuideRulesGuiListener
        implements Listener {

    private final Core core;
    private final GuideRulesGui gui;
    private final LinksService linksService;

    public GuideRulesGuiListener(
            Core core,
            GuideRulesGui gui,
            LinksService linksService
    ) {
        this.core = core;
        this.gui = gui;
        this.linksService = linksService;
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = false
    )
    public void onClick(InventoryClickEvent event) {
        GuideRulesGui.Holder holder =
                gui.holder(
                        event.getView().getTopInventory()
                );

        if (holder == null) {
            return;
        }

        event.setCancelled(true);
        event.setResult(Event.Result.DENY);

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        int rawSlot = event.getRawSlot();
        int topSize = event.getView()
                .getTopInventory()
                .getSize();

        if (rawSlot < 0 || rawSlot >= topSize) {
            return;
        }

        String action = holder.action(rawSlot);

        if (action == null || action.isBlank()) {
            return;
        }

        SoundService.guiClick(player, core);
        runAction(player, holder.menuKey(), action);
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = false
    )
    public void onDrag(InventoryDragEvent event) {
        if (gui.isInventory(
                event.getView().getTopInventory()
        )) {
            event.setCancelled(true);
            event.setResult(Event.Result.DENY);
        }
    }

    private void runAction(
            Player player,
            String currentMenu,
            String rawAction
    ) {
        String action = rawAction.trim();
        String lower = action.toLowerCase(Locale.ROOT);

        if (lower.equals("close")) {
            MenuHistory.close(core, player);
            return;
        }

        if (lower.equals("guide")
                || lower.equals("open:guide")) {
            openChild(
                    player,
                    currentMenu,
                    GuideRulesService.GUIDE
            );
            return;
        }

        if (lower.equals("rules")
                || lower.equals("open:rules")) {
            openChild(
                    player,
                    currentMenu,
                    GuideRulesService.RULES
            );
            return;
        }

        if (lower.startsWith("link:")) {
            String linkKey = action.substring(
                    "link:".length()
            ).trim();

            MenuHistory.close(core, player);
            core.getServer().getScheduler().runTask(
                    core,
                    () -> linksService.sendLink(
                            player,
                            linkKey
                    )
            );
            return;
        }

        if (lower.startsWith("command:")) {
            runCommand(
                    player,
                    action.substring(
                            "command:".length()
                    )
            );
            return;
        }

        if (action.startsWith("/")) {
            runCommand(player, action);
        }
    }

    private void openChild(
            Player player,
            String currentMenu,
            String targetMenu
    ) {
        if (targetMenu.equalsIgnoreCase(currentMenu)) {
            return;
        }

        MenuHistory.openChild(
                core,
                player,
                () -> gui.open(player, currentMenu),
                () -> gui.open(player, targetMenu)
        );
    }

    private void runCommand(
            Player player,
            String rawCommand
    ) {
        String command = rawCommand == null
                ? ""
                : rawCommand.trim();

        while (command.startsWith("/")) {
            command = command.substring(1);
        }

        if (command.isBlank()) {
            return;
        }

        String finalCommand = command;
        MenuHistory.close(core, player);

        core.getServer().getScheduler().runTask(
                core,
                () -> {
                    if (player.isOnline()) {
                        player.performCommand(finalCommand);
                    }
                }
        );
    }
}
