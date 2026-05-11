package net.mineacle.core.homes.listener;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.homes.gui.ConfirmDeleteHomeGui;
import net.mineacle.core.homes.gui.HomesMainGui;
import net.mineacle.core.homes.service.HomeService;
import net.mineacle.core.homes.service.HomeWorldRules;
import net.mineacle.core.homes.service.TeleportService;
import net.mineacle.core.teams.model.TeamRecord;
import net.mineacle.core.teams.service.TeamHomeService;
import net.mineacle.core.teams.service.TeamService;
import org.bukkit.Location;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.metadata.FixedMetadataValue;

import java.util.UUID;

public final class HomesGuiListener implements Listener {

    private static final String META_HOME_PENDING = "mh_pendingDelete";
    private static final String META_HOME_CONFIRM = "mh_deleteConfirm";

    private static final String META_TEAM_HOME_PENDING = "mh_teamHomePending";
    private static final String META_TEAM_HOME_CONFIRM = "mh_teamHomeConfirm";

    private final Core core;
    private final HomeService homeService;
    private final HomeWorldRules worldRules;
    private final TeleportService teleportService;

    public HomesGuiListener(Core core, HomeService homeService, HomeWorldRules worldRules, TeleportService teleportService) {
        this.core = core;
        this.homeService = homeService;
        this.worldRules = worldRules;
        this.teleportService = teleportService;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        HumanEntity whoClicked = event.getWhoClicked();

        if (!(whoClicked instanceof Player player)) {
            return;
        }

        String homesTitle = org.bukkit.ChatColor.translateAlternateColorCodes('&', core.getMessage("homes.gui.title"));
        String deleteTitle = org.bukkit.ChatColor.translateAlternateColorCodes('&', core.getMessage("homes.gui.delete-title"));
        String teamDeleteTitle = org.bukkit.ChatColor.translateAlternateColorCodes('&', core.getMessage("homes.gui.team-delete-title"));

        int slot = event.getRawSlot();

        if (event.getView().getTitle().equals(homesTitle)) {
            event.setCancelled(true);

            if (slot < 0 || slot >= event.getInventory().getSize()) {
                return;
            }

            for (int i = 0; i < HomesMainGui.BED_SLOTS.length; i++) {
                if (slot == HomesMainGui.BED_SLOTS[i]) {
                    handleHomeBedClick(player, i + 1);
                    return;
                }
            }

            for (int i = 0; i < HomesMainGui.DYE_SLOTS.length; i++) {
                if (slot == HomesMainGui.DYE_SLOTS[i]) {
                    handleHomeDyeClick(player, i + 1);
                    return;
                }
            }

            handleTeamHomeClick(player, slot);
            return;
        }

        if (event.getView().getTitle().equals(deleteTitle)) {
            event.setCancelled(true);

            handlePlayerDeleteConfirm(player, slot);
            return;
        }

        if (event.getView().getTitle().equals(teamDeleteTitle)) {
            event.setCancelled(true);

            handleTeamHomeDeleteConfirm(player, slot);
        }
    }

    private void handleHomeBedClick(Player player, int id) {
        UUID uuid = player.getUniqueId();

        if (homeService.exists(uuid, id)) {
            Location target = homeService.get(uuid, id);

            if (target == null) {
                String message = stripTrailingPeriod(core.getMessage("homes.not-set")
                        .replace("%home%", homeService.getDisplayName(uuid, id)));

                player.sendActionBar(actionBar(message));
                player.sendMessage(message);
                SoundService.guiError(player, core);
                return;
            }

            player.closeInventory();
            SoundService.guiConfirm(player, core);

            teleportService.begin(player, homeService.getDisplayName(uuid, id), () -> {
                player.teleport(target);

                String message = stripTrailingPeriod(core.getMessage("homes.teleported")
                        .replace("%home%", homeService.getDisplayName(uuid, id)));

                player.sendActionBar(actionBar(message));
                player.sendMessage(message);
            });

            return;
        }

        if (!homeService.hasFreeHomeCapacity(player)) {
            sendUpgradeMessage(player);
            return;
        }

        if (!homeService.canSetPersonalHomeHere(player)) {
            sendBlockedHomeWorld(player);
            return;
        }

        homeService.set(uuid, id, player.getLocation(), homeService.getDefaultDisplayName(id));

        String message = stripTrailingPeriod(core.getMessage("homes.set")
                .replace("%home%", homeService.getDisplayName(uuid, id)));

        player.sendActionBar(actionBar(message));
        player.sendMessage(message);
        SoundService.homeSet(player, core);

        HomesMainGui.open(core, player, homeService);
    }

