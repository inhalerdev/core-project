package net.mineacle.core.teams.listener;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.gui.MenuHistory;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.teleport.TeleportService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.homes.gui.HomesMainGui;
import net.mineacle.core.homes.service.HomeService;
import net.mineacle.core.stats.PlayerStatisticsGui;
import net.mineacle.core.teams.gui.TeamConfirmGui;
import net.mineacle.core.teams.gui.TeamInviteGui;
import net.mineacle.core.teams.gui.TeamMemberGui;
import net.mineacle.core.teams.gui.TeamStartGui;
import net.mineacle.core.teams.gui.TeamsMainGui;
import net.mineacle.core.teams.model.TeamMemberRecord;
import net.mineacle.core.teams.model.TeamRecord;
import net.mineacle.core.teams.model.TeamRole;
import net.mineacle.core.teams.service.TeamHomeService;
import net.mineacle.core.teams.service.TeamInviteService;
import net.mineacle.core.teams.service.TeamService;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.metadata.FixedMetadataValue;

import java.util.List;
import java.util.UUID;

public final class TeamsGuiListener implements Listener {

    private static final String META_TARGET = "simple_team_target";
    private static final String META_ACTION = "simple_team_action";
    private static final String META_CONFIRM = "simple_team_confirm";
    private static final String PRIMARY = "&#8436FE";
    private static final String SECONDARY = "&#B078FF";
    private static final String ACCENT = "&#D0AFFF";
    private static final String BODY = "&#bbbbbb";

    private final Core core;
    private final TeamService teamService;
    private final TeamInviteService inviteService;
    private final TeamHomeService teamHomeService;
    private final HomeService homeService;
    private final TeleportService teleportService;
    private final PlayerStatisticsGui playerStatisticsGui;

    public TeamsGuiListener(
            Core core,
            TeamService teamService,
            TeamInviteService inviteService,
            TeamHomeService teamHomeService,
            HomeService homeService,
            TeleportService teleportService,
            PlayerStatisticsGui playerStatisticsGui
    ) {
        this.core = core;
        this.teamService = teamService;
        this.inviteService = inviteService;
        this.teamHomeService = teamHomeService;
        this.homeService = homeService;
        this.teleportService = teleportService;
        this.playerStatisticsGui = playerStatisticsGui;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        int slot = event.getRawSlot();
        int topSize = event.getView().getTopInventory().getSize();

        if (slot < 0 || slot >= topSize) {
            return;
        }

        String title = ChatColor.stripColor(event.getView().getTitle());

        if (title == null) {
            return;
        }

        if (title.equals(ChatColor.stripColor(TeamStartGui.TITLE))) {
            event.setCancelled(true);
            handleStartClick(player, slot);
            return;
        }

        if (isTeamMainMenu(player, title)) {
            event.setCancelled(true);
            handleMainClick(player, slot);
            return;
        }

        if (title.equals(ChatColor.stripColor(TeamInviteGui.TITLE))) {
            event.setCancelled(true);
            handleInviteClick(player, slot);
            return;
        }

        if (title.startsWith(TeamMemberGui.TITLE_PREFIX)) {
            event.setCancelled(true);
            handleMemberClick(player, slot);
            return;
        }

        if (title.equals(ChatColor.stripColor(TeamConfirmGui.TITLE))) {
            event.setCancelled(true);
            handleConfirmClick(player, slot);
        }
    }

    /**
     * O(1) player/team lookup plus one team-member query. The previous code
     * scanned every online player and recomputed every matching team's size
     * for each inventory click.
     */
    private boolean isTeamMainMenu(Player player, String title) {
        TeamRecord team = teamService.getTeamByPlayer(player.getUniqueId());

        if (team == null) {
            return false;
        }

        String expectedTitle = team.name()
                + " ("
                + teamService.getTeamMembers(team.teamId()).size()
                + "/"
                + teamService.maxMembers()
                + ")";

        return title.equals(expectedTitle);
    }

    private void handleStartClick(Player player, int slot) {
        if (slot == TeamStartGui.CREATE_SLOT) {
            SoundService.guiClick(player, core);
            player.closeInventory();

            Component prompt = legacy(
                    BODY + "Type " + PRIMARY + "/team create " + BODY + "to create a team"
            ).clickEvent(ClickEvent.suggestCommand("/team create "));

            player.sendMessage(prompt);
            player.sendActionBar(actionBar(
                    BODY + "Type " + PRIMARY + "/team create " + BODY + "to create a team"
            ));
            return;
        }

        if (slot == TeamStartGui.INVITES_SLOT) {
            SoundService.guiClick(player, core);
            MenuHistory.openChild(
                    core,
                    player,
                    () -> TeamStartGui.open(core, player, inviteService),
                    () -> TeamInviteGui.open(core, player, inviteService, teamService)
            );
        }
    }

