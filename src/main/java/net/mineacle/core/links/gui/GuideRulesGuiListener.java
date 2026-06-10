package net.mineacle.core.links.gui;

import net.mineacle.core.Core;
import net.mineacle.core.common.gui.MenuHistory;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.Locale;

public final class GuideRulesGuiListener implements Listener {

    private final Core core;

    public GuideRulesGuiListener(Core core) {
        this.core = core;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        String title = ChatColor.stripColor(event.getView().getTitle());

        if (title == null) {
            return;
        }

        String guideTitle = ChatColor.stripColor(GuideRulesGui.configuredTitle(GuideRulesGui.GUIDE_KEY));
        String rulesTitle = ChatColor.stripColor(GuideRulesGui.configuredTitle(GuideRulesGui.RULES_KEY));

        boolean guide = title.equalsIgnoreCase(guideTitle) || title.equalsIgnoreCase("Guide") || title.equalsIgnoreCase("Mineacle Guide");
        boolean rules = title.equalsIgnoreCase(rulesTitle) || title.equalsIgnoreCase("Rules") || title.equalsIgnoreCase("Server Rules");

        if (!guide && !rules) {
            return;
        }

        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }

        String menuKey = guide ? GuideRulesGui.GUIDE_KEY : GuideRulesGui.RULES_KEY;
        String action = GuideRulesGui.action(menuKey, event.getRawSlot());

        runAction(player, menuKey, action);
    }

    private void runAction(Player player, String currentMenu, String rawAction) {
        if (rawAction == null || rawAction.isBlank()) {
            return;
        }

        String action = rawAction.trim();
        String lower = action.toLowerCase(Locale.ROOT);

        if (lower.equals("close")) {
            player.closeInventory();
            return;
        }

        if (lower.equals("guide") || lower.equals("open:guide")) {
            MenuHistory.openChild(core, player,
                    () -> openCurrent(player, currentMenu),
                    () -> GuideRulesGui.openGuide(player));
            return;
        }

        if (lower.equals("rules") || lower.equals("open:rules")) {
            MenuHistory.openChild(core, player,
                    () -> openCurrent(player, currentMenu),
                    () -> GuideRulesGui.openRules(player));
            return;
        }

        if (lower.startsWith("command:")) {
            String command = action.substring("command:".length()).trim();

            if (command.startsWith("/")) {
                command = command.substring(1);
            }

            player.closeInventory();
            player.performCommand(command);
            return;
        }

        if (action.startsWith("/")) {
            player.closeInventory();
            player.performCommand(action.substring(1));
        }
    }

    private void openCurrent(Player player, String currentMenu) {
        if (GuideRulesGui.RULES_KEY.equalsIgnoreCase(currentMenu)) {
            GuideRulesGui.openRules(player);
            return;
        }

        GuideRulesGui.openGuide(player);
    }
}
