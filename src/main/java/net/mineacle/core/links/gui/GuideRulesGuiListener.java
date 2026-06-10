package net.mineacle.core.links.gui;

import net.mineacle.core.Core;
import net.mineacle.core.common.gui.MenuHistory;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;

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

        boolean guide = GuideRulesGui.GUIDE_TITLE.equalsIgnoreCase(title);
        boolean rules = GuideRulesGui.RULES_TITLE.equalsIgnoreCase(title);

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

        int slot = event.getRawSlot();

        if (guide && slot == 22) {
            MenuHistory.openChild(core, player,
                    () -> GuideRulesGui.openGuide(player),
                    () -> GuideRulesGui.openRules(player));
            return;
        }

        if (rules && slot == 22) {
            MenuHistory.openChild(core, player,
                    () -> GuideRulesGui.openRules(player),
                    () -> GuideRulesGui.openGuide(player));
        }
    }
}