    private void handleMainClick(Player player, int slot) {
        TeamRecord team = teamService.getTeamByPlayer(player.getUniqueId());

        if (team == null) {
            SoundService.guiError(player, core);
            MenuHistory.openRoot(core, player, () -> TeamStartGui.open(core, player, inviteService));
            return;
        }

        List<UUID> members = TeamsMainGui.sortedMembers(player, team.teamId(), teamService);

        if (slot >= 0 && slot < 45) {
            if (slot < members.size()) {
                SoundService.guiClick(player, core);
                UUID targetId = members.get(slot);
                player.setMetadata(META_TARGET, new FixedMetadataValue(core, targetId.toString()));

                MenuHistory.openChild(
                        core,
                        player,
                        () -> TeamsMainGui.open(core, player, teamService, inviteService),
                        () -> TeamMemberGui.open(player, targetId, teamService)
                );
                return;
            }

            if (teamService.isAdmin(player.getUniqueId())
                    && slot == members.size()
                    && members.size() < teamService.maxMembers()) {
                SoundService.guiClick(player, core);
                player.closeInventory();

                Component invitePrompt = legacy(
                        BODY + "Type " + PRIMARY + "/team invite " + BODY + "to invite a player"
                ).clickEvent(ClickEvent.suggestCommand("/team invite "));

                player.sendMessage(invitePrompt);
                return;
            }

            return;
        }

        if (slot == TeamsMainGui.TEAM_HOME_SLOT) {
            handleTeamHomeButton(player, team);
            return;
        }

        if (slot == TeamsMainGui.TEAM_CHAT_SLOT) {
            boolean enabled = teamService.toggleTeamChat(player.getUniqueId());
            sendBoth(player, enabled ? "&aTeam chat enabled" : "&cTeam chat disabled");
            SoundService.guiConfirm(player, core);
            TeamsMainGui.open(core, player, teamService, inviteService);
            return;
        }

        if (slot == TeamsMainGui.SORT_SLOT) {
            SoundService.guiClick(player, core);
            TeamsMainGui.cycleSort(player);
            TeamsMainGui.open(core, player, teamService, inviteService);
            return;
        }

        if (slot == TeamsMainGui.TEAM_PVP_SLOT && teamService.isAdmin(player.getUniqueId())) {
            boolean newValue = !team.friendlyFire();
            teamService.setFriendlyFire(team.teamId(), newValue);
            sendBoth(player, newValue ? "&aTeam PvP enabled" : "&cTeam PvP disabled");
            SoundService.guiConfirm(player, core);
            TeamsMainGui.open(core, player, teamService, inviteService);
            return;
        }

        if (slot == TeamsMainGui.TEAM_PVP_SLOT) {
            SoundService.guiError(player, core);
        }
    }

    private void handleTeamHomeButton(Player player, TeamRecord team) {
        org.bukkit.Location home = teamHomeService.getTeamHome(team.teamId());

        if (home != null) {
            SoundService.guiSelect(player, core);
            player.closeInventory();
            teleportService.beginLocation(
                    player,
                    "Team Home",
                    home,
                    TeleportService.TeleportKind.TEAM_HOME
            );
            return;
        }

        if (!teamService.isAdmin(player.getUniqueId())) {
            SoundService.guiError(player, core);
            return;
        }

        SoundService.guiClick(player, core);
        MenuHistory.openChild(
                core,
                player,
                () -> TeamsMainGui.open(core, player, teamService, inviteService),
                () -> HomesMainGui.open(core, player, homeService)
        );
    }

    private void handleInviteClick(Player player, int slot) {
        if (slot == TeamInviteGui.ACCEPT_SLOT) {
            if (inviteService.acceptInvite(player.getUniqueId())) {
                sendBoth(player, "&aInvite accepted");
                SoundService.guiConfirm(player, core);
                MenuHistory.openRoot(
                        core,
                        player,
                        () -> TeamsMainGui.open(core, player, teamService, inviteService)
                );
            } else {
                player.closeInventory();
                sendBoth(player, "&cCould not accept invite");
                SoundService.guiError(player, core);
            }
            return;
        }

        if (slot == TeamInviteGui.DENY_SLOT) {
            if (inviteService.denyInvite(player.getUniqueId())) {
                player.closeInventory();
                sendBoth(player, "&cInvite declined");
                SoundService.guiCancel(player, core);
            } else {
                player.closeInventory();
                sendBoth(player, "&cNo invite found");
                SoundService.guiError(player, core);
            }
        }
    }

