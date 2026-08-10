package net.mineacle.core.teams.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.gui.MenuHistory;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.teleport.TeleportService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.homes.service.HomeService;
import net.mineacle.core.teams.gui.TeamConfirmGui;
import net.mineacle.core.teams.gui.TeamInviteGui;
import net.mineacle.core.teams.gui.TeamStartGui;
import net.mineacle.core.teams.gui.TeamsMainGui;
import net.mineacle.core.teams.model.TeamRecord;
import net.mineacle.core.teams.service.TeamGuiState;
import net.mineacle.core.teams.service.TeamHomeService;
import net.mineacle.core.teams.service.TeamInviteService;
import net.mineacle.core.teams.service.TeamService;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class TeamCommand implements CommandExecutor, TabCompleter {

    private static final String PRIMARY = "&#8436FE";
    private static final String SECONDARY = "&#B078FF";
    private static final String BODY = "&#bbbbbb";

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
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only");
            return true;
        }

        if (!player.hasPermission("mineacleteams.use")) {
            sendError(player, "&cYou do not have permission");
            return true;
        }

        if (args.length == 0) {
            openTeamRoot(player);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        if (!teamService.hasTeam(player.getUniqueId())
                && !sub.equals("create")
                && !sub.equals("join")
                && !sub.equals("invites")
                && !sub.equals("accept")
                && !sub.equals("decline")
                && !sub.equals("deny")) {
            MenuHistory.openRoot(
                    core,
                    player,
                    () -> TeamStartGui.open(player, inviteService)
            );
            return true;
        }

        switch (sub) {
            case "create" -> create(player, args);
            case "join", "invites" -> invites(player);
            case "accept" -> accept(player);
            case "decline", "deny" -> decline(player);
            case "invite" -> invite(player, args);
            case "chat" -> teamChat(player);
            case "leave" -> leave(player);
            case "disband" -> disband(player);
            case "kick" -> confirmTargetAction(
                    player,
                    args,
                    "KICK",
                    "Kick Player",
                    "&cUsage: /team kick <player>"
            );
            case "ban" -> confirmTargetAction(
                    player,
                    args,
                    "BAN",
                    "Ban Player",
                    "&cUsage: /team ban <player>"
            );
            case "promote" -> confirmTargetAction(
                    player,
                    args,
                    "PROMOTE",
                    "Promote Player",
                    "&cUsage: /team promote <player>"
            );
            case "demote" -> confirmTargetAction(
                    player,
                    args,
                    "DEMOTE",
                    "Demote Player",
                    "&cUsage: /team demote <player>"
            );
            case "transfer" -> confirmTargetAction(
                    player,
                    args,
                    "TRANSFER",
                    "Transfer Founder",
                    "&cUsage: /team transfer <player>"
            );
            case "home" -> home(player);
            case "sethome" -> setHome(player);
            case "delhome" -> delHome(player);
            case "pvp" -> pvp(player);
            default -> sendError(player, "&cUnknown team command");
        }

        return true;
    }

    private void openTeamRoot(Player player) {
        if (teamService.hasTeam(player.getUniqueId())) {
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
                () -> TeamStartGui.open(player, inviteService)
        );
    }

    private void create(Player player, String[] args) {
        if (teamService.hasTeam(player.getUniqueId())) {
            sendError(player, "&cYou are already in a team");
            return;
        }

        if (args.length < 2) {
            Component prompt = legacy(
                    BODY + "Type " + PRIMARY + "/team create "
                            + BODY + "to create a team"
            ).clickEvent(ClickEvent.suggestCommand("/team create "));
            player.sendMessage(prompt);
            player.sendActionBar(
                    actionBar(
                            BODY + "Type " + PRIMARY + "/team create "
                                    + BODY + "to create a team"
                    )
            );
            return;
        }

        String name = args[1];
        if (!teamService.createTeam(player.getUniqueId(), name)) {
            sendError(
                    player,
                    "&cCould not create that team Use 3-16 letters, numbers, or underscores"
            );
            return;
        }

        sendBoth(
                player,
                BODY + "Team " + PRIMARY + name + " " + BODY + "created"
        );
        SoundService.teamCreate(player, core);
    }

    private void invites(Player player) {
        MenuHistory.openRoot(
                core,
                player,
                () -> TeamInviteGui.open(player, inviteService, teamService)
        );
    }

    private void accept(Player player) {
        if (inviteService.getInvite(player.getUniqueId()) == null) {
            sendError(player, "&cYou have no current team invites");
            return;
        }

        if (!inviteService.acceptInvite(player.getUniqueId())) {
            sendError(player, "&cCould not accept invite");
            return;
        }

        sendBoth(player, "&aInvite accepted");
        SoundService.guiConfirm(player, core);
        openTeamRoot(player);
    }

    private void decline(Player player) {
        if (!inviteService.denyInvite(player.getUniqueId())) {
            sendError(player, "&cYou have no current team invites");
            return;
        }

        sendBoth(player, "&cInvite declined");
        SoundService.guiCancel(player, core);
    }

    private void invite(Player player, String[] args) {
        TeamRecord team = teamService.getTeamByPlayer(player.getUniqueId());

        if (team == null) {
            sendError(player, "&cYou are not in a team");
            return;
        }
        if (!teamService.isAdmin(player.getUniqueId())) {
            sendError(player, "&cOnly admins can invite players");
            return;
        }
        if (args.length < 2) {
            sendError(player, "&cUsage: /team invite <player>");
            return;
        }
        if (teamService.getTeamMembers(team.teamId()).size()
                >= teamService.maxMembers()) {
            sendError(player, "&cYour team is full");
            return;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sendError(player, "&cThat player is offline");
            return;
        }
        if (teamService.hasTeam(target.getUniqueId())) {
            sendError(player, "&cThat player is already in a team");
            return;
        }
        if (teamService.isBanned(team.teamId(), target.getUniqueId())) {
            sendError(player, "&cThat player is banned from joining this team");
            return;
        }
        if (!inviteService.createInvite(
                team.teamId(),
                player.getUniqueId(),
                target.getUniqueId()
        )) {
            sendError(player, "&cCould not send invite");
            return;
        }

        String senderName = DisplayNames.prefixedDisplayName(player);
        String targetName = DisplayNames.prefixedDisplayName(target);

        sendBoth(player, "&aInvite sent to " + targetName);
        SoundService.teamInvite(player, core);

        Component accept = legacy("&a[Accept]")
                .clickEvent(ClickEvent.runCommand("/team accept"));
        Component deny = legacy("&c[Deny]")
                .clickEvent(ClickEvent.runCommand("/team deny"));
        Component view = legacy(PRIMARY + "[View]")
                .clickEvent(ClickEvent.runCommand("/team invites"));
        Component inviteMessage = legacy(
                BODY + "You received a team invite to "
                        + PRIMARY + team.name() + " " + BODY
        ).append(accept)
                .append(Component.space())
                .append(deny)
                .append(Component.space())
                .append(view);

        target.sendActionBar(
                actionBar(PRIMARY + "Team invite from " + SECONDARY + senderName)
        );
        target.sendMessage(inviteMessage);
        SoundService.teamInvite(target, core);
    }

    private void teamChat(Player player) {
        if (!teamService.hasTeam(player.getUniqueId())) {
            sendError(player, "&cYou are not in a team");
            return;
        }

        boolean enabled = teamService.toggleTeamChat(player.getUniqueId());
        sendBoth(
                player,
                enabled ? "&aTeam chat enabled" : "&cTeam chat disabled"
        );
        SoundService.guiConfirm(player, core);
    }

    private void leave(Player player) {
        if (teamService.isFounder(player.getUniqueId())) {
            sendError(player, "&cYou cannot leave as founder Use /team disband");
            return;
        }

        guiState.beginAction(player, "LEAVE");
        MenuHistory.openRoot(
                core,
                player,
                () -> TeamConfirmGui.open(player, "Leave Team")
        );
    }

    private void disband(Player player) {
        if (!teamService.isFounder(player.getUniqueId())) {
            sendError(player, "&cOnly the founder can disband the team");
            return;
        }

        guiState.beginAction(player, "DISBAND");
        MenuHistory.openRoot(
                core,
                player,
                () -> TeamConfirmGui.open(player, "Disband Team")
        );
    }

    private void confirmTargetAction(
            Player player,
            String[] args,
            String action,
            String title,
            String usage
    ) {
        if (args.length < 2) {
            sendError(player, usage);
            return;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sendError(player, "&cThat player must be online");
            return;
        }

        TeamRecord playerTeam = teamService.getTeamByPlayer(player.getUniqueId());
        TeamRecord targetTeam = teamService.getTeamByPlayer(target.getUniqueId());
        if (playerTeam == null
                || targetTeam == null
                || !playerTeam.teamId().equals(targetTeam.teamId())) {
            sendError(player, "&cThat player is not in your team");
            return;
        }

        guiState.beginAction(player, action, target.getUniqueId());
        MenuHistory.openRoot(
                core,
                player,
                () -> TeamConfirmGui.open(player, title)
        );
    }

    private void home(Player player) {
        TeamRecord team = teamService.getTeamByPlayer(player.getUniqueId());
        if (team == null) {
            sendError(player, "&cYou are not in a team");
            return;
        }

        org.bukkit.Location home = teamHomeService.getTeamHome(team.teamId());
        if (home == null) {
            sendError(player, "&cYour team does not have a home set");
            return;
        }

        SoundService.guiSelect(player, core);
        teleportService.beginLocation(
                player,
                "Team Home",
                home,
                TeleportService.TeleportKind.TEAM_HOME
        );
    }

    private void setHome(Player player) {
        TeamRecord team = teamService.getTeamByPlayer(player.getUniqueId());
        if (team == null) {
            sendError(player, "&cYou are not in a team");
            return;
        }
        if (!teamService.isAdmin(player.getUniqueId())) {
            sendError(player, "&cOnly admins can set team home");
            return;
        }
        if (!homeService.canSetTeamHomeHere(player)) {
            sendError(player, "&cYou cannot set Team Home in this world");
            return;
        }

        teamHomeService.setTeamHome(team.teamId(), player.getLocation());
        sendBoth(player, "&aTeam Home set " + BODY + "to your current location");
        SoundService.homeSet(player, core);
    }

    private void delHome(Player player) {
        TeamRecord team = teamService.getTeamByPlayer(player.getUniqueId());
        if (team == null) {
            sendError(player, "&cYou are not in a team");
            return;
        }
        if (!teamService.isAdmin(player.getUniqueId())) {
            sendError(player, "&cOnly admins can delete team home");
            return;
        }
        if (teamHomeService.getTeamHome(team.teamId()) == null) {
            sendError(player, "&cYour team does not have a home set");
            return;
        }

        guiState.beginAction(player, "DELETE_HOME");
        MenuHistory.openRoot(
                core,
                player,
                () -> TeamConfirmGui.open(player, "Delete Team Home")
        );
    }

    private void pvp(Player player) {
        TeamRecord team = teamService.getTeamByPlayer(player.getUniqueId());
        if (team == null) {
            sendError(player, "&cYou are not in a team");
            return;
        }
        if (!teamService.isAdmin(player.getUniqueId())) {
            sendError(player, "&cOnly admins can toggle Team PvP");
            return;
        }

        boolean enabled = !team.friendlyFire();
        teamService.setFriendlyFire(team.teamId(), enabled);
        sendBoth(
                player,
                enabled ? "&aTeam PvP enabled" : "&cTeam PvP disabled"
        );
        SoundService.guiConfirm(player, core);
    }

    @Override
    public @NotNull List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            String @NotNull [] args
    ) {
        List<String> completions = new ArrayList<>();

        if (!(sender instanceof Player player)) {
            return completions;
        }

        if (args.length == 1) {
            List<String> options;
            if (!teamService.hasTeam(player.getUniqueId())) {
                options = List.of("create", "join", "invites", "accept", "deny");
            } else if (teamService.isAdmin(player.getUniqueId())) {
                options = List.of(
                        "invite", "chat", "home", "sethome", "delhome", "pvp",
                        "promote", "demote", "kick", "ban", "transfer",
                        "leave", "disband"
                );
            } else {
                options = List.of("chat", "home", "leave");
            }

            String partial = args[0].toLowerCase(Locale.ROOT);
            for (String option : options) {
                if (option.startsWith(partial)) {
                    completions.add(option);
                }
            }
            return completions;
        }

        if (args.length == 2 && isPlayerTargetSubcommand(args[0])) {
            String partial = args[1].toLowerCase(Locale.ROOT);
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.getUniqueId().equals(player.getUniqueId())) {
                    continue;
                }
                if (online.getName().toLowerCase(Locale.ROOT).startsWith(partial)) {
                    completions.add(online.getName());
                }
            }
        }

        return completions;
    }

    private boolean isPlayerTargetSubcommand(String subcommand) {
        return subcommand.equalsIgnoreCase("invite")
                || subcommand.equalsIgnoreCase("promote")
                || subcommand.equalsIgnoreCase("demote")
                || subcommand.equalsIgnoreCase("kick")
                || subcommand.equalsIgnoreCase("ban")
                || subcommand.equalsIgnoreCase("transfer");
    }

    private void sendError(Player player, String message) {
        sendBoth(player, message);
        SoundService.guiError(player, core);
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