    private void handleHomeDyeClick(Player player, int id) {
        UUID uuid = player.getUniqueId();

        if (!homeService.exists(uuid, id)) {
            if (!homeService.hasFreeHomeCapacity(player)) {
                sendUpgradeMessage(player);
                return;
            }

            if (!homeService.canSetPersonalHomeHere(player)) {
                sendBlockedHomeWorld(player);
                return;
            }

            homeService.set(uuid, id, player.getLocation(), homeService.getDefaultDisplayName(id));

            String message = stripTrailingPeriod(core.getMessage("homes.set")
                    .replace("%home%", homeService.getDisplayName(uuid, id)));

            player.sendActionBar(actionBar(message));
            player.sendMessage(message);
            SoundService.homeSet(player, core);

            HomesMainGui.open(core, player, homeService);
            return;
        }

        player.setMetadata(META_HOME_PENDING, new FixedMetadataValue(core, id));
        player.setMetadata(META_HOME_CONFIRM, new FixedMetadataValue(core, 0));

        SoundService.guiClick(player, core);
        ConfirmDeleteHomeGui.openPlayerDelete(core, player, id, homeService.getDisplayName(uuid, id));
    }

    private void handleTeamHomeClick(Player player, int slot) {
        int bannerSlot = core.getConfig().getInt("homes.team-home.banner-slot", 10);
        int dyeSlot = core.getConfig().getInt("homes.team-home.dye-slot", 19);

        if (slot != bannerSlot && slot != dyeSlot) {
            return;
        }

        TeamService teamService = new TeamService(core);
        TeamHomeService teamHomeService = new TeamHomeService(core, teamService);
        TeamRecord team = teamService.getTeamByPlayer(player.getUniqueId());

        if (team == null) {
            player.closeInventory();
            sendCreateTeamPrompt(player);
            SoundService.guiError(player, core);
            return;
        }

        boolean isAdmin = teamService.isAdmin(player.getUniqueId());
        boolean isFounder = teamService.isFounder(player.getUniqueId());
        boolean hasHome = teamHomeService.hasTeamHome(team.teamId());

        if (!hasHome) {
            if (!isAdmin) {
                player.closeInventory();

                String message = "§7Ask your §dteam founder §7to set Team Home";
                player.sendActionBar(actionBar(message));
                player.sendMessage(message);
                SoundService.guiError(player, core);
                return;
            }

            if (!homeService.canSetTeamHomeHere(player)) {
                sendBlockedTeamHomeWorld(player);
                return;
            }

            teamHomeService.setTeamHome(team.teamId(), player.getLocation());

            String message = "§7Team Home set to your current location";
            player.sendActionBar(actionBar(message));
            player.sendMessage(message);
            SoundService.homeSet(player, core);

            HomesMainGui.open(core, player, homeService);
            return;
        }

        if (slot == bannerSlot) {
            Location home = teamHomeService.getTeamHome(team.teamId());

            if (home == null) {
                String message = "§cYour team does not have a home set";
                player.sendActionBar(actionBar(message));
                player.sendMessage(message);
                SoundService.guiError(player, core);
                return;
            }

            player.closeInventory();
            SoundService.guiConfirm(player, core);

            teleportService.begin(player, "Team Home", () -> {
                player.teleport(home);

                String message = "§7Teleported to §dTeam Home";
                player.sendActionBar(actionBar(message));
                player.sendMessage(message);
            });

            return;
        }

        if (slot == dyeSlot && isFounder) {
            player.setMetadata(META_TEAM_HOME_PENDING, new FixedMetadataValue(core, team.teamId()));
            player.setMetadata(META_TEAM_HOME_CONFIRM, new FixedMetadataValue(core, false));
            SoundService.guiClick(player, core);
            ConfirmDeleteHomeGui.openTeamDelete(core, player);
            return;
        }

        if (slot == dyeSlot) {
            String message = "§cOnly the founder can delete Team Home";
            player.sendActionBar(actionBar(message));
            player.sendMessage(message);
            SoundService.guiError(player, core);
        }
    }