    private void handleMemberClick(Player player, int slot) {
        if (!player.hasMetadata(META_TARGET)) {
            player.closeInventory();
            SoundService.guiError(player, core);
            return;
        }

        UUID targetId;
        try {
            targetId = UUID.fromString(player.getMetadata(META_TARGET).get(0).asString());
        } catch (IllegalArgumentException exception) {
            clearConfirmMeta(player);
            player.closeInventory();
            SoundService.guiError(player, core);
            return;
        }

        TeamMemberRecord target = teamService.getMember(targetId);

        if (target == null) {
            player.closeInventory();
            sendBoth(player, "&cThat player is no longer in your team");
            SoundService.guiError(player, core);
            return;
        }

        if (slot == 10) {
            startConfirm(player, "PROMOTE", targetId, "Promote Player");
            return;
        }
        if (slot == 11) {
            startConfirm(player, "DEMOTE", targetId, "Demote Player");
            return;
        }
        if (slot == 13) {
            SoundService.guiClick(player, core);
            MenuHistory.openChild(
                    core,
                    player,
                    () -> TeamMemberGui.open(player, targetId, teamService),
                    () -> playerStatisticsGui.open(player, targetId)
            );
            return;
        }
        if (slot == 15) {
            startConfirm(player, "KICK", targetId, "Kick Player");
            return;
        }
        if (slot == 16) {
            startConfirm(player, "BAN", targetId, "Ban Player");
            return;
        }
        if (slot == 22) {
            startConfirm(player, "TRANSFER", targetId, "Transfer Founder");
        }
    }

    private void startConfirm(Player player, String action, UUID targetId, String title) {
        SoundService.guiClick(player, core);
        player.setMetadata(META_ACTION, new FixedMetadataValue(core, action));
        player.setMetadata(META_TARGET, new FixedMetadataValue(core, targetId.toString()));
        player.removeMetadata(META_CONFIRM, core);

        MenuHistory.openChild(
                core,
                player,
                () -> TeamMemberGui.open(player, targetId, teamService),
                () -> TeamConfirmGui.open(core, player, title)
        );
    }

    private void handleConfirmClick(Player player, int slot) {
        if (slot == TeamConfirmGui.CANCEL_SLOT) {
            clearConfirmMeta(player);
            player.closeInventory();
            sendBoth(player, "&cAction cancelled");
            SoundService.guiCancel(player, core);
            return;
        }

        if (slot == TeamConfirmGui.ACTION_SLOT) {
            return;
        }

        if (slot != TeamConfirmGui.CONFIRM_SLOT) {
            return;
        }

        if (!player.hasMetadata(META_ACTION)) {
            clearConfirmMeta(player);
            player.closeInventory();
            sendBoth(player, "&cNo action is ready to confirm");
            SoundService.guiError(player, core);
            return;
        }

        String action = player.getMetadata(META_ACTION).get(0).asString();

        if (!isConfirmReady(player, action)) {
            markConfirmReady(player, action);
            return;
        }

        executeConfirmedAction(player, action);
    }

    private void executeConfirmedAction(Player player, String action) {
        switch (action) {
            case "DISBAND" -> {
                if (teamService.disbandTeam(player.getUniqueId())) {
                    clearConfirmMeta(player);
                    player.closeInventory();
                    sendBoth(player, "&cTeam disbanded");
                    SoundService.teamDisband(player, core);
                    return;
                }
                clearConfirmMeta(player);
                player.closeInventory();
                sendBoth(player, "&cOnly the founder can disband the team");
                SoundService.guiError(player, core);
            }
            case "LEAVE" -> {
                if (teamService.removeMember(player.getUniqueId())) {
                    clearConfirmMeta(player);
                    player.closeInventory();
                    sendBoth(player, "&cYou left your team");
                    SoundService.guiCancel(player, core);
                    return;
                }
                clearConfirmMeta(player);
                player.closeInventory();
                sendBoth(player, "&cYou cannot leave as founder Use /team disband");
                SoundService.guiError(player, core);
            }
            case "DELETE_HOME" -> deleteTeamHome(player);
            case "PROMOTE", "DEMOTE", "KICK", "BAN", "TRANSFER" ->
                    executeConfirmedTargetAction(player, action);
            default -> {
                clearConfirmMeta(player);
                player.closeInventory();
                sendBoth(player, "&cUnknown action");
                SoundService.guiError(player, core);
            }
        }
    }

    private void deleteTeamHome(Player player) {
        TeamRecord team = teamService.getTeamByPlayer(player.getUniqueId());

        if (team == null) {
            failConfirmed(player, "&cYou are not in a team");
            return;
        }
        if (!teamService.isAdmin(player.getUniqueId())) {
            failConfirmed(player, "&cOnly admins can delete team home");
            return;
        }
        if (!teamHomeService.deleteTeamHome(team.teamId())) {
            failConfirmed(player, "&cYour team does not have a home set");
            return;
        }

        clearConfirmMeta(player);
        player.closeInventory();
        sendBoth(player, "&cTeam home deleted");
        SoundService.homeDelete(player, core);
    }

