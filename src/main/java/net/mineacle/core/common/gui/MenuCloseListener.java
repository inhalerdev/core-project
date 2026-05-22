package net.mineacle.core.common.gui;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.plugin.Plugin;

public final class MenuCloseListener implements Listener {

    private final Plugin plugin;

    public MenuCloseListener(Plugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }

        String title = ChatColor.stripColor(event.getView().getTitle());

        if (title == null || !isMineacleMenu(title)) {
            return;
        }

        MenuHistory.handleClose(plugin, player);
    }

    private boolean isMineacleMenu(String title) {
        return isHomesMenu(title)
                || isTeamsMenu(title)
                || isTpaMenu(title)
                || isStatsMenu(title)
                || isBalTopMenu(title)
                || isSpawnMenu(title)
                || isRtpMenu(title)
                || isPlusMenu(title)
                || isAdminMenu(title)
                || isSellMenu(title)
                || isOrdersMenu(title)
                || isBountyMenu(title);
    }

    private boolean isHomesMenu(String title) {
        return title.equalsIgnoreCase("Homes")
                || title.equalsIgnoreCase("Delete Home")
                || title.equalsIgnoreCase("Delete Team Home")
                || title.equalsIgnoreCase("Overwrite Home")
                || title.equalsIgnoreCase("Rename Home");
    }

    private boolean isTeamsMenu(String title) {
        return title.equalsIgnoreCase("Team Menu")
                || title.equalsIgnoreCase("Team Invites")
                || title.equalsIgnoreCase("Team Invite")
                || title.equalsIgnoreCase("Confirm Action")
                || title.equalsIgnoreCase("Disband Team")
                || title.equalsIgnoreCase("Leave Team")
                || title.equalsIgnoreCase("Kick Player")
                || title.equalsIgnoreCase("Ban Player")
                || title.equalsIgnoreCase("Promote Player")
                || title.equalsIgnoreCase("Demote Player")
                || title.equalsIgnoreCase("Transfer Founder")
                || title.equalsIgnoreCase("Delete Team Home")
                || title.equalsIgnoreCase("Team Info")
                || title.startsWith("Team:")
                || title.startsWith("Member:")
                || isTeamMainMenu(title);
    }

    private boolean isTpaMenu(String title) {
        return title.equalsIgnoreCase("Teleport Request");
    }

    private boolean isStatsMenu(String title) {
        return title.endsWith(" Stats");
    }

    private boolean isBalTopMenu(String title) {
        return title.startsWith("Balance Top (Page ");
    }

    private boolean isSpawnMenu(String title) {
        return title.equalsIgnoreCase("Spawn");
    }

    private boolean isRtpMenu(String title) {
        return title.equalsIgnoreCase("Random Teleport")
                || title.equalsIgnoreCase("Origins RTP");
    }

    private boolean isPlusMenu(String title) {
        return title.equalsIgnoreCase("Mineacle+") || title.equalsIgnoreCase("Mineacle Plus");
    }

    private boolean isAdminMenu(String title) {
        return title.equalsIgnoreCase("Admin Panel") || title.startsWith("Admin:");
    }

    private boolean isSellMenu(String title) {
        return title.equalsIgnoreCase("Place Items In Here To Sell")
                || title.equalsIgnoreCase("Sell")
                || title.equalsIgnoreCase("Sell Multipliers")
                || title.startsWith("Sell History");
    }

    private boolean isOrdersMenu(String title) {
        return title.startsWith("Orders (Page ")
                || title.equalsIgnoreCase("My Orders")
                || title.startsWith("Choose Item (Page ")
                || title.equalsIgnoreCase("Confirm Delivery")
                || title.equalsIgnoreCase("Confirm Cancel");
    }

    private boolean isBountyMenu(String title) {
        return title.startsWith("Bounties (Page ")
                || title.equalsIgnoreCase("Place Bounty");
    }

    private boolean isTeamMainMenu(String title) {
        if (title.startsWith("Balance Top")) {
            return false;
        }

        if (title.endsWith(" Stats")) {
            return false;
        }

        return title.matches(".+ \\(\\d+/\\d+\\)");
    }
}
