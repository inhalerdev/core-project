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

        String homesTitle = plainTitle("homes.gui.title");
        String deleteTitle = plainTitle("homes.gui.delete-title");
        String teamDeleteTitle = plainTitle("homes.gui.team-delete-title");
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
                sendPopup(player, core.getMessage("homes.not-set")
                        .replace("%home%", homeService.getDisplayName(uuid, id)));
                SoundService.guiError(player, core);
                return;
            }

            SoundService.guiSelect(player, core);
            player.closeInventory();
            teleportService.begin(player, homeService.getDisplayName(uuid, id), () -> {
                player.teleport(target);
                sendPopup(player, core.getMessage("homes.teleported")
                        .replace("%home%", homeService.getDisplayName(uuid, id)));
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
        sendPopup(player, core.getMessage("homes.set")
                .replace("%home%", homeService.getDisplayName(uuid, id)));
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
            sendPopup(player, core.getMessage("homes.set")
                    .replace("%home%", homeService.getDisplayName(uuid, id)));
            SoundService.homeSet(player, core);
            HomesMainGui.open(core, player, homeService);
            return;
        }

        SoundService.guiClick(player, core);
        player.setMetadata(META_HOME_PENDING, new FixedMetadataValue(core, id));
        player.setMetadata(META_HOME_CONFIRM, new FixedMetadataValue(core, 0));
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
                SoundService.guiError(player, core);
                return;
            }

            if (!homeService.canSetTeamHomeHere(player)) {
                sendBlockedTeamHomeWorld(player);
                return;
            }

            teamHomeService.setTeamHome(team.teamId(), player.getLocation());
            sendPopup(player, "&#bbbbbbTeam Home set to your current location");
            SoundService.homeSet(player, core);
            HomesMainGui.open(core, player, homeService);
            return;
        }

        if (slot == bannerSlot) {
            Location home = teamHomeService.getTeamHome(team.teamId());

            if (home == null) {
                SoundService.guiError(player, core);
                return;
            }

            SoundService.guiSelect(player, core);
            player.closeInventory();
            teleportService.begin(player, "Team Home", () -> {
                player.teleport(home);
                sendPopup(player, "&#bbbbbbTeleported to &dTeam Home");
            });
            return;
        }

        if (slot == dyeSlot && isFounder) {
            SoundService.guiClick(player, core);
            player.setMetadata(META_TEAM_HOME_PENDING, new FixedMetadataValue(core, team.teamId()));
            player.setMetadata(META_TEAM_HOME_CONFIRM, new FixedMetadataValue(core, false));
            ConfirmDeleteHomeGui.openTeamDelete(core, player);
            return;
        }

        if (slot == dyeSlot) {
            sendPopup(player, "&cOnly the founder can delete Team Home");
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
            sendPopup(player, "&cTeam home delete cancelled");
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
            sendPopup(player, "&#bbbbbbClick confirm again to continue");
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
                    sendPopup(player, "&cAction timed out");
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
            sendPopup(player, "&cYour team does not have a home set");
            SoundService.guiError(player, core);
            HomesMainGui.open(core, player, homeService);
            return;
        }

        clearTeamHomeDeleteMeta(player);
        player.closeInventory();
        sendPopup(player, "&cTeam Home deleted");
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
            sendPopup(player, core.getMessage("homes.delete-cancelled"));
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
            sendPopup(player, core.getMessage("homes.deleted").replace("%home%", displayName));
            SoundService.homeDelete(player, core);
            return;
        }

        player.setMetadata(META_HOME_CONFIRM, new FixedMetadataValue(core, id));
        sendPopup(player, "&#bbbbbbClick confirm again to continue");
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
                sendPopup(player, "&cAction timed out");
                SoundService.guiError(player, core);
            }
        }, 20L * Math.max(1, timeout));
    }

    private void sendBlockedHomeWorld(Player player) {
        sendPopup(player, core.getMessage("homes.blocked-world"));
        SoundService.guiError(player, core);
    }

    private void sendBlockedTeamHomeWorld(Player player) {
        sendPopup(player, core.getMessage("homes.blocked-team-home-world"));
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
        player.sendMessage(format(core.getMessage("homes.upgrade-line-1")));
        player.sendMessage(" ");
        player.sendMessage(format(core.getMessage("homes.upgrade-line-2")));
        player.sendMessage(" ");
        SoundService.guiError(player, core);
    }

    private void sendCreateTeamPrompt(Player player) {
        player.sendMessage(format("&cYou are not in a team"));
        Component clickable = component("&#bbbbbbType &d/team create &#bbbbbbto create a team")
                .clickEvent(ClickEvent.suggestCommand("/team create "));
        player.sendMessage(clickable);
    }

    private void sendPopup(Player player, String message) {
        String formatted = format(message);
        player.sendActionBar(component(formatted));
        player.sendMessage(formatted);
    }

    private String format(String message) {
        return TextColor.color(stripTrailingPeriod(message));
    }

    private String plainTitle(String path) {
        return TextColor.strip(core.getMessage(path));
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

    private Component component(String message) {
        return LegacyComponentSerializer.legacySection().deserialize(TextColor.color(message));
    }
}