    private void failConfirmed(Player player, String message) {
        clearConfirmMeta(player);
        player.closeInventory();
        sendBoth(player, message);
        SoundService.guiError(player, core);
    }

    private void executeConfirmedTargetAction(Player player, String action) {
        if (!player.hasMetadata(META_TARGET)) {
            failConfirmed(player, "&cNo player is selected");
            return;
        }

        UUID targetId;
        try {
            targetId = UUID.fromString(player.getMetadata(META_TARGET).get(0).asString());
        } catch (IllegalArgumentException exception) {
            failConfirmed(player, "&cNo player is selected");
            return;
        }

        switch (action) {
            case "PROMOTE" -> {
                if (teamService.setMemberRole(player.getUniqueId(), targetId, TeamRole.ADMIN)) {
                    confirmedSuccess(player, "&aPlayer promoted", true);
                } else {
                    failConfirmed(player, "&cYou cannot promote this player");
                }
            }
            case "DEMOTE" -> {
                if (teamService.setMemberRole(player.getUniqueId(), targetId, TeamRole.MEMBER)) {
                    confirmedSuccess(player, "&aPlayer demoted", true);
                } else {
                    failConfirmed(player, "&cYou cannot demote this player");
                }
            }
            case "KICK" -> {
                if (teamService.kickMember(player.getUniqueId(), targetId)) {
                    clearConfirmMeta(player);
                    sendBoth(player, "&cPlayer kicked");
                    SoundService.guiCancel(player, core);
                    MenuHistory.openRoot(
                            core,
                            player,
                            () -> TeamsMainGui.open(core, player, teamService, inviteService)
                    );
                } else {
                    failConfirmed(player, "&cYou cannot kick that player");
                }
            }
            case "BAN" -> {
                if (teamService.banMember(player.getUniqueId(), targetId)) {
                    clearConfirmMeta(player);
                    sendBoth(player, "&cPlayer banned from this team");
                    SoundService.guiCancel(player, core);
                    MenuHistory.openRoot(
                            core,
                            player,
                            () -> TeamsMainGui.open(core, player, teamService, inviteService)
                    );
                } else {
                    failConfirmed(player, "&cYou cannot ban that player");
                }
            }
            case "TRANSFER" -> {
                if (teamService.transferFounder(player.getUniqueId(), targetId)) {
                    confirmedSuccess(player, "&aFounder transferred", true);
                } else {
                    failConfirmed(player, "&cYou cannot transfer founder to that player");
                }
            }
            default -> failConfirmed(player, "&cUnknown action");
        }
    }

    private void confirmedSuccess(Player player, String message, boolean reopen) {
        clearConfirmMeta(player);
        sendBoth(player, message);
        SoundService.guiConfirm(player, core);

        if (reopen) {
            MenuHistory.openRoot(
                    core,
                    player,
                    () -> TeamsMainGui.open(core, player, teamService, inviteService)
            );
        }
    }

    private boolean isConfirmReady(Player player, String action) {
        return player.hasMetadata(META_CONFIRM)
                && player.getMetadata(META_CONFIRM).get(0).asString().equals(action);
    }

    private void markConfirmReady(Player player, String action) {
        player.setMetadata(META_CONFIRM, new FixedMetadataValue(core, action));
        sendBoth(player, ACCENT + "Click confirm again " + BODY + "to continue");
        SoundService.guiConfirm(player, core);

        UUID playerId = player.getUniqueId();
        core.getServer().getScheduler().runTaskLater(core, () -> {
            Player online = core.getServer().getPlayer(playerId);

            if (online == null || !online.hasMetadata(META_CONFIRM)) {
                return;
            }

            String current = online.getMetadata(META_CONFIRM).get(0).asString();
            if (!current.equals(action)) {
                return;
            }

            online.removeMetadata(META_CONFIRM, core);
            sendBoth(online, "&cAction timed out");
            SoundService.guiError(online, core);
        }, 20L * 5L);
    }

    private void clearConfirmMeta(Player player) {
        player.removeMetadata(META_ACTION, core);
        player.removeMetadata(META_TARGET, core);
        player.removeMetadata(META_CONFIRM, core);
    }

    private void sendBoth(Player player, String message) {
        player.sendMessage(TextColor.color(message));
        player.sendActionBar(actionBar(message));
    }

    private Component actionBar(String message) {
        return legacy(message);
    }

    private Component legacy(String message) {
        return LegacyComponentSerializer.legacySection().deserialize(TextColor.color(message));
    }
}
