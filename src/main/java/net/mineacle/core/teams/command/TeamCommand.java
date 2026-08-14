package net.mineacle.core.teams.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.gui.MenuHistory;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.player.PlayerTabComplete;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.teleport.TeleportService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.homes.service.HomeService;
import net.mineacle.core.teams.gui.TeamBansGui;
import net.mineacle.core.teams.gui.TeamConfirmGui;
import net.mineacle.core.teams.gui.TeamInviteGui;
import net.mineacle.core.teams.gui.TeamStartGui;
import net.mineacle.core.teams.gui.TeamsMainGui;
import net.mineacle.core.teams.model.TeamMemberRecord;
import net.mineacle.core.teams.model.TeamRecord;
import net.mineacle.core.teams.model.TeamRole;
import net.mineacle.core.teams.service.TeamGuiState;
import net.mineacle.core.teams.service.TeamHomeService;
import net.mineacle.core.teams.service.TeamInviteService;
import net.mineacle.core.teams.service.TeamService;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class TeamCommand
        implements CommandExecutor, TabCompleter {

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
    private final TeleportService teleportService;
    private final HomeService homeService;
    private final TeamGuiState guiState;

    public TeamCommand(
            Core core,
            TeamService teamService,
            TeamInviteService inviteService,
            TeamHomeService teamHomeService,
            TeleportService teleportService,
            HomeService homeService,
            TeamGuiState guiState
    ) {
        this.core = core;
        this.teamService = teamService;
        this.inviteService = inviteService;
        this.teamHomeService = teamHomeService;
        this.teleportService = teleportService;
        this.homeService = homeService;
        this.guiState = guiState;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            String @NotNull [] args
    ) {
        if (!(sender
                instanceof Player player)) {
            sender.sendMessage(
                    TextColor.color(
                            "&cPlayers only"
                    )
            );
            return true;
        }

        if (!player.hasPermission(
                "mineacleteams.use"
        )) {
            sendError(
                    player,
                    "&cThis feature is not available for your rank"
            );
            return true;
        }

        if (args.length == 0) {
            openTeamRoot(player);
            return true;
        }

        String sub =
                args[0]
                        .toLowerCase(
                                Locale.ROOT
                        );

        if (!teamService.hasTeam(
                player.getUniqueId()
        )
                && !sub.equals("create")
                && !sub.equals("join")
                && !sub.equals("invites")
                && !sub.equals("accept")
                && !sub.equals("decline")
                && !sub.equals("deny")) {
            openTeamRoot(player);
            return true;
        }

        switch (sub) {
            case "create" ->
                    create(
                            player,
                            args
                    );
            case "join", "invites" ->
                    invites(player);
            case "accept" ->
                    accept(player);
            case "decline", "deny" ->
                    decline(player);
            case "invite" ->
                    invite(
                            player,
                            args
                    );
            case "chat" ->
                    teamChat(player);
            case "home" ->
                    home(player);
            case "sethome" ->
                    setHome(player);
            case "delhome" ->
                    deleteHome(player);
            case "pvp" ->
                    pvp(player);
            case "bans" ->
                    bans(player);
            case "unban" ->
                    unban(
                            player,
                            args
                    );
            case "leave" ->
                    leave(player);
            case "disband" ->
                    disband(player);
            case "kick" ->
                    targetConfirm(
                            player,
                            args,
                            "KICK",
                            "Kick Player",
                            "&cUsage: /team kick <player>"
                    );
            case "ban" ->
                    targetConfirm(
                            player,
                            args,
                            "BAN",
                            "Ban Player",
                            "&cUsage: /team ban <player>"
                    );
            case "promote" ->
                    targetConfirm(
                            player,
                            args,
                            "PROMOTE",
                            "Promote Player",
                            "&cUsage: /team promote <player>"
                    );
            case "demote" ->
                    targetConfirm(
                            player,
                            args,
                            "DEMOTE",
                            "Demote Player",
                            "&cUsage: /team demote <player>"
                    );
            case "transfer" ->
                    targetConfirm(
                            player,
                            args,
                            "TRANSFER",
                            "Transfer Founder",
                            "&cUsage: /team transfer <player>"
                    );
            default ->
                    sendError(
                            player,
                            "&cUnknown team command"
                    );
        }

        return true;
    }

    private void openTeamRoot(
            Player player
    ) {
        if (teamService.hasTeam(
                player.getUniqueId()
        )) {
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

        MenuHistory.openRoot(
                core,
                player,
                () -> TeamStartGui.open(
                        player,
                        inviteService
                )
        );
    }

    private void create(
            Player player,
            String[] args
    ) {
        if (teamService.hasTeam(
                player.getUniqueId()
        )) {
            sendError(
                    player,
                    "&cYou are already in a team"
            );
            return;
        }

        if (args.length < 2) {
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

        String name =
                args[1];

        boolean created = teamService.createTeam(
                player.getUniqueId(),
                name
        );

        if (!created) {
            sendError(
                    player,
                    "&cUse 3-16 letters, numbers, or underscores"
            );
            return;
        }

        sendBoth(
                player,
                BODY
                        + "Created team "
                        + PRIMARY
                        + name
        );
        SoundService.teamCreate(
                player,
                core
        );
        openTeamRoot(player);
    }

    private void invites(
            Player player
    ) {
        MenuHistory.openRoot(
                core,
                player,
                () -> TeamInviteGui.open(
                        player,
                        inviteService,
                        teamService
                )
        );
    }

    private void accept(
            Player player
    ) {
        if (inviteService.acceptInvite(
                player.getUniqueId()
        )) {
            sendBoth(
                    player,
                    "&aInvite accepted"
            );
            SoundService.guiConfirm(
                    player,
                    core
            );
            openTeamRoot(player);
            return;
        }

        sendError(
                player,
                "&cYou do not have a valid team invite"
        );
    }

    private void decline(
            Player player
    ) {
        if (!inviteService.denyInvite(
                player.getUniqueId()
        )) {
            sendError(
                    player,
                    "&cYou do not have a pending team invite"
            );
            return;
        }

        sendBoth(
                player,
                "&cInvite declined"
        );
        SoundService.guiCancel(
                player,
                core
        );
    }

    private void invite(
            Player player,
            String[] args
    ) {
        TeamRecord team =
                teamService.getTeamByPlayer(
                        player.getUniqueId()
                );

        if (team == null) {
            sendError(
                    player,
                    "&cYou are not in a team"
            );
            return;
        }

        if (!teamService.canInvite(
                player.getUniqueId()
        )) {
            sendError(
                    player,
                    "&cYour team role cannot invite players"
            );
            return;
        }

        if (args.length < 2) {
            sendError(
                    player,
                    "&cUsage: /team invite <player>"
            );
            return;
        }

        if (teamService.memberCount(
                team.teamId()
        ) >= teamService.maxMembers()) {
            sendError(
                    player,
                    "&cYour team is full"
            );
            return;
        }

        Player target =
                resolvePublicOnline(
                        player,
                        args[1]
                );

        if (target == null
                || !player.canSee(target)) {
            sendError(
                    player,
                    "&cThat player is not online"
            );
            return;
        }

        if (target.getUniqueId()
                .equals(
                        player.getUniqueId()
                )) {
            sendError(
                    player,
                    "&cYou cannot invite yourself"
            );
            return;
        }

        if (teamService.hasTeam(
                target.getUniqueId()
        )) {
            sendError(
                    player,
                    "&cThat player is already in a team"
            );
            return;
        }

        if (teamService.isBanned(
                team.teamId(),
                target.getUniqueId()
        )) {
            sendError(
                    player,
                    "&cThat player is banned from this team"
            );
            return;
        }

        boolean invited = inviteService.createInvite(
                team.teamId(),
                player.getUniqueId(),
                target.getUniqueId()
        );

        if (!invited) {
            sendError(
                    player,
                    "&cCould not send invite"
            );
            return;
        }

        String targetName =
                DisplayNames.displayName(
                        target
                );
        String senderName =
                DisplayNames.displayName(
                        player
                );

        sendBoth(
                player,
                BODY
                        + "Invited "
                        + SECONDARY
                        + targetName
        );
        SoundService.teamInvite(
                player,
                core
        );

        Component accept =
                legacy("&a[Accept]")
                        .clickEvent(
                                ClickEvent.runCommand(
                                        "/team accept"
                                )
                        );
        Component deny =
                legacy("&c[Deny]")
                        .clickEvent(
                                ClickEvent.runCommand(
                                        "/team deny"
                                )
                        );
        Component view =
                legacy(
                        ACCENT
                                + "[View]"
                ).clickEvent(
                        ClickEvent.runCommand(
                                "/team invites"
                        )
                );

        Component inviteMessage =
                legacy(
                        BODY
                                + "Team invite to "
                                + PRIMARY
                                + team.name()
                                + " "
                                + BODY
                )
                        .append(accept)
                        .append(
                                Component.space()
                        )
                        .append(deny)
                        .append(
                                Component.space()
                        )
                        .append(view);

        target.sendActionBar(
                legacy(
                        BODY
                                + "Team invite from "
                                + SECONDARY
                                + senderName
                )
        );
        target.sendMessage(
                inviteMessage
        );
        SoundService.teamInvite(
                target,
                core
        );
    }

    private void teamChat(
            Player player
    ) {
        boolean enabled =
                teamService.toggleTeamChat(
                        player.getUniqueId()
                );

        sendBoth(
                player,
                enabled
                        ? "&aTeam chat enabled"
                        : "&cTeam chat disabled"
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
    }

    private void home(
            Player player
    ) {
        TeamRecord team =
                teamService.getTeamByPlayer(
                        player.getUniqueId()
                );

        if (team == null) {
            sendError(
                    player,
                    "&cYou are not in a team"
            );
            return;
        }

        org.bukkit.Location home =
                teamHomeService.getTeamHome(
                        team.teamId()
                );

        if (home == null) {
            sendError(
                    player,
                    "&cYour team does not have a home set"
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
        teleportService.beginLocation(
                player,
                "Team Home",
                home,
                TeleportService
                        .TeleportKind
                        .TEAM_HOME
        );
    }

    private void setHome(
            Player player
    ) {
        TeamRecord team =
                teamService.getTeamByPlayer(
                        player.getUniqueId()
                );

        if (team == null) {
            sendError(
                    player,
                    "&cYou are not in a team"
            );
            return;
        }

        if (!teamService.canManageTeamHome(
                player.getUniqueId()
        )) {
            sendError(
                    player,
                    "&cOnly Founder can set Team Home"
            );
            return;
        }

        if (teamHomeService.hasTeamHome(
                team.teamId()
        )) {
            sendError(
                    player,
                    "&cDelete the current Team Home before setting a new one"
            );
            return;
        }

        if (!homeService.canSetTeamHomeHere(
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
    }

    private void deleteHome(
            Player player
    ) {
        TeamRecord team =
                teamService.getTeamByPlayer(
                        player.getUniqueId()
                );

        if (team == null) {
            sendError(
                    player,
                    "&cYou are not in a team"
            );
            return;
        }

        if (!teamService.canManageTeamHome(
                player.getUniqueId()
        )) {
            sendError(
                    player,
                    "&cOnly Founder can delete Team Home"
            );
            return;
        }

        if (!teamHomeService.hasTeamHome(
                team.teamId()
        )) {
            sendError(
                    player,
                    "&cYour team does not have a home set"
            );
            return;
        }

        openRootConfirm(
                player,
                "DELETE_HOME",
                null,
                "Delete Team Home"
        );
    }

    private void pvp(
            Player player
    ) {
        TeamRecord team =
                teamService.getTeamByPlayer(
                        player.getUniqueId()
                );

        if (team == null) {
            sendError(
                    player,
                    "&cYou are not in a team"
            );
            return;
        }

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
    }

    private void bans(
            Player player
    ) {
        if (!teamService.canManageBans(
                player.getUniqueId()
        )) {
            sendError(
                    player,
                    "&cOnly Founder and MVP can manage team bans"
            );
            return;
        }

        MenuHistory.openRoot(
                core,
                player,
                () -> TeamBansGui.open(
                        player,
                        teamService,
                        0
                )
        );
    }

    private void unban(
            Player player,
            String[] args
    ) {
        TeamRecord team =
                teamService.getTeamByPlayer(
                        player.getUniqueId()
                );

        if (team == null
                || !teamService.canManageBans(
                player.getUniqueId()
        )) {
            sendError(
                    player,
                    "&cOnly Founder and MVP can manage team bans"
            );
            return;
        }

        if (args.length < 2) {
            sendError(
                    player,
                    "&cUsage: /team unban <player>"
            );
            return;
        }

        UUID targetId =
                resolveBannedPlayer(
                        team.teamId(),
                        args[1]
                );

        if (targetId == null) {
            sendError(
                    player,
                    "&cThat player is not actively banned"
            );
            return;
        }

        openRootConfirm(
                player,
                "UNBAN",
                targetId,
                "Unban "
                        + DisplayNames.displayName(
                        Bukkit.getOfflinePlayer(targetId)
                )
        );
    }

    private void leave(
            Player player
    ) {
        if (teamService.isFounder(
                player.getUniqueId()
        )) {
            sendError(
                    player,
                    "&cFounder cannot leave Use /team disband"
            );
            return;
        }

        openRootConfirm(
                player,
                "LEAVE",
                null,
                "Leave Team"
        );
    }

    private void disband(
            Player player
    ) {
        if (!teamService.isFounder(
                player.getUniqueId()
        )) {
            sendError(
                    player,
                    "&cOnly Founder can disband the team"
            );
            return;
        }

        openRootConfirm(
                player,
                "DISBAND",
                null,
                "Disband Team"
        );
    }

    private void targetConfirm(
            Player player,
            String[] args,
            String action,
            String actionName,
            String usage
    ) {
        if (args.length < 2) {
            sendError(
                    player,
                    usage
            );
            return;
        }

        UUID targetId =
                resolveTeamMember(
                        player,
                        args[1]
                );

        if (targetId == null) {
            sendError(
                    player,
                    "&cThat player is not in your team"
            );
            return;
        }

        if (targetId.equals(
                player.getUniqueId()
        )) {
            sendError(
                    player,
                    "&cYou cannot target yourself"
            );
            return;
        }

        TeamMemberRecord actor =
                teamService.getMember(
                        player.getUniqueId()
                );
        TeamMemberRecord target =
                teamService.getMember(
                        targetId
                );

        if (actor == null
                || target == null) {
            sendError(
                    player,
                    "&cThat team member is unavailable"
            );
            return;
        }

        boolean allowed =
                switch (action) {
                    case "PROMOTE" ->
                            actor.role()
                            == TeamRole.FOUNDER
                                    && target.role()
                                    .canBePromoted();
                    case "DEMOTE" ->
                            actor.role()
                            == TeamRole.FOUNDER
                                    && target.role()
                                    .canBeDemoted();
                    case "KICK", "BAN" ->
                            actor.role()
                                    .canModerate(
                                            target.role()
                                    );
                    case "TRANSFER" ->
                            actor.role()
                            == TeamRole.FOUNDER
                                    && target.role()
                                    != TeamRole.FOUNDER;
                    default -> false;
                };

        if (!allowed) {
            sendError(
                    player,
                    "&cYour team role cannot do that"
            );
            return;
        }

        openRootConfirm(
                player,
                action,
                targetId,
                actionName
        );
    }

    private void openRootConfirm(
            Player player,
            String action,
            UUID targetId,
            String actionName
    ) {
        TeamRecord team =
                teamService.getTeamByPlayer(
                        player.getUniqueId()
                );

        if (team == null) {
            sendError(
                    player,
                    "&cYou are not in a team"
            );
            return;
        }

        guiState.clear(player);
        SoundService.guiClick(
                player,
                core
        );
        MenuHistory.openRoot(
                core,
                player,
                () -> TeamConfirmGui.open(
                        player,
                        team.teamId(),
                        action,
                        targetId,
                        actionName
                )
        );
    }

    private Player resolvePublicOnline(
            Player viewer,
            String input
    ) {
        if (input == null || input.isBlank()) {
            return null;
        }

        String normalized =
                TextColor.strip(input)
                        .trim()
                        .toLowerCase(Locale.ROOT);
        Player match = null;

        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!viewer.canSee(online)) {
                continue;
            }

            String display =
                    DisplayNames.commandDisplayName(online);

            if (!TextColor.strip(display)
                    .trim()
                    .toLowerCase(Locale.ROOT)
                    .equals(normalized)) {
                continue;
            }

            if (match != null
                    && !match.getUniqueId().equals(
                    online.getUniqueId()
            )) {
                return null;
            }

            match = online;
        }

        return match;
    }

    private UUID resolveTeamMember(
            Player viewer,
            String input
    ) {
        TeamRecord team =
                teamService.getTeamByPlayer(
                        viewer.getUniqueId()
                );

        if (team == null
                || input == null
                || input.isBlank()) {
            return null;
        }

        String normalized =
                TextColor.strip(input)
                        .trim()
                        .toLowerCase(Locale.ROOT);
        UUID match = null;

        for (UUID memberId :
                teamService.getTeamMembers(
                        team.teamId()
                )) {
            OfflinePlayer member =
                    Bukkit.getOfflinePlayer(memberId);
            String display =
                    DisplayNames.commandDisplayName(member);

            if (!TextColor.strip(display)
                    .trim()
                    .toLowerCase(Locale.ROOT)
                    .equals(normalized)) {
                continue;
            }

            if (match != null
                    && !match.equals(memberId)) {
                return null;
            }

            match = memberId;
        }

        return match;
    }

    private UUID resolveBannedPlayer(
            String teamId,
            String input
    ) {
        if (teamId == null
                || input == null
                || input.isBlank()) {
            return null;
        }

        String normalized =
                TextColor.strip(input)
                        .trim()
                        .toLowerCase(Locale.ROOT);
        UUID match = null;

        for (net.mineacle.core.teams.model.TeamBanRecord record :
                teamService.activeBans(teamId)) {
            OfflinePlayer player =
                    Bukkit.getOfflinePlayer(
                            record.playerId()
                    );
            String display =
                    DisplayNames.commandDisplayName(player);

            if (!TextColor.strip(display)
                    .trim()
                    .toLowerCase(Locale.ROOT)
                    .equals(normalized)) {
                continue;
            }

            if (match != null
                    && !match.equals(
                    record.playerId()
            )) {
                return null;
            }

            match = record.playerId();
        }

        return match;
    }

    @Override
    public @NotNull List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            String @NotNull [] args
    ) {
        if (!(sender
                instanceof Player player)
                || !player.hasPermission(
                "mineacleteams.use"
        )) {
            return List.of();
        }

        if (args.length == 1) {
            return PlayerTabComplete.options(
                    args[0],
                    rootOptions(player)
            );
        }

        if (args.length == 2
                && args[0].equalsIgnoreCase(
                "invite"
        )) {
            return inviteCompletions(
                    player,
                    args[1]
            );
        }

        if (args.length == 2
                && args[0]
                .equalsIgnoreCase(
                        "unban"
                )) {
            return bannedPlayerCompletions(
                    player,
                    args[1]
            );
        }

        if (args.length == 2
                && isMemberTargetCommand(
                args[0]
        )) {
            return teamMemberCompletions(
                    player,
                    args[0],
                    args[1]
            );
        }

        return List.of();
    }

    private List<String> rootOptions(
            Player player
    ) {
        if (!teamService.hasTeam(
                player.getUniqueId()
        )) {
            return List.of(
                    "create",
                    "join",
                    "invites",
                    "accept",
                    "deny"
            );
        }

        TeamRole role =
                teamService.role(
                        player.getUniqueId()
                );
        List<String> options =
                new ArrayList<>(
                        List.of(
                                "chat",
                                "home"
                        )
                );

        if (role != TeamRole.FOUNDER) {
            options.add("leave");
        }

        if (role != null
                && role.canInvite()) {
            options.add("invite");
        }

        if (role != null
                && role.canManageBans()) {
            options.add("bans");
            options.add("unban");
            options.add("kick");
            options.add("ban");
        }

        if (role != null
                && role.canTogglePvp()) {
            options.add("pvp");
        }

        if (role == TeamRole.FOUNDER) {
            options.add("sethome");
            options.add("delhome");
            options.add("promote");
            options.add("demote");
            options.add("transfer");
            options.add("disband");
        }

        return List.copyOf(options);
    }

    private boolean isMemberTargetCommand(
            String value
    ) {
        return value.equalsIgnoreCase(
                "promote"
        )
                || value.equalsIgnoreCase(
                "demote"
        )
                || value.equalsIgnoreCase(
                "kick"
        )
                || value.equalsIgnoreCase(
                "ban"
        )
                || value.equalsIgnoreCase(
                "transfer"
        );
    }

    private List<String> inviteCompletions(
            Player player,
            String partial
    ) {
        TeamRecord team =
                teamService.getTeamByPlayer(
                        player.getUniqueId()
                );

        if (team == null
                || !teamService.canInvite(
                player.getUniqueId()
        )
                || teamService.memberCount(
                team.teamId()
        ) >= teamService.maxMembers()) {
            return List.of();
        }

        String normalized =
                partial == null
                        ? ""
                        : TextColor.strip(partial)
                        .trim()
                        .toLowerCase(Locale.ROOT);
        List<String> names =
                new ArrayList<>();

        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getUniqueId().equals(
                    player.getUniqueId()
            )
                    || !player.canSee(online)
                    || teamService.hasTeam(
                    online.getUniqueId()
            )
                    || teamService.isBanned(
                    team.teamId(),
                    online.getUniqueId()
            )) {
                continue;
            }

            String name =
                    DisplayNames.commandDisplayName(online);
            String stripped =
                    TextColor.strip(name)
                            .toLowerCase(Locale.ROOT);

            if (normalized.isEmpty()
                    || stripped.startsWith(normalized)) {
                names.add(name);
            }
        }

        names.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(names);
    }

    private List<String> teamMemberCompletions(
            Player player,
            String action,
            String partial
    ) {
        TeamRecord team =
                teamService.getTeamByPlayer(
                        player.getUniqueId()
                );
        TeamMemberRecord actor =
                teamService.getMember(
                        player.getUniqueId()
                );

        if (team == null || actor == null) {
            return List.of();
        }

        String normalized =
                partial == null
                        ? ""
                        : TextColor.strip(partial)
                        .trim()
                        .toLowerCase(Locale.ROOT);
        List<String> names =
                new ArrayList<>();

        for (UUID memberId :
                teamService.getTeamMembers(
                        team.teamId()
                )) {
            if (memberId.equals(
                    player.getUniqueId()
            )) {
                continue;
            }

            TeamMemberRecord target =
                    teamService.getMember(memberId);

            if (target == null
                    || !tabActionAllowed(
                    actor.role(),
                    target.role(),
                    action
            )) {
                continue;
            }

            String name =
                    DisplayNames.commandDisplayName(
                            Bukkit.getOfflinePlayer(memberId)
                    );

            if (normalized.isEmpty()
                    || TextColor.strip(name)
                    .toLowerCase(Locale.ROOT)
                    .startsWith(normalized)) {
                names.add(name);
            }
        }

        names.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(names);
    }

    private boolean tabActionAllowed(
            TeamRole actor,
            TeamRole target,
            String action
    ) {
        if (actor == null
                || target == null
                || action == null) {
            return false;
        }

        return switch (action.toLowerCase(Locale.ROOT)) {
            case "promote" ->
                    actor == TeamRole.FOUNDER
                            && target.canBePromoted();
            case "demote" ->
                    actor == TeamRole.FOUNDER
                            && target.canBeDemoted();
            case "kick", "ban" ->
                    actor.canModerate(target);
            case "transfer" ->
                    actor == TeamRole.FOUNDER
                            && target != TeamRole.FOUNDER;
            default -> false;
        };
    }

    private List<String> bannedPlayerCompletions(
            Player player,
            String partial
    ) {
        TeamRecord team =
                teamService.getTeamByPlayer(
                        player.getUniqueId()
                );

        if (team == null
                || !teamService.canManageBans(
                player.getUniqueId()
        )) {
            return List.of();
        }

        String normalized =
                partial == null
                        ? ""
                        : partial
                        .trim()
                        .toLowerCase(
                                Locale.ROOT
                        );
        List<String> names =
                new ArrayList<>();

        for (net.mineacle.core.teams.model.TeamBanRecord record :
                teamService.activeBans(
                        team.teamId()
                )) {
            String name =
                    DisplayNames.commandDisplayName(
                            Bukkit.getOfflinePlayer(
                                    record.playerId()
                            )
                    );

            if (normalized.isEmpty()
                    || TextColor.strip(name)
                    .toLowerCase(
                            Locale.ROOT
                    )
                    .startsWith(
                            normalized
                    )) {
                names.add(name);
            }
        }

        names.sort(
                String.CASE_INSENSITIVE_ORDER
        );
        return List.copyOf(names);
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
