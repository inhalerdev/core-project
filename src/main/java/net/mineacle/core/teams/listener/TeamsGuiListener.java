package net.mineacle.core.teams.listener;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.gui.MenuHistory;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.homes.gui.HomesMainGui;
import net.mineacle.core.homes.service.HomeService;
import net.mineacle.core.homes.service.TeleportService;
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

        if (isTeamMainMenu(title)) {
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

    private boolean isTeamMainMenu(String title) {
        for (Player online : core.getServer().getOnlinePlayers()) {
            TeamRecord possibleTeam = teamService.getTeamByPlayer(online.getUniqueId());

            if (possibleTeam == null) {
                continue;
            }

            String expectedTitle = possibleTeam.name()
                    + " ("
                    + teamService.getTeamMembers(possibleTeam.teamId()).size()
                    + "/"
                    + teamService.maxMembers()
                    + ")";

            if (title.equals(expectedTitle)) {
                return true;
            }
        }

        return false;
    }

    private void handleStartClick(Player player, int slot) {
        if (slot == TeamStartGui.CREATE_SLOT) {
            player.closeInventory();

            Component prompt = Component.text(TextColor.color("§7Type §d/team create <name> §7to create a team"))
                    .clickEvent(ClickEvent.suggestCommand("/team create "));

            player.sendMessage(prompt);
            player.sendActionBar(actionBar("§7Type §d/team create <name> §7to create a team"));
            return;
        }

        if (slot == TeamStartGui.INVITES_SLOT) {
            SoundService.guiConfirm(player, core);

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
                UUID targetId = members.get(slot);
                player.setMetadata(META_TARGET, new FixedMetadataValue(core, targetId.toString()));
                SoundService.guiConfirm(player, core);

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
                player.closeInventory();

                Component invitePrompt = Component.text("§7Type §d/team invite <player> §7to invite a player")
                        .clickEvent(ClickEvent.suggestCommand("/team invite "));

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
            String message = enabled ? "§7Team chat enabled" : "§7Team chat disabled";

            sendBoth(player, message);
            SoundService.guiConfirm(player, core);
            TeamsMainGui.open(core, player, teamService, inviteService);
            return;
        }

        if (slot == TeamsMainGui.SORT_SLOT) {
            TeamsMainGui.cycleSort(player);
            TeamsMainGui.open(core, player, teamService, inviteService);
            return;
        }

        if (slot == TeamsMainGui.TEAM_PVP_SLOT && teamService.isAdmin(player.getUniqueId())) {
            boolean newValue = !team.friendlyFire();
            teamService.setFriendlyFire(team.teamId(), newValue);

            String message = newValue ? "§aTeam PvP enabled" : "§cTeam PvP disabled";

            sendBoth(player, message);
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
            player.closeInventory();
            SoundService.guiConfirm(player, core);

            teleportService.begin(player, "Team Home", () -> {
                player.teleport(home);
                sendBoth(player, "§7Teleported to §dTeam Home");
            });

            return;
        }

        if (!teamService.isFounder(player.getUniqueId())) {
            sendBoth(player, "§cYour team does not have a home set");
            player.sendMessage("§7Ask your §dteam founder §7to set Team Home");
            SoundService.guiError(player, core);
            return;
        }

        sendBoth(player, "§7Open Homes to set §dTeam Home");
        SoundService.guiConfirm(player, core);

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
                sendBoth(player, "§aInvite accepted");
                SoundService.guiConfirm(player, core);
                MenuHistory.openRoot(core, player, () -> TeamsMainGui.open(core, player, teamService, inviteService));
            } else {
                player.closeInventory();
                sendBoth(player, "§cCould not accept invite");
                SoundService.guiError(player, core);
            }

            return;
        }

        if (slot == TeamInviteGui.DENY_SLOT) {
            if (inviteService.denyInvite(player.getUniqueId())) {
                player.closeInventory();
                sendBoth(player, "§cInvite declined");
                SoundService.guiCancel(player, core);
            } else {
                player.closeInventory();
                sendBoth(player, "§cNo invite found");
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

        UUID targetId = UUID.fromString(player.getMetadata(META_TARGET).get(0).asString());
        TeamMemberRecord target = teamService.getMember(targetId);

        if (target == null) {
            player.closeInventory();
            sendBoth(player, "§cThat player is no longer in your team");
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
            SoundService.guiConfirm(player, core);

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
        player.setMetadata(META_ACTION, new FixedMetadataValue(core, action));
        player.setMetadata(META_TARGET, new FixedMetadataValue(core, targetId.toString()));
        player.removeMetadata(META_CONFIRM, core);
        SoundService.guiConfirm(player, core);

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
            sendBoth(player, "§cAction cancelled");
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
            sendBoth(player, "§cNo action is ready to confirm");
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
                    sendBoth(player, "§cTeam disbanded");
                    SoundService.teamDisband(player, core);
                    return;
                }

                clearConfirmMeta(player);
                player.closeInventory();
                sendBoth(player, "§cOnly the founder can disband the team");
                SoundService.guiError(player, core);
            }

            case "LEAVE" -> {
                if (teamService.removeMember(player.getUniqueId())) {
                    clearConfirmMeta(player);
                    player.closeInventory();
                    sendBoth(player, "§cYou left your team");
                    SoundService.guiCancel(player, core);
                    return;
                }

                clearConfirmMeta(player);
                player.closeInventory();
                sendBoth(player, "§cYou cannot leave as founder Use /team disband");
                SoundService.guiError(player, core);
            }

            case "DELETE_HOME" -> {
                TeamRecord team = teamService.getTeamByPlayer(player.getUniqueId());

                if (team == null) {
                    clearConfirmMeta(player);
                    player.closeInventory();
                    sendBoth(player, "§cYou are not in a team");
                    SoundService.guiError(player, core);
                    return;
                }

                if (!teamService.isAdmin(player.getUniqueId())) {
                    clearConfirmMeta(player);
                    player.closeInventory();
                    sendBoth(player, "§cOnly admins can delete team home");
                    SoundService.guiError(player, core);
                    return;
                }

                if (!teamHomeService.deleteTeamHome(team.teamId())) {
                    clearConfirmMeta(player);
                    player.closeInventory();
                    sendBoth(player, "§cYour team does not have a home set");
                    SoundService.guiError(player, core);
                    return;
                }

                clearConfirmMeta(player);
                player.closeInventory();
                sendBoth(player, "§cTeam home deleted");
                SoundService.homeDelete(player, core);
            }

            case "PROMOTE", "DEMOTE", "KICK", "BAN", "TRANSFER" -> executeConfirmedTargetAction(player, action);

            default -> {
                clearConfirmMeta(player);
                player.closeInventory();
                sendBoth(player, "§cUnknown action");
                SoundService.guiError(player, core);
            }
        }
    }

    private void executeConfirmedTargetAction(Player player, String action) {
        if (!player.hasMetadata(META_TARGET)) {
            clearConfirmMeta(player);
            player.closeInventory();
            sendBoth(player, "§cNo player is selected");
            SoundService.guiError(player, core);
            return;
        }

        UUID targetId = UUID.fromString(player.getMetadata(META_TARGET).get(0).asString());

        switch (action) {
            case "PROMOTE" -> {
                if (teamService.setMemberRole(player.getUniqueId(), targetId, TeamRole.ADMIN)) {
                    clearConfirmMeta(player);
                    sendBoth(player, "§aPlayer promoted");
                    SoundService.guiConfirm(player, core);
                    MenuHistory.openRoot(core, player, () -> TeamsMainGui.open(core, player, teamService, inviteService));
                    return;
                }

                clearConfirmMeta(player);
                player.closeInventory();
                sendBoth(player, "§cYou cannot promote this player");
                SoundService.guiError(player, core);
            }

            case "DEMOTE" -> {
                if (teamService.setMemberRole(player.getUniqueId(), targetId, TeamRole.MEMBER)) {
                    clearConfirmMeta(player);
                    sendBoth(player, "§aPlayer demoted");
                    SoundService.guiConfirm(player, core);
                    MenuHistory.openRoot(core, player, () -> TeamsMainGui.open(core, player, teamService, inviteService));
                    return;
                }

                clearConfirmMeta(player);
                player.closeInventory();
                sendBoth(player, "§cYou cannot demote this player");
                SoundService.guiError(player, core);
            }

            case "KICK" -> {
                if (teamService.kickMember(player.getUniqueId(), targetId)) {
                    clearConfirmMeta(player);
                    sendBoth(player, "§cPlayer kicked");
                    SoundService.guiCancel(player, core);
                    MenuHistory.openRoot(core, player, () -> TeamsMainGui.open(core, player, teamService, inviteService));
                    return;
                }

                clearConfirmMeta(player);
                player.closeInventory();
                sendBoth(player, "§cYou cannot kick that player");
                SoundService.guiError(player, core);
            }

            case "BAN" -> {
                if (teamService.banMember(player.getUniqueId(), targetId)) {
                    clearConfirmMeta(player);
                    sendBoth(player, "§cPlayer banned from this team");
                    SoundService.guiCancel(player, core);
                    MenuHistory.openRoot(core, player, () -> TeamsMainGui.open(core, player, teamService, inviteService));
                    return;
                }

                clearConfirmMeta(player);
                player.closeInventory();
                sendBoth(player, "§cYou cannot ban that player");
                SoundService.guiError(player, core);
            }

            case "TRANSFER" -> {
                if (teamService.transferFounder(player.getUniqueId(), targetId)) {
                    clearConfirmMeta(player);
                    sendBoth(player, "§aFounder transferred");
                    SoundService.guiConfirm(player, core);
                    MenuHistory.openRoot(core, player, () -> TeamsMainGui.open(core, player, teamService, inviteService));
                    return;
                }

                clearConfirmMeta(player);
                player.closeInventory();
                sendBoth(player, "§cYou cannot transfer founder to that player");
                SoundService.guiError(player, core);
            }

            default -> {
                clearConfirmMeta(player);
                player.closeInventory();
                sendBoth(player, "§cUnknown action");
                SoundService.guiError(player, core);
            }
        }
    }

    private boolean isConfirmReady(Player player, String action) {
        if (!player.hasMetadata(META_CONFIRM)) {
            return false;
        }

        return player.getMetadata(META_CONFIRM).get(0).asString().equals(action);
    }

    private void markConfirmReady(Player player, String action) {
        player.setMetadata(META_CONFIRM, new FixedMetadataValue(core, action));

        String message = "§7Click confirm again to continue";

        sendBoth(player, message);
        SoundService.guiConfirm(player, core);

        core.getServer().getScheduler().runTaskLater(core, () -> {
            if (!player.isOnline()) {
                return;
            }

            if (!player.hasMetadata(META_CONFIRM)) {
                return;
            }

            String current = player.getMetadata(META_CONFIRM).get(0).asString();

            if (!current.equals(action)) {
                return;
            }

            player.removeMetadata(META_CONFIRM, core);
            sendBoth(player, "§cAction timed out");
            SoundService.guiError(player, core);
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
        return LegacyComponentSerializer.legacySection().deserialize(TextColor.color(message));
    }
}