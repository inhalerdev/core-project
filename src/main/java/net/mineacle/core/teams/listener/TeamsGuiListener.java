package net.mineacle.core.teams.listener;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.gui.GuiText;
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
import net.mineacle.core.teams.service.TeamGuiState;
import net.mineacle.core.teams.service.TeamHomeService;
import net.mineacle.core.teams.service.TeamInviteService;
import net.mineacle.core.teams.service.TeamService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.List;
import java.util.UUID;

@SuppressWarnings("unused")
public final class TeamsGuiListener implements Listener {

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
    private final TeamGuiState guiState;

    public TeamsGuiListener(
            Core core,
            TeamService teamService,
            TeamInviteService inviteService,
            TeamHomeService teamHomeService,
            HomeService homeService,
            TeleportService teleportService,
            PlayerStatisticsGui playerStatisticsGui,
            TeamGuiState guiState
    ) {
        this.core = core;
        this.teamService = teamService;
        this.inviteService = inviteService;
        this.teamHomeService = teamHomeService;
        this.homeService = homeService;
        this.teleportService = teleportService;
        this.playerStatisticsGui = playerStatisticsGui;
        this.guiState = guiState;
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

        String title = GuiText.plain(event.getView().title());

        if (title.equals(GuiText.plain(TeamStartGui.TITLE))) {
            event.setCancelled(true);
            handleStartClick(player, slot);
            return;
        }

        if (isTeamMainMenu(player, title)) {
            event.setCancelled(true);
            handleMainClick(player, slot);
            return;
        }

        if (title.equals(GuiText.plain(TeamInviteGui.TITLE))) {
            event.setCancelled(true);
            handleInviteClick(player, slot);
            return;
        }

        if (title.startsWith(TeamMemberGui.TITLE_PREFIX)) {
            event.setCancelled(true);
            handleMemberClick(player, slot);
            return;
        }

        if (title.equals(GuiText.plain(TeamConfirmGui.TITLE))) {
            event.setCancelled(true);
            handleConfirmClick(player, slot);
        }
    }

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
                    BODY + "Type " + ACCENT + "/team create "
                            + BODY + "to create a team"
            ).clickEvent(ClickEvent.suggestCommand("/team create "));
            player.sendMessage(prompt);
            player.sendActionBar(
                    actionBar(
                            BODY + "Type " + ACCENT + "/team create "
                                    + BODY + "to create a team"
                    )
            );
            return;
        }

        if (slot == TeamStartGui.INVITES_SLOT) {
            SoundService.guiClick(player, core);
            MenuHistory.openChild(
                    core,
                    player,
                    () -> TeamStartGui.open(player, inviteService),
                    () -> TeamInviteGui.open(player, inviteService, teamService)
            );
        }
    }

    private void handleMainClick(Player player, int slot) {
        TeamRecord team = teamService.getTeamByPlayer(player.getUniqueId());

        if (team == null) {
            SoundService.guiError(player, core);
            MenuHistory.openRoot(
                    core,
                    player,
                    () -> TeamStartGui.open(player, inviteService)
            );
            return;
        }

        List<UUID> members = TeamsMainGui.sortedMembers(
                player,
                team.teamId(),
                teamService
        );

        if (slot < 45) {
            if (slot < members.size()) {
                SoundService.guiClick(player, core);
                UUID targetId = members.get(slot);
                guiState.selectTarget(player, targetId);

                MenuHistory.openChild(
                        core,
                        player,
                        () -> TeamsMainGui.open(
                                core,
                                player,
                                teamService,
                                inviteService
                        ),
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
                        BODY + "Type " + ACCENT + "/team invite "
                                + BODY + "to invite a player"
                ).clickEvent(ClickEvent.suggestCommand("/team invite "));
                player.sendMessage(invitePrompt);
            }
            return;
        }

        if (slot == TeamsMainGui.TEAM_HOME_SLOT) {
            handleTeamHomeButton(player, team);
            return;
        }

        if (slot == TeamsMainGui.TEAM_CHAT_SLOT) {
            boolean enabled = teamService.toggleTeamChat(player.getUniqueId());
            sendBoth(
                    player,
                    BODY + "Team chat " + (enabled ? SECONDARY + "enabled" : ACCENT + "disabled")
            );
            SoundService.guiConfirm(player, core);
            reopenMain(player);
            return;
        }

        if (slot == TeamsMainGui.SORT_SLOT) {
            SoundService.guiClick(player, core);
            TeamsMainGui.cycleSort(player);
            reopenMain(player);
            return;
        }

        if (slot == TeamsMainGui.TEAM_PVP_SLOT) {
            if (!teamService.isAdmin(player.getUniqueId())) {
                SoundService.guiError(player, core);
                return;
            }

            boolean enabled = !team.friendlyFire();
            teamService.setFriendlyFire(team.teamId(), enabled);
            sendBoth(
                    player,
                    BODY + "Team PvP " + (enabled ? SECONDARY + "enabled" : ACCENT + "disabled")
            );
            SoundService.guiConfirm(player, core);
            reopenMain(player);
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
                () -> TeamsMainGui.open(
                        core,
                        player,
                        teamService,
                        inviteService
                ),
                () -> HomesMainGui.open(core, player, homeService)
        );
    }

    private void handleInviteClick(Player player, int slot) {
        if (slot == TeamInviteGui.ACCEPT_SLOT) {
            if (inviteService.acceptInvite(player.getUniqueId())) {
                sendBoth(player, "&aInvite accepted");
                SoundService.guiConfirm(player, core);
                reopenMain(player);
            } else {
                player.closeInventory();
                sendBoth(player, "&cCould not accept invite");
                SoundService.guiError(player, core);
            }
            return;
        }

        if (slot == TeamInviteGui.DENY_SLOT) {
            player.closeInventory();
            if (inviteService.denyInvite(player.getUniqueId())) {
                sendBoth(player, "&cInvite declined");
                SoundService.guiCancel(player, core);
            } else {
                sendBoth(player, "&cNo invite found");
                SoundService.guiError(player, core);
            }
        }
    }

    private void handleMemberClick(Player player, int slot) {
        UUID targetId = guiState.target(player);
        if (targetId == null) {
            player.closeInventory();
            SoundService.guiError(player, core);
            return;
        }

        TeamMemberRecord target = teamService.getMember(targetId);
        if (target == null) {
            guiState.clear(player);
            player.closeInventory();
            sendBoth(player, "&cThat player is no longer in your team");
            SoundService.guiError(player, core);
            return;
        }

        switch (slot) {
            case 10 -> startConfirm(player, "PROMOTE", targetId, "Promote Player");
            case 11 -> startConfirm(player, "DEMOTE", targetId, "Demote Player");
            case 13 -> {
                SoundService.guiClick(player, core);
                MenuHistory.openChild(
                        core,
                        player,
                        () -> TeamMemberGui.open(player, targetId, teamService),
                        () -> playerStatisticsGui.open(player, targetId)
                );
            }
            case 15 -> startConfirm(player, "KICK", targetId, "Kick Player");
            case 16 -> startConfirm(player, "BAN", targetId, "Ban Player");
            case 22 -> startConfirm(
                    player,
                    "TRANSFER",
                    targetId,
                    "Transfer Founder"
            );
            default -> {
            }
        }
    }

    private void startConfirm(
            Player player,
            String action,
            UUID targetId,
            String title
    ) {
        SoundService.guiClick(player, core);
        guiState.beginAction(player, action, targetId);
        MenuHistory.openChild(
                core,
                player,
                () -> TeamMemberGui.open(player, targetId, teamService),
                () -> TeamConfirmGui.open(player, title)
        );
    }

    private void handleConfirmClick(Player player, int slot) {
        if (slot == TeamConfirmGui.CANCEL_SLOT) {
            guiState.clear(player);
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

        String action = guiState.action(player);
        if (action == null) {
            failConfirmed(player, "&cNo action is ready to confirm");
            return;
        }

        if (!guiState.isConfirmReady(player, action)) {
            markConfirmReady(player, action);
            return;
        }

        executeConfirmedAction(player, action);
    }

    private void executeConfirmedAction(Player player, String action) {
        switch (action) {
            case "DISBAND" -> {
                if (teamService.disbandTeam(player.getUniqueId())) {
                    guiState.clear(player);
                    player.closeInventory();
                    sendBoth(player, "&cTeam disbanded");
                    SoundService.teamDisband(player, core);
                } else {
                    failConfirmed(player, "&cOnly the founder can disband the team");
                }
            }
            case "LEAVE" -> {
                if (teamService.removeMember(player.getUniqueId())) {
                    guiState.clear(player);
                    player.closeInventory();
                    sendBoth(player, "&cYou left your team");
                    SoundService.guiCancel(player, core);
                } else {
                    failConfirmed(
                            player,
                            "&cYou cannot leave as founder Use /team disband"
                    );
                }
            }
            case "DELETE_HOME" -> deleteTeamHome(player);
            case "PROMOTE", "DEMOTE", "KICK", "BAN", "TRANSFER" ->
                    executeConfirmedTargetAction(player, action);
            default -> failConfirmed(player, "&cUnknown action");
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
        if (!teamHomeService.hasTeamHome(team.teamId())) {
            failConfirmed(player, "&cYour team does not have a home set");
            return;
        }

        teamHomeService.deleteTeamHome(team.teamId());

        guiState.clear(player);
        player.closeInventory();
        sendBoth(player, "&cTeam home deleted");
        SoundService.homeDelete(player, core);
    }

    private void executeConfirmedTargetAction(Player player, String action) {
        UUID targetId = guiState.target(player);
        if (targetId == null) {
            failConfirmed(player, "&cNo player is selected");
            return;
        }

        switch (action) {
            case "PROMOTE" -> {
                if (teamService.setMemberRole(
                        player.getUniqueId(),
                        targetId,
                        TeamRole.ADMIN
                )) {
                    confirmedSuccess(player, BODY + "Player " + SECONDARY + "promoted");
                } else {
                    failConfirmed(player, "&cYou cannot promote this player");
                }
            }
            case "DEMOTE" -> {
                if (teamService.setMemberRole(
                        player.getUniqueId(),
                        targetId,
                        TeamRole.MEMBER
                )) {
                    confirmedSuccess(player, BODY + "Player " + SECONDARY + "demoted");
                } else {
                    failConfirmed(player, "&cYou cannot demote this player");
                }
            }
            case "KICK" -> {
                if (teamService.kickMember(player.getUniqueId(), targetId)) {
                    guiState.clear(player);
                    sendBoth(player, "&cPlayer kicked");
                    SoundService.guiCancel(player, core);
                    reopenMain(player);
                } else {
                    failConfirmed(player, "&cYou cannot kick that player");
                }
            }
            case "BAN" -> {
                if (teamService.banMember(player.getUniqueId(), targetId)) {
                    guiState.clear(player);
                    sendBoth(player, "&cPlayer banned from this team");
                    SoundService.guiCancel(player, core);
                    reopenMain(player);
                } else {
                    failConfirmed(player, "&cYou cannot ban that player");
                }
            }
            case "TRANSFER" -> {
                if (teamService.transferFounder(player.getUniqueId(), targetId)) {
                    confirmedSuccess(player, BODY + "Founder " + SECONDARY + "transferred");
                } else {
                    failConfirmed(
                            player,
                            "&cYou cannot transfer founder to that player"
                    );
                }
            }
            default -> failConfirmed(player, "&cUnknown action");
        }
    }

    private void confirmedSuccess(Player player, String message) {
        guiState.clear(player);
        sendBoth(player, message);
        SoundService.guiConfirm(player, core);
        reopenMain(player);
    }

    private void failConfirmed(Player player, String message) {
        guiState.clear(player);
        player.closeInventory();
        sendBoth(player, message);
        SoundService.guiError(player, core);
    }

    private void markConfirmReady(Player player, String action) {
        int timeoutSeconds = 5;
        long confirmationExpiresAt =
                guiState.armConfirmation(
                        player,
                        action,
                        timeoutSeconds
                );
        sendBoth(
                player,
                BODY + "Click confirm again to continue"
        );
        SoundService.guiConfirm(player, core);

        UUID playerId = player.getUniqueId();
        core.getServer().getScheduler().runTaskLater(
                core,
                () -> {
                    Player online = core.getServer().getPlayer(playerId);
                    if (online == null
                            || !guiState.confirmationMatches(
                            online,
                            action,
                            confirmationExpiresAt
                    )) {
                        return;
                    }

                    guiState.clearConfirmation(online);
                    sendBoth(online, "&cAction timed out");
                    SoundService.guiError(online, core);
                },
                timeoutSeconds * 20L
        );
    }

    private void reopenMain(Player player) {
        MenuHistory.openRoot(
                core,
                player,
                () -> TeamsMainGui.open(
                        core,
                        player,
                        teamService,
                        inviteService
                )
        );
    }

    private void sendBoth(Player player, String message) {
        player.sendMessage(TextColor.color(message));
        player.sendActionBar(actionBar(message));
    }

    private Component actionBar(String message) {
        return legacy(message);
    }

    private Component legacy(String message) {
        return LegacyComponentSerializer.legacySection()
                .deserialize(TextColor.color(message));
    }
}