    private void handleTeamHomeDeleteConfirm(Player player, int slot) {
        if (!player.hasMetadata(META_TEAM_HOME_PENDING)) {
            player.closeInventory();
            SoundService.guiError(player, core);
            return;
        }

        String teamId = player.getMetadata(META_TEAM_HOME_PENDING).get(0).asString();

        boolean confirmed = player.hasMetadata(META_TEAM_HOME_CONFIRM)
                && player.getMetadata(META_TEAM_HOME_CONFIRM).get(0).asBoolean();

        if (slot == ConfirmDeleteHomeGui.CANCEL_SLOT) {
            clearTeamHomeDeleteMeta(player);
            player.closeInventory();
            HomesMainGui.open(core, player, homeService);

            String message = "§cTeam home delete cancelled";
            player.sendActionBar(actionBar(message));
            player.sendMessage(message);
            SoundService.guiCancel(player, core);
            return;
        }

        if (slot == ConfirmDeleteHomeGui.ACTION_SLOT) {
            return;
        }

        if (slot != ConfirmDeleteHomeGui.CONFIRM_SLOT) {
            return;
        }

        if (!confirmed) {
            player.setMetadata(META_TEAM_HOME_CONFIRM, new FixedMetadataValue(core, true));

            String message = "§7Click confirm again to continue";
            player.sendActionBar(actionBar(message));
            player.sendMessage(message);
            SoundService.guiConfirm(player, core);

            int timeout = core.getConfig().getInt("homes.delete-confirm.timeout-seconds", 5);

            core.getServer().getScheduler().runTaskLater(core, () -> {
                if (!player.isOnline()) {
                    return;
                }

                if (!player.hasMetadata(META_TEAM_HOME_PENDING) || !player.hasMetadata(META_TEAM_HOME_CONFIRM)) {
                    return;
                }

                String currentTeamId = player.getMetadata(META_TEAM_HOME_PENDING).get(0).asString();
                boolean currentConfirmed = player.getMetadata(META_TEAM_HOME_CONFIRM).get(0).asBoolean();

                if (currentTeamId.equals(teamId) && currentConfirmed) {
                    player.setMetadata(META_TEAM_HOME_CONFIRM, new FixedMetadataValue(core, false));

                    String timeoutMessage = "§cAction timed out";
                    player.sendActionBar(actionBar(timeoutMessage));
                    player.sendMessage(timeoutMessage);
                    SoundService.guiError(player, core);
                }
            }, 20L * Math.max(1, timeout));

            return;
        }

        TeamService teamService = new TeamService(core);
        TeamHomeService teamHomeService = new TeamHomeService(core, teamService);

        if (!teamHomeService.deleteTeamHome(teamId)) {
            clearTeamHomeDeleteMeta(player);
            player.closeInventory();

            String message = "§cYour team does not have a home set";
            player.sendActionBar(actionBar(message));
            player.sendMessage(message);
            SoundService.guiError(player, core);

            HomesMainGui.open(core, player, homeService);
            return;
        }

        clearTeamHomeDeleteMeta(player);
        player.closeInventory();

        String message = "§cTeam Home deleted";
        player.sendActionBar(actionBar(message));
        player.sendMessage(message);
        SoundService.homeDelete(player, core);

        HomesMainGui.open(core, player, homeService);
    }

