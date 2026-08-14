package net.mineacle.core.teams.listener;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.gui.MenuHistory;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.teleport.TeleportService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.homes.gui.HomesMainGui;
import net.mineacle.core.homes.service.HomeService;
import net.mineacle.core.stats.PlayerStatisticsGui;
import net.mineacle.core.teams.gui.TeamBansGui;
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
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.UUID;

public final class TeamsGuiListener
        implements Listener {

    private static final String PRIMARY =
            "&#8436FE";
    private static final String SECONDARY =
            "&#B078FF";
    private static final String ACCENT =
            "&#D0AFFF";
    private static final String BODY =
            "&#bbbbbb";

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

    @SuppressWarnings("unused")
    @EventHandler
    public void onInventoryClick(
            InventoryClickEvent event
    ) {
        if (!(event.getWhoClicked()
                instanceof Player player)) {
            return;
        }

        Inventory top =
                event.getView()
                        .getTopInventory();
        InventoryHolder holder =
                top.getHolder(false);

        if (!isTeamsHolder(holder)) {
            return;
        }

        event.setCancelled(true);

        int slot =
                event.getRawSlot();

        if (slot < 0
                || slot >= top.getSize()) {
            return;
        }

        switch (holder) {
            case TeamStartGui.StartHolder ignored ->
                    handleStart(
                            player,
                            slot
                    );
            case TeamsMainGui.MainHolder main ->
                    handleMain(
                            player,
                            main,
                            slot,
                            event.isRightClick()
                    );
            case TeamInviteGui.InviteHolder invite ->
                    handleInvite(
                            player,
                            invite,
                            slot
                    );
            case TeamMemberGui.MemberHolder member ->
                    handleMember(
                            player,
                            member,
                            slot
                    );
            case TeamBansGui.BansHolder bans ->
                    handleBans(
                            player,
                            bans,
                            slot
                    );
            case TeamConfirmGui.ConfirmHolder confirm ->
                    handleConfirm(
                            player,
                            confirm,
                            top,
                            slot
                    );
            default -> {
            }
        }
    }

    @SuppressWarnings("unused")
    @EventHandler
    public void onInventoryDrag(
            InventoryDragEvent event
    ) {
        InventoryHolder holder =
                event.getView()
                        .getTopInventory()
                        .getHolder(false);

        if (isTeamsHolder(holder)
                || holder instanceof HomesMainGui.HomesHolder) {
            event.setCancelled(true);
        }
    }

    @SuppressWarnings("unused")
    @EventHandler(
            priority = EventPriority.MONITOR
    )
    public void onInventoryClose(
            InventoryCloseEvent event
    ) {
        if (!(event.getPlayer()
                instanceof Player player)) {
            return;
        }

        if (event.getInventory()
                .getHolder(false)
                instanceof TeamConfirmGui.ConfirmHolder) {
            guiState.clear(player);
        }
    }

    private boolean isTeamsHolder(
            InventoryHolder holder
    ) {
        return holder
                instanceof TeamStartGui.StartHolder
                || holder
                instanceof TeamsMainGui.MainHolder
                || holder
                instanceof TeamInviteGui.InviteHolder
                || holder
                instanceof TeamMemberGui.MemberHolder
                || holder
                instanceof TeamBansGui.BansHolder
                || holder
                instanceof TeamConfirmGui.ConfirmHolder;
    }

    private void handleStart(
            Player player,
            int slot
    ) {
        if (slot
                == TeamStartGui.CREATE_SLOT) {
            SoundService.guiClick(
                    player,
                    core
            );
            MenuHistory.close(
                    core,
                    player
            );

            Component prompt =
                    legacy(
                            BODY
                                    + "Type "
                                    + PRIMARY
                                    + "/team create "
                                    + BODY
                                    + "to create a team"
                    ).clickEvent(
                            ClickEvent
                                    .suggestCommand(
                                            "/team create "
                                    )
                    );

            player.sendMessage(prompt);
            player.sendActionBar(prompt);
            return;
        }

        if (slot
                == TeamStartGui.INVITES_SLOT) {
            if (!inviteService.hasInvite(
                    player.getUniqueId()
            )) {
                SoundService.guiError(
                        player,
                        core
                );
                return;
            }

            SoundService.guiSelect(
                    player,
                    core
            );
            MenuHistory.openChild(
                    core,
                    player,
                    () -> TeamStartGui.open(
                            player,
                            inviteService
                    ),
                    () -> TeamInviteGui.open(
                            player,
                            inviteService,
                            teamService
                    )
            );
        }
    }

    private void handleMain(
            Player player,
            TeamsMainGui.MainHolder holder,
            int slot,
            boolean rightClick
    ) {
        TeamRecord team =
                currentTeam(
                        player,
                        holder.teamId()
                );

        if (team == null) {
            recoverRoot(player);
            return;
        }

        UUID memberId =
                holder.memberAt(slot);

        if (memberId != null) {
            TeamMemberRecord member =
                    teamService.getMember(memberId);

            if (member == null
                    || !holder.teamId().equals(
                    member.teamId()
            )) {
                sendError(
                        player,
                        "&cThat member is no longer in your team"
                );
                reopenMain(player);
                return;
            }

            SoundService.guiSelect(
                    player,
                    core
            );
            MenuHistory.openChild(
                    core,
                    player,
                    () -> TeamsMainGui.open(
                            core,
                            player,
                            teamService,
                            inviteService
                    ),
                    () -> TeamMemberGui.open(
                            player,
                            memberId,
                            teamService
                    )
            );
            return;
        }

        if (slot == holder.inviteSlot()
                && teamService.canInvite(
                player.getUniqueId()
        )) {
            SoundService.guiClick(
                    player,
                    core
            );
            MenuHistory.close(
                    core,
                    player
            );

            Component prompt =
                    legacy(
                            BODY
                                    + "Type "
                                    + ACCENT
                                    + "/team invite "
                                    + BODY
                                    + "to invite a player"
                    ).clickEvent(
                            ClickEvent
                                    .suggestCommand(
                                            "/team invite "
                                    )
                    );

            player.sendMessage(prompt);
            player.sendActionBar(prompt);
            return;
        }

        if (slot
                == TeamsMainGui.BANS_SLOT) {
            if (!teamService.canManageBans(
                    player.getUniqueId()
            )) {
                sendError(
                        player,
                        "&cOnly Founder and MVP can manage team bans"
                );
                return;
            }

            SoundService.guiSelect(
                    player,
                    core
            );
            MenuHistory.openChild(
                    core,
                    player,
                    () -> TeamsMainGui.open(
                            core,
                            player,
                            teamService,
                            inviteService
                    ),
                    () -> TeamBansGui.open(
                            player,
                            teamService,
                            0
                    )
            );
            return;
        }

        if (slot
                == TeamsMainGui.TEAM_HOME_SLOT) {
            handleTeamHome(
                    player,
                    team,
                    rightClick
            );
            return;
        }

        if (slot
                == TeamsMainGui.TEAM_CHAT_SLOT) {
            boolean enabled =
                    teamService.toggleTeamChat(
                            player.getUniqueId()
                    );

            sendBoth(
                    player,
                    BODY
                            + "Team chat "
                            + (
                            enabled
                                    ? "&aenabled"
                                    : "&cdisabled"
                    )
            );

            if (enabled) {
                SoundService.featureEnable(
                        player,
                        core
                );
            } else {
                SoundService.featureDisable(
                        player,
                        core
                );
            }

            reopenMain(player);
            return;
        }

        if (slot
                == TeamsMainGui.TEAM_INFO_SLOT) {
            return;
        }

        if (slot
                == TeamsMainGui.SORT_SLOT) {
            TeamsMainGui.cycleSort(
                    player,
                    rightClick
            );
            SoundService.guiSort(
                    player,
                    core
            );
            reopenMain(player);
            return;
        }

        if (slot
                == TeamsMainGui.TEAM_PVP_SLOT) {
            if (!teamService.canTogglePvp(
                    player.getUniqueId()
            )) {
                sendError(
                        player,
                        "&cOnly Founder and MVP can toggle Team PvP"
                );
                return;
            }

            boolean enabled =
                    !team.friendlyFire();

            boolean changed =
                    teamService.setFriendlyFire(
                            player.getUniqueId(),
                            enabled
                    );

            if (!changed) {
                sendError(
                        player,
                        "&cTeam PvP could not be changed"
                );
                return;
            }

            sendBoth(
                    player,
                    enabled
                            ? "&aTeam PvP enabled"
                            : "&cTeam PvP disabled"
            );

            if (enabled) {
                SoundService.featureEnable(
                        player,
                        core
                );
            } else {
                SoundService.featureDisable(
                        player,
                        core
                );
            }

            reopenMain(player);
        }
    }

    private void handleTeamHome(
            Player player,
            TeamRecord team,
            boolean rightClick
    ) {
        Location home =
                teamHomeService.getTeamHome(
                        team.teamId()
                );

        if (home != null) {
            if (rightClick
                    && teamService
                    .canManageTeamHome(
                            player.getUniqueId()
                    )) {
                openConfirm(
                        player,
                        "DELETE_HOME",
                        null,
                        "Delete Team Home",
                        () -> TeamsMainGui.open(
                                core,
                                player,
                                teamService,
                                inviteService
                        )
                );
                return;
            }

            if (homeService
                    .teamHomeTeleportBlocked(
                            home
                    )) {
                sendError(
                        player,
                        "&cTeam Home is unavailable right now"
                );
                return;
            }

            SoundService.guiSelect(
                    player,
                    core
            );
            MenuHistory.close(
                    core,
                    player
            );
            teleportService.beginLocation(
                    player,
                    "Team Home",
                    home,
                    TeleportService
                            .TeleportKind
                            .TEAM_HOME
            );
            return;
        }

        if (!teamService.canManageTeamHome(
                player.getUniqueId()
        )) {
            sendError(
                    player,
                    "&cFounder must set Team Home first"
            );
            return;
        }

        if (!homeService
                .canSetTeamHomeHere(
                        player
                )) {
            sendError(
                    player,
                    "&cYou cannot set Team Home in this world"
            );
            return;
        }

        teamHomeService.setTeamHome(
                team.teamId(),
                player.getLocation()
        );
        sendBoth(
                player,
                BODY
                        + "Team Home set "
                        + SECONDARY
                        + "here"
        );
        SoundService.homeSet(
                player,
                core
        );
        reopenMain(player);
    }

    private void handleInvite(
            Player player,
            TeamInviteGui.InviteHolder holder,
            int slot
    ) {
        if (!holder.hasInvite()) {
            SoundService.guiError(
                    player,
                    core
            );
            return;
        }

        if (slot == TeamInviteGui.ACCEPT_SLOT) {
            if (inviteService.acceptInvite(
                    player.getUniqueId(),
                    holder.teamId(),
                    holder.inviterId(),
                    holder.createdAt()
            )) {
                sendBoth(
                        player,
                        "&aInvite accepted"
                );
                SoundService.guiConfirm(
                        player,
                        core
                );
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
                return;
            }

            sendError(
                    player,
                    "&cThat invite is no longer valid"
            );
            MenuHistory.openWithoutBackTrigger(
                    core,
                    player,
                    () -> TeamInviteGui.open(
                            player,
                            inviteService,
                            teamService
                    )
            );
            return;
        }

        if (slot == TeamInviteGui.DENY_SLOT) {
            if (inviteService.denyInvite(
                    player.getUniqueId(),
                    holder.teamId(),
                    holder.inviterId(),
                    holder.createdAt()
            )) {
                sendBoth(
                        player,
                        "&cInvite declined"
                );
                SoundService.guiCancel(
                        player,
                        core
                );

                if (!MenuHistory.back(
                        core,
                        player
                )) {
                    MenuHistory.close(
                            core,
                            player
                    );
                }
                return;
            }

            sendError(
                    player,
                    "&cThat invite is no longer valid"
            );
        }
    }

    private void handleMember(
            Player player,
            TeamMemberGui.MemberHolder holder,
            int slot
    ) {
        UUID targetId =
                holder.targetId();
        TeamMemberRecord target =
                teamService.getMember(targetId);
        TeamMemberRecord viewer =
                teamService.getMember(
                        player.getUniqueId()
                );

        if (target == null
                || viewer == null
                || !holder.teamId().equals(
                viewer.teamId()
        )
                || !holder.teamId().equals(
                target.teamId()
        )) {
            sendError(
                    player,
                    "&cThat member is no longer available"
            );
            recoverRoot(player);
            return;
        }

        if (slot == TeamMemberGui.STATS_SLOT) {
            if (!player.hasPermission(
                    "mineaclestats.use"
            )) {
                sendError(
                        player,
                        core.getMessage(
                                "general.no-permission"
                        )
                );
                return;
            }

            SoundService.guiSelect(
                    player,
                    core
            );
            MenuHistory.openChild(
                    core,
                    player,
                    () -> TeamMemberGui.open(
                            player,
                            targetId,
                            teamService
                    ),
                    () -> playerStatisticsGui.open(
                            player,
                            targetId
                    )
            );
            return;
        }

        if (player.getUniqueId().equals(targetId)) {
            if (slot != TeamMemberGui.SELF_ACTION_SLOT) {
                return;
            }

            if (viewer.role() == TeamRole.FOUNDER) {
                openConfirm(
                        player,
                        "DISBAND",
                        null,
                        "Disband Team",
                        () -> TeamMemberGui.open(
                                player,
                                targetId,
                                teamService
                        )
                );
            } else {
                openConfirm(
                        player,
                        "LEAVE",
                        null,
                        "Leave Team",
                        () -> TeamMemberGui.open(
                                player,
                                targetId,
                                teamService
                        )
                );
            }
            return;
        }

        if (slot == TeamMemberGui.PROMOTE_SLOT
                && viewer.role() == TeamRole.FOUNDER
                && target.role().canBePromoted()) {
            openConfirm(
                    player,
                    "PROMOTE",
                    targetId,
                    "Promote "
                            + DisplayNames.displayName(
                            org.bukkit.Bukkit.getOfflinePlayer(
                                    targetId
                            )
                    ),
                    () -> TeamMemberGui.open(
                            player,
                            targetId,
                            teamService
                    )
            );
            return;
        }

        if (slot == TeamMemberGui.DEMOTE_SLOT
                && viewer.role() == TeamRole.FOUNDER
                && target.role().canBeDemoted()) {
            openConfirm(
                    player,
                    "DEMOTE",
                    targetId,
                    "Demote "
                            + DisplayNames.displayName(
                            org.bukkit.Bukkit.getOfflinePlayer(
                                    targetId
                            )
                    ),
                    () -> TeamMemberGui.open(
                            player,
                            targetId,
                            teamService
                    )
            );
            return;
        }

        if (slot == TeamMemberGui.KICK_SLOT
                && viewer.role().canModerate(
                target.role()
        )) {
            openConfirm(
                    player,
                    "KICK",
                    targetId,
                    "Kick "
                            + DisplayNames.displayName(
                            org.bukkit.Bukkit.getOfflinePlayer(
                                    targetId
                            )
                    ),
                    () -> TeamMemberGui.open(
                            player,
                            targetId,
                            teamService
                    )
            );
            return;
        }

        if (slot == TeamMemberGui.BAN_SLOT
                && viewer.role().canModerate(
                target.role()
        )) {
            openConfirm(
                    player,
                    "BAN",
                    targetId,
                    "Ban "
                            + DisplayNames.displayName(
                            org.bukkit.Bukkit.getOfflinePlayer(
                                    targetId
                            )
                    ),
                    () -> TeamMemberGui.open(
                            player,
                            targetId,
                            teamService
                    )
            );
            return;
        }

        if (slot == TeamMemberGui.TRANSFER_SLOT
                && viewer.role() == TeamRole.FOUNDER
                && target.role() != TeamRole.FOUNDER) {
            openConfirm(
                    player,
                    "TRANSFER",
                    targetId,
                    "Transfer Founder",
                    () -> TeamMemberGui.open(
                            player,
                            targetId,
                            teamService
                    )
            );
        }
    }

    private void handleBans(
            Player player,
            TeamBansGui.BansHolder holder,
            int slot
    ) {
        TeamRecord team =
                currentTeam(
                        player,
                        holder.teamId()
                );

        if (team == null) {
            recoverRoot(player);
            return;
        }

        if (!teamService.canManageBans(
                player.getUniqueId()
        )) {
            sendError(
                    player,
                    "&cOnly Founder and MVP can manage team bans"
            );
            recoverRoot(player);
            return;
        }

        UUID bannedId =
                holder.bannedPlayerAt(
                        slot
                );

        if (bannedId != null) {
            openConfirm(
                    player,
                    "UNBAN",
                    bannedId,
                    "Unban "
                            + DisplayNames
                            .displayName(
                                    org.bukkit.Bukkit
                                            .getOfflinePlayer(
                                                    bannedId
                                            )
                            ),
                    () -> TeamBansGui.open(
                            player,
                            teamService,
                            holder.page()
                    )
            );
            return;
        }

        if (slot
                == TeamBansGui.REFRESH_SLOT) {
            SoundService.guiRefresh(
                    player,
                    core
            );
            MenuHistory.openWithoutBackTrigger(
                    core,
                    player,
                    () -> TeamBansGui.open(
                            player,
                            teamService,
                            holder.page()
                    )
            );
            return;
        }

        if (slot
                == TeamBansGui.PREVIOUS_SLOT
                && holder.page() > 0) {
            SoundService.guiPage(
                    player,
                    core
            );
            MenuHistory.openWithoutBackTrigger(
                    core,
                    player,
                    () -> TeamBansGui.open(
                            player,
                            teamService,
                            holder.page() - 1
                    )
            );
            return;
        }

        if (slot
                == TeamBansGui.NEXT_SLOT) {
            int next =
                    holder.page() + 1;

            if (next * 45
                    < teamService
                    .activeBans(
                            team.teamId()
                    )
                    .size()) {
                SoundService.guiPage(
                        player,
                        core
                );
                MenuHistory.openWithoutBackTrigger(
                        core,
                        player,
                        () -> TeamBansGui.open(
                                player,
                                teamService,
                                next
                        )
                );
            }
        }
    }

    private void openConfirm(
            Player player,
            String action,
            UUID targetId,
            String actionName,
            Runnable previous
    ) {
        TeamRecord team =
                teamService.getTeamByPlayer(
                        player.getUniqueId()
                );

        if (team == null) {
            recoverRoot(player);
            return;
        }

        SoundService.guiClick(
                player,
                core
        );
        guiState.clear(player);

        MenuHistory.openChild(
                core,
                player,
                previous,
                () -> TeamConfirmGui.open(
                        player,
                        team.teamId(),
                        action,
                        targetId,
                        actionName
                )
        );
    }

    private void handleConfirm(
            Player player,
            TeamConfirmGui.ConfirmHolder holder,
            Inventory inventory,
            int slot
    ) {
        if (currentTeam(
                player,
                holder.teamId()
        ) == null) {
            guiState.clear(player);
            sendError(
                    player,
                    "&cThis team action is no longer valid"
            );
            recoverRoot(player);
            return;
        }

        if (slot == TeamConfirmGui.CANCEL_SLOT) {
            guiState.clear(player);
            sendBoth(
                    player,
                    "&cAction cancelled"
            );
            SoundService.guiCancel(
                    player,
                    core
            );

            if (!MenuHistory.back(
                    core,
                    player
            )) {
                MenuHistory.close(
                        core,
                        player
                );
            }
            return;
        }

        if (slot == TeamConfirmGui.ACTION_SLOT) {
            return;
        }

        if (slot != TeamConfirmGui.CONFIRM_SLOT) {
            return;
        }

        if (!holder.requiresSecondConfirm()) {
            guiState.clear(player);
            executeConfirmed(
                    player,
                    holder
            );
            return;
        }

        String token =
                holder.token();

        if (!guiState.ready(
                player,
                token
        )) {
            int timeout =
                    Math.max(
                            1,
                            core.getConfig().getInt(
                                    "teams.confirm-timeout-seconds",
                                    5
                            )
                    );
            long expiresAt =
                    guiState.arm(
                            player,
                            token,
                            timeout
                    );

            TeamConfirmGui.showArmed(
                    inventory
            );
            sendBoth(
                    player,
                    BODY + "Click "
                            + "&aConfirm Again "
                            + BODY
                            + "to continue"
            );
            SoundService.guiConfirm(
                    player,
                    core
            );
            scheduleConfirmationTimeout(
                    player.getUniqueId(),
                    token,
                    expiresAt,
                    timeout
            );
            return;
        }

        guiState.clear(player);
        executeConfirmed(
                player,
                holder
        );
    }

    private void scheduleConfirmationTimeout(
            UUID playerId,
            String token,
            long expiresAt,
            int timeoutSeconds
    ) {
        core.getServer()
                .getScheduler()
                .runTaskLater(
                        core,
                        () -> {
                            Player player =
                                    core.getServer()
                                            .getPlayer(
                                                    playerId
                                            );

                            if (!guiState.matches(
                                    player,
                                    token,
                                    expiresAt
                            )) {
                                return;
                            }

                            guiState.clear(player);

                            Inventory top =
                                    player.getOpenInventory()
                                            .getTopInventory();

                            if (top.getHolder(false)
                                    instanceof TeamConfirmGui.ConfirmHolder current
                                    && current.token()
                                    .equals(token)) {
                                TeamConfirmGui
                                        .showUnarmed(
                                                top
                                        );
                            }

                            sendBoth(
                                    player,
                                    "&cAction timed out"
                            );
                            SoundService.guiError(
                                    player,
                                    core
                            );
                        },
                        Math.max(
                                1,
                                timeoutSeconds
                        ) * 20L
                );
    }

    private void executeConfirmed(
            Player player,
            TeamConfirmGui.ConfirmHolder holder
    ) {
        TeamRecord expectedTeam =
                currentTeam(
                        player,
                        holder.teamId()
                );

        if (expectedTeam == null) {
            sendError(
                    player,
                    "&cThis team action is no longer valid"
            );
            recoverRoot(player);
            return;
        }

        String action =
                holder.action();
        UUID targetId =
                holder.targetId();

        switch (action) {
            case "PROMOTE" -> {
                boolean promoted = teamService.promoteMember(
                        player.getUniqueId(),
                        targetId
                );

                if (!promoted) {
                    failConfirm(
                            player,
                            "&cYou cannot promote that member"
                    );
                    return;
                }

                sendBoth(
                        player,
                        "&aMember promoted"
                );
                SoundService.guiConfirm(
                        player,
                        core
                );
                backOrMain(player);
            }
            case "DEMOTE" -> {
                boolean demoted = teamService.demoteMember(
                        player.getUniqueId(),
                        targetId
                );

                if (!demoted) {
                    failConfirm(
                            player,
                            "&cYou cannot demote that member"
                    );
                    return;
                }

                sendBoth(
                        player,
                        "&aMember demoted"
                );
                SoundService.guiConfirm(
                        player,
                        core
                );
                backOrMain(player);
            }
            case "KICK" -> {
                boolean kicked = teamService.kickMember(
                        player.getUniqueId(),
                        targetId
                );

                if (!kicked) {
                    failConfirm(
                            player,
                            "&cYou cannot kick that member"
                    );
                    return;
                }

                sendBoth(
                        player,
                        "&cMember kicked"
                );
                SoundService.guiDelete(
                        player,
                        core
                );
                reopenMainRoot(player);
            }
            case "BAN" -> {
                boolean banned = teamService.banMember(
                        player.getUniqueId(),
                        targetId
                );

                if (!banned) {
                    failConfirm(
                            player,
                            "&cYou cannot ban that member"
                    );
                    return;
                }

                sendBoth(
                        player,
                        "&cMember banned"
                );
                SoundService.guiDelete(
                        player,
                        core
                );
                reopenMainRoot(player);
            }
            case "TRANSFER" -> {
                boolean transferred = teamService.transferFounder(
                        player.getUniqueId(),
                        targetId
                );

                if (!transferred) {
                    failConfirm(
                            player,
                            "&cFounder transfer failed"
                    );
                    return;
                }

                sendBoth(
                        player,
                        "&aFounder transferred"
                );
                SoundService.guiConfirm(
                        player,
                        core
                );
                reopenMainRoot(player);
            }
            case "UNBAN" -> {
                if (teamService.unbanMember(
                        player.getUniqueId(),
                        targetId
                )) {
                    sendBoth(
                            player,
                            "&aPlayer unbanned"
                    );
                    SoundService.guiConfirm(
                            player,
                            core
                    );
                    backOrMain(player);
                    return;
                }

                failConfirm(
                        player,
                        "&cThat player is not actively banned"
                );
            }
            case "LEAVE" -> {
                boolean left = teamService.removeMember(
                        player.getUniqueId()
                );

                if (!left) {
                    failConfirm(
                            player,
                            "&cYou cannot leave as Founder"
                    );
                    return;
                }

                sendBoth(
                        player,
                        "&cYou left your team"
                );
                SoundService.guiCancel(
                        player,
                        core
                );
                MenuHistory.openRoot(
                        core,
                        player,
                        () -> TeamStartGui.open(
                                player,
                                inviteService
                        )
                );
            }
            case "DISBAND" -> {
                boolean disbanded = teamService.disbandTeam(
                        player.getUniqueId()
                );

                if (!disbanded) {
                    failConfirm(
                            player,
                            "&cOnly Founder can disband the team"
                    );
                    return;
                }

                sendBoth(
                        player,
                        "&cTeam disbanded"
                );
                SoundService.teamDisband(
                        player,
                        core
                );
                MenuHistory.openRoot(
                        core,
                        player,
                        () -> TeamStartGui.open(
                                player,
                                inviteService
                        )
                );
            }
            case "DELETE_HOME" -> {
                if (!teamService.canManageTeamHome(
                        player.getUniqueId()
                )
                        || !teamHomeService.hasTeamHome(
                        expectedTeam.teamId()
                )) {
                    failConfirm(
                            player,
                            "&cTeam Home cannot be deleted"
                    );
                    return;
                }

                teamHomeService.deleteTeamHome(
                        expectedTeam.teamId()
                );
                sendBoth(
                        player,
                        "&cTeam Home deleted"
                );
                SoundService.homeDelete(
                        player,
                        core
                );
                backOrMain(player);
            }
            default ->
                    failConfirm(
                            player,
                            "&cUnknown action"
                    );
        }
    }

    private TeamRecord currentTeam(
            Player player,
            String expectedTeamId
    ) {
        TeamRecord team =
                teamService.getTeamByPlayer(
                        player.getUniqueId()
                );

        if (team == null
                || expectedTeamId == null
                || !expectedTeamId.equals(
                team.teamId()
        )) {
            return null;
        }

        return team;
    }

    private void recoverRoot(
            Player player
    ) {
        SoundService.guiError(
                player,
                core
        );

        if (teamService.hasTeam(
                player.getUniqueId()
        )) {
            reopenMainRoot(player);
            return;
        }

        MenuHistory.openRoot(
                core,
                player,
                () -> TeamStartGui.open(
                        player,
                        inviteService
                )
        );
    }

    private void reopenMain(
            Player player
    ) {
        MenuHistory.openWithoutBackTrigger(
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

    private void reopenMainRoot(
            Player player
    ) {
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

    private void backOrMain(
            Player player
    ) {
        if (!MenuHistory.back(
                core,
                player
        )) {
            if (teamService.hasTeam(
                    player.getUniqueId()
            )) {
                reopenMainRoot(player);
            } else {
                MenuHistory.close(
                        core,
                        player
                );
            }
        }
    }

    private void failConfirm(
            Player player,
            String message
    ) {
        sendError(
                player,
                message
        );
        backOrMain(player);
    }

    private void sendError(
            Player player,
            String message
    ) {
        sendBoth(
                player,
                message
        );
        SoundService.guiError(
                player,
                core
        );
    }

    private void sendBoth(
            Player player,
            String message
    ) {
        Component component =
                legacy(message);

        player.sendMessage(component);
        player.sendActionBar(component);
    }

    private Component legacy(
            String message
    ) {
        return LegacyComponentSerializer
                .legacySection()
                .deserialize(
                        TextColor.color(
                                message
                        )
                );
    }
}
