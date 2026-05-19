package net.mineacle.core.teams.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.gui.MenuHistory;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.homes.service.HomeService;
import net.mineacle.core.homes.service.TeleportService;
import net.mineacle.core.teams.gui.TeamConfirmGui;
import net.mineacle.core.teams.gui.TeamInviteGui;
import net.mineacle.core.teams.gui.TeamStartGui;
import net.mineacle.core.teams.gui.TeamsMainGui;
import net.mineacle.core.teams.model.TeamInviteRecord;
import net.mineacle.core.teams.model.TeamRecord;
import net.mineacle.core.teams.service.TeamHomeService;
import net.mineacle.core.teams.service.TeamInviteService;
import net.mineacle.core.teams.service.TeamService;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.metadata.FixedMetadataValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class TeamCommand implements CommandExecutor, TabCompleter {

    private static final String META_TARGET = "simple_team_target";
    private static final String META_ACTION = "simple_team_action";
    private static final String META_CONFIRM = "simple_team_confirm";

    private final Core core;
    private final TeamService teamService;
    private final TeamInviteService inviteService;
    private final TeamHomeService teamHomeService;
    private final TeleportService teleportService;
    private final HomeService homeService;

    public TeamCommand(
            Core core,
            TeamService teamService,
            TeamInviteService inviteService,
            TeamHomeService teamHomeService,
            TeleportService teleportService
    ) {
        this.core = core;
        this.teamService = teamService;
        this.inviteService = inviteService;
        this.teamHomeService = teamHomeService;
        this.teleportService = teleportService;
        this.homeService = new HomeService(core);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only");
            return true;
        }

        if (!player.hasPermission("mineacleteams.use")) {
            sendError(player, "§cYou do not have permission");
            return true;
        }

        if (args.length == 0) {
            if (teamService.hasTeam(player.getUniqueId())) {
                MenuHistory.openRoot(core, player, () -> TeamsMainGui.open(core, player, teamService, inviteService));
            } else {
                MenuHistory.openRoot(core, player, () -> TeamStartGui.open(core, player, inviteService));
            }

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
            MenuHistory.openRoot(core, player, () -> TeamStartGui.open(core, player, inviteService));
            return true;
        }

        switch (sub) {
            case "create" -> {
                return create(player, args);
            }
            case "join", "invites" -> {
                return invites(player);
            }
            case "accept" -> {
                return accept(player);
            }
            case "decline", "deny" -> {
                return decline(player);
            }
            case "invite" -> {
                return invite(player, args);
            }
            case "chat" -> {
                return teamChat(player);
            }
            case "leave" -> {
                return leave(player);
            }
            case "disband" -> {
                return disband(player);
            }
            case "kick" -> {
                return confirmTargetAction(player, args, "KICK", "Kick Player", "§cUsage: /team kick ");
            }
            case "ban" -> {
                return confirmTargetAction(player, args, "BAN", "Ban Player", "§cUsage: /team ban ");
            }
            case "promote" -> {
                return confirmTargetAction(player, args, "PROMOTE", "Promote Player", "§cUsage: /team promote ");
            }
            case "demote" -> {
                return confirmTargetAction(player, args, "DEMOTE", "Demote Player", "§cUsage: /team demote ");
            }
            case "transfer" -> {
                return confirmTargetAction(player, args, "TRANSFER", "Transfer Founder", "§cUsage: /team transfer ");
            }
            case "home" -> {
                return home(player);
            }
            case "sethome" -> {
                return setHome(player);
            }
            case "delhome" -> {
                return delHome(player);
            }
            case "pvp" -> {
                return pvp(player);
            }
            default -> {
                sendError(player, "§cUnknown team command");
                return true;
            }
        }
    }

    private boolean create(Player player, String[] args) {
        if (teamService.hasTeam(player.getUniqueId())) {
            sendError(player, "§cYou are already in a team");
            return true;
        }

        if (args.length < 2) {
            Component prompt = Component.text(TextColor.color("§7Type §d/team create  §7to create a team"))
                    .clickEvent(ClickEvent.suggestCommand("/team create "));

            player.sendMessage(prompt);
            player.sendActionBar(actionBar("§7Type §d/team create  §7to create a team"));
            return true;
        }

        String name = args[1];

        if (!teamService.createTeam(player.getUniqueId(), name)) {
            sendError(player, "§cCould not create that team Use 3-16 letters, numbers, or underscores");
            return true;
        }

        sendBoth(player, "§7Team §d" + name + " §7created");
        SoundService.teamCreate(player, core);
        return true;
    }

    private boolean invites(Player player) {
        MenuHistory.openRoot(core, player, () -> TeamInviteGui.open(core, player, inviteService, teamService));
        return true;
    }

    private boolean accept(Player player) {
        TeamInviteRecord invite = inviteService.getInvite(player.getUniqueId());

        if (invite == null) {
            sendError(player, "§cYou have no current team invites");
            return true;
        }

        if (!inviteService.acceptInvite(player.getUniqueId())) {
            sendError(player, "§cCould not accept invite");
            return true;
        }

        sendBoth(player, "§aInvite accepted");
        SoundService.guiConfirm(player, core);
        MenuHistory.openRoot(core, player, () -> TeamsMainGui.open(core, player, teamService, inviteService));
        return true;
    }

    private boolean decline(Player player) {
        if (!inviteService.denyInvite(player.getUniqueId())) {
            sendError(player, "§cYou have no current team invites");
            return true;
        }

        sendBoth(player, "§cInvite declined");
        SoundService.guiCancel(player, core);
        return true;
    }

    private boolean invite(Player player, String[] args) {
        TeamRecord team = teamService.getTeamByPlayer(player.getUniqueId());

        if (team == null) {
            sendError(player, "§cYou are not in a team");
            return true;
        }

        if (!teamService.isAdmin(player.getUniqueId())) {
            sendError(player, "§cOnly admins can invite players");
            return true;
        }

        if (args.length < 2) {
            sendError(player, "§cUsage: /team invite ");
            return true;
        }

        if (teamService.getTeamMembers(team.teamId()).size() >= teamService.maxMembers()) {
            sendError(player, "§cYour team is full");
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[1]);

        if (target == null) {
            sendError(player, "§cThat player is offline");
            return true;
        }

        if (teamService.hasTeam(target.getUniqueId())) {
            sendError(player, "§cThat player is already in a team");
            return true;
        }

        if (teamService.isBanned(team.teamId(), target.getUniqueId())) {
            sendError(player, "§cThat player is banned from joining this team");
            return true;
        }

        if (!inviteService.createInvite(team.teamId(), player.getUniqueId(), target.getUniqueId())) {
            sendError(player, "§cCould not send invite");
            return true;
        }

        String senderName = DisplayNames.prefixedDisplayName(player);
        String targetName = DisplayNames.prefixedDisplayName(target);

        sendBoth(player, "§aInvite sent to " + TextColor.color(targetName));
        SoundService.teamInvite(player, core);

        Component accept = Component.text(TextColor.color("&d[Accept]"))
                .clickEvent(ClickEvent.runCommand("/team accept"));
        Component deny = Component.text(TextColor.color("&d[Deny]"))
                .clickEvent(ClickEvent.runCommand("/team deny"));
        Component view = Component.text(TextColor.color("&d[View]"))
                .clickEvent(ClickEvent.runCommand("/team invites"));

        Component inviteMessage = Component.text(TextColor.color("§7You received a team invite to &d" + team.name() + " §7"))
                .append(accept)
                .append(Component.text(" "))
                .append(deny)
                .append(Component.text(" "))
                .append(view);

        target.sendActionBar(actionBar("§dTeam invite from " + TextColor.color(senderName)));
        target.sendMessage(inviteMessage);
        SoundService.teamInvite(target, core);
        return true;
    }

    private boolean teamChat(Player player) {
        if (!teamService.hasTeam(player.getUniqueId())) {
            sendError(player, "§cYou are not in a team");
            return true;
        }

        boolean enabled = teamService.toggleTeamChat(player.getUniqueId());
        String message = enabled ? "§7Team chat enabled" : "§7Team chat disabled";

        sendBoth(player, message);
        SoundService.guiConfirm(player, core);
        return true;
    }

    private boolean leave(Player player) {
        if (teamService.isFounder(player.getUniqueId())) {
            sendError(player, "§cYou cannot leave as founder Use /team disband");
            return true;
        }

        clearConfirmMeta(player);
        player.setMetadata(META_ACTION, new FixedMetadataValue(core, "LEAVE"));
        MenuHistory.openRoot(core, player, () -> TeamConfirmGui.open(core, player, "Leave Team"));
        return true;
    }

    private boolean disband(Player player) {
        if (!teamService.isFounder(player.getUniqueId())) {
            sendError(player, "§cOnly the founder can disband the team");
            return true;
        }

        clearConfirmMeta(player);
        player.setMetadata(META_ACTION, new FixedMetadataValue(core, "DISBAND"));
        MenuHistory.openRoot(core, player, () -> TeamConfirmGui.open(core, player, "Disband Team"));
        return true;
    }

    private boolean confirmTargetAction(Player player, String[] args, String action, String title, String usage) {
        if (args.length < 2) {
            sendError(player, usage);
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[1]);

        if (target == null) {
            sendError(player, "§cThat player must be online");
            return true;
        }

        TeamRecord playerTeam = teamService.getTeamByPlayer(player.getUniqueId());
        TeamRecord targetTeam = teamService.getTeamByPlayer(target.getUniqueId());

        if (playerTeam == null || targetTeam == null || !playerTeam.teamId().equals(targetTeam.teamId())) {
            sendError(player, "§cThat player is not in your team");
            return true;
        }

        clearConfirmMeta(player);
        player.setMetadata(META_ACTION, new FixedMetadataValue(core, action));
        player.setMetadata(META_TARGET, new FixedMetadataValue(core, target.getUniqueId().toString()));
        MenuHistory.openRoot(core, player, () -> TeamConfirmGui.open(core, player, title));
        return true;
    }

    private boolean home(Player player) {
        TeamRecord team = teamService.getTeamByPlayer(player.getUniqueId());

        if (team == null) {
            sendError(player, "§cYou are not in a team");
            return true;
        }

        org.bukkit.Location home = teamHomeService.getTeamHome(team.teamId());

        if (home == null) {
            sendError(player, "§cYour team does not have a home set");
            return true;
        }

        SoundService.guiClick(player, core);
        teleportService.begin(player, "Team Home", () -> {
            player.teleport(home);
            sendBoth(player, "§7Teleported to §dTeam Home");
        });

        return true;
    }

    private boolean setHome(Player player) {
        TeamRecord team = teamService.getTeamByPlayer(player.getUniqueId());

        if (team == null) {
            sendError(player, "§cYou are not in a team");
            return true;
        }

        if (!teamService.isAdmin(player.getUniqueId())) {
            sendError(player, "§cOnly admins can set team home");
            return true;
        }

        if (!homeService.canSetTeamHomeHere(player)) {
            sendError(player, "§cYou cannot set Team Home in this world");
            return true;
        }

        teamHomeService.setTeamHome(team.teamId(), player.getLocation());
        sendBoth(player, "§7Team Home set to your current location");
        SoundService.homeSet(player, core);
        return true;
    }

    private boolean delHome(Player player) {
        TeamRecord team = teamService.getTeamByPlayer(player.getUniqueId());

        if (team == null) {
            sendError(player, "§cYou are not in a team");
            return true;
        }

        if (!teamService.isAdmin(player.getUniqueId())) {
            sendError(player, "§cOnly admins can delete team home");
            return true;
        }

        if (teamHomeService.getTeamHome(team.teamId()) == null) {
            sendError(player, "§cYour team does not have a home set");
            return true;
        }

        clearConfirmMeta(player);
        player.setMetadata(META_ACTION, new FixedMetadataValue(core, "DELETE_HOME"));
        MenuHistory.openRoot(core, player, () -> TeamConfirmGui.open(core, player, "Delete Team Home"));
        return true;
    }

    private boolean pvp(Player player) {
        TeamRecord team = teamService.getTeamByPlayer(player.getUniqueId());

        if (team == null) {
            sendError(player, "§cYou are not in a team");
            return true;
        }

        if (!teamService.isAdmin(player.getUniqueId())) {
            sendError(player, "§cOnly admins can toggle Team PvP");
            return true;
        }

        boolean enabled = !team.friendlyFire();
        teamService.setFriendlyFire(team.teamId(), enabled);

        String message = enabled ? "§aTeam PvP enabled" : "§cTeam PvP disabled";

        sendBoth(player, message);
        SoundService.guiConfirm(player, core);
        return true;
    }

    private void clearConfirmMeta(Player player) {
        player.removeMetadata(META_ACTION, core);
        player.removeMetadata(META_TARGET, core);
        player.removeMetadata(META_CONFIRM, core);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
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
                        "promote", "demote", "kick", "ban", "transfer", "leave", "disband"
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

        if (args.length == 2
                && (args[0].equalsIgnoreCase("invite")
                || args[0].equalsIgnoreCase("promote")
                || args[0].equalsIgnoreCase("demote")
                || args[0].equalsIgnoreCase("kick")
                || args[0].equalsIgnoreCase("ban")
                || args[0].equalsIgnoreCase("transfer"))) {
            String partial = args[1].toLowerCase(Locale.ROOT);

            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.getName().toLowerCase(Locale.ROOT).startsWith(partial)) {
                    completions.add(online.getName());
                }
            }
        }

        return completions;
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
        return LegacyComponentSerializer.legacySection().deserialize(TextColor.color(message));
    }
}