    private void handlePlayerDeleteConfirm(Player player, int slot) {
        if (!player.hasMetadata(META_HOME_PENDING)) {
            player.closeInventory();
            SoundService.guiError(player, core);
            return;
        }

        int id = player.getMetadata(META_HOME_PENDING).get(0).asInt();
        String displayName = homeService.getDisplayName(player.getUniqueId(), id);

        if (slot == ConfirmDeleteHomeGui.CANCEL_SLOT) {
            clearPlayerDeleteMeta(player);
            player.closeInventory();
            HomesMainGui.open(core, player, homeService);

            String message = stripTrailingPeriod(core.getMessage("homes.delete-cancelled"));

            player.sendActionBar(actionBar(message));
            player.sendMessage(message);
            SoundService.guiCancel(player, core);
            return;
        }

        if (slot == ConfirmDeleteHomeGui.ACTION_SLOT) {
            return;
        }

        if (slot != ConfirmDeleteHomeGui.CONFIRM_SLOT) {
            return;
        }

        int confirmValue = player.getMetadata(META_HOME_CONFIRM).get(0).asInt();

        if (confirmValue == id) {
            homeService.delete(player.getUniqueId(), id);
            clearPlayerDeleteMeta(player);
            player.closeInventory();

            String message = stripTrailingPeriod(core.getMessage("homes.deleted")
                    .replace("%home%", displayName));

            player.sendActionBar(actionBar(message));
            player.sendMessage(message);
            SoundService.homeDelete(player, core);
            return;
        }

        player.setMetadata(META_HOME_CONFIRM, new FixedMetadataValue(core, id));

        String message = "§7Click confirm again to continue";

        player.sendActionBar(actionBar(message));
        player.sendMessage(message);
        SoundService.guiConfirm(player, core);

        int timeout = core.getConfig().getInt("homes.delete-confirm.timeout-seconds", 5);

        core.getServer().getScheduler().runTaskLater(core, () -> {
            if (!player.isOnline()) {
                return;
            }

            if (!player.hasMetadata(META_HOME_PENDING) || !player.hasMetadata(META_HOME_CONFIRM)) {
                return;
            }

            int pendingId = player.getMetadata(META_HOME_PENDING).get(0).asInt();
            int currentConfirmValue = player.getMetadata(META_HOME_CONFIRM).get(0).asInt();

            if (pendingId == id && currentConfirmValue == id) {
                player.setMetadata(META_HOME_CONFIRM, new FixedMetadataValue(core, 0));

                String timeoutMessage = "§cAction timed out";

                player.sendActionBar(actionBar(timeoutMessage));
                player.sendMessage(timeoutMessage);
                SoundService.guiError(player, core);
            }
        }, 20L * Math.max(1, timeout));
    }

    private void sendBlockedHomeWorld(Player player) {
        String message = stripTrailingPeriod(core.getMessage("homes.blocked-world"));
        player.sendActionBar(actionBar(message));
        player.sendMessage(message);
        SoundService.guiError(player, core);
    }

    private void sendBlockedTeamHomeWorld(Player player) {
        String message = stripTrailingPeriod(core.getMessage("homes.blocked-team-home-world"));
        player.sendActionBar(actionBar(message));
        player.sendMessage(message);
        SoundService.guiError(player, core);
    }

    private void clearPlayerDeleteMeta(Player player) {
        player.removeMetadata(META_HOME_PENDING, core);
        player.removeMetadata(META_HOME_CONFIRM, core);
    }

    private void clearTeamHomeDeleteMeta(Player player) {
        player.removeMetadata(META_TEAM_HOME_PENDING, core);
        player.removeMetadata(META_TEAM_HOME_CONFIRM, core);
    }

    private void sendUpgradeMessage(Player player) {
        player.closeInventory();
        player.sendMessage(" ");
        player.sendMessage(stripTrailingPeriod(core.getMessage("homes.upgrade-line-1")));
        player.sendMessage(" ");
        player.sendMessage(stripTrailingPeriod(core.getMessage("homes.upgrade-line-2")));
        player.sendMessage(" ");
        SoundService.guiError(player, core);
    }

    private void sendCreateTeamPrompt(Player player) {
        player.sendMessage("§cYou are not in a team");

        Component clickable = Component.text("§7Type §d/team create <name> §7to create a team")
                .clickEvent(ClickEvent.suggestCommand("/team create "));

        player.sendMessage(clickable);
    }

    private String stripTrailingPeriod(String message) {
        if (message == null || message.isBlank()) {
            return "";
        }

        String output = message;

        while (output.endsWith(".")) {
            output = output.substring(0, output.length() - 1);
        }

        return output;
    }

    private Component actionBar(String message) {
        return LegacyComponentSerializer.legacySection().deserialize(TextColor.color(message));
    }
}