package net.mineacle.core.homes.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.homes.gui.ConfirmDeleteHomeGui;
import net.mineacle.core.homes.gui.HomesMainGui;
import net.mineacle.core.homes.service.HomeService;
import net.mineacle.core.homes.service.HomeWorldRules;
import net.mineacle.core.homes.service.TeleportService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.metadata.FixedMetadataValue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class HomeCommand implements CommandExecutor, TabCompleter {

    private static final String META_HOME_PENDING = "mh_pendingDelete";
    private static final String META_HOME_CONFIRM = "mh_deleteConfirm";

    private final Core core;
    private final HomeService homeService;
    private final HomeWorldRules worldRules;
    private final TeleportService teleportService;

    public HomeCommand(Core core, HomeService homeService, HomeWorldRules worldRules, TeleportService teleportService) {
        this.core = core;
        this.homeService = homeService;
        this.worldRules = worldRules;
        this.teleportService = teleportService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String commandName = command.getName().toLowerCase(Locale.ROOT);

        if (!(sender instanceof Player player)) {
            sender.sendMessage(core.getMessage("general.players-only"));
            return true;
        }

        if (commandName.equals("mineaclehomes")) {
            return handleAdminCommand(player, args);
        }

        if (!player.hasPermission("mineaclehomes.use")) {
            player.sendMessage(core.getMessage("general.no-permission"));
            SoundService.guiError(player, core);
            return true;
        }

        return switch (commandName) {
            case "home" -> handleHomeCommand(player);
            case "sethome" -> handleSetHomeCommand(player, args);
            case "delhome" -> handleDeleteHomeCommand(player, args);
            case "renamehome" -> handleRenameHomeCommand(player, args);
            default -> false;
        };
    }

    private boolean handleHomeCommand(Player player) {
        HomesMainGui.open(core, player, homeService);
        return true;
    }

    private boolean handleSetHomeCommand(Player player, String[] args) {
        if (!homeService.canSetPersonalHomeHere(player)) {
            String message = blockedWorldMessage();
            player.sendActionBar(actionBar(message));
            player.sendMessage(message);
            SoundService.guiError(player, core);
            return true;
        }

        UUID uuid = player.getUniqueId();
        int maxHomes = homeService.getMaxHomes(player);
        String requestedName = args.length == 0 ? "" : String.join(" ", args).trim();

        Integer existingId = null;

        if (!requestedName.isBlank()) {
            if (!homeService.isValidName(requestedName)) {
                player.sendMessage(core.getMessage("homes.invalid-name"));
                SoundService.guiError(player, core);
                return true;
            }

            existingId = homeService.findByName(uuid, maxHomes, requestedName);
        }

        int targetId;

        if (existingId != null) {
            targetId = existingId;
        } else {
            Integer emptySlot = homeService.findFirstEmptySlot(player);

            if (emptySlot == null) {
                player.sendMessage(core.getMessage("homes.no-empty-slot"));
                SoundService.guiError(player, core);
                return true;
            }

            targetId = emptySlot;
        }

        String displayName = requestedName.isBlank()
                ? homeService.getDefaultDisplayName(targetId)
                : requestedName;

        homeService.set(uuid, targetId, player.getLocation(), displayName);

        String message = core.getMessage("homes.set")
                .replace("%home%", homeService.getDisplayName(uuid, targetId));

        player.sendActionBar(actionBar(message));
        player.sendMessage(message);
        SoundService.homeSet(player, core);
        return true;
    }

    private boolean handleDeleteHomeCommand(Player player, String[] args) {
        if (args.length < 1) {
            player.sendMessage("§cUsage: /delhome <home>");
            SoundService.guiError(player, core);
            return true;
        }

        String requestedName = String.join(" ", args).trim();
        int maxHomes = homeService.getMaxHomes(player);
        Integer id = homeService.findHomeIdByName(player.getUniqueId(), maxHomes, requestedName);

        if (id == null) {
            player.sendMessage(core.getMessage("homes.not-set").replace("%home%", requestedName));
            SoundService.guiError(player, core);
            return true;
        }

        player.setMetadata(META_HOME_PENDING, new FixedMetadataValue(core, id));
        player.setMetadata(META_HOME_CONFIRM, new FixedMetadataValue(core, 0));

        SoundService.guiClick(player, core);
        ConfirmDeleteHomeGui.openPlayerDelete(core, player, id, homeService.getDisplayName(player.getUniqueId(), id));
        return true;
    }

    private boolean handleRenameHomeCommand(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage("§cUsage: /renamehome <existing home> <new home name>");
            SoundService.guiError(player, core);
            return true;
        }

        String oldName = args[0].trim();
        String newName = String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();

        if (!homeService.isValidName(newName)) {
            player.sendMessage(core.getMessage("homes.invalid-name"));
            SoundService.guiError(player, core);
            return true;
        }

        UUID uuid = player.getUniqueId();
        int maxHomes = homeService.getMaxHomes(player);
        Integer id = homeService.findHomeIdByName(uuid, maxHomes, oldName);

        if (id == null) {
            player.sendMessage(core.getMessage("homes.not-set").replace("%home%", oldName));
            SoundService.guiError(player, core);
            return true;
        }

        Integer duplicate = homeService.findByName(uuid, maxHomes, newName);

        if (duplicate != null && duplicate != id) {
            player.sendMessage(core.getMessage("homes.already-exists").replace("%home%", newName));
            SoundService.guiError(player, core);
            return true;
        }

        String oldDisplayName = homeService.getDisplayName(uuid, id);
        homeService.rename(uuid, id, newName);

        String message = core.getMessage("homes.renamed")
                .replace("%old_home%", oldDisplayName)
                .replace("%new_home%", homeService.getDisplayName(uuid, id));

        player.sendActionBar(actionBar(message));
        player.sendMessage(message);
        SoundService.guiConfirm(player, core);
        return true;
    }

    private boolean handleAdminCommand(Player player, String[] args) {
        if (!player.hasPermission("mineaclehomes.admin")) {
            player.sendMessage(core.getMessage("general.no-permission"));
            SoundService.guiError(player, core);
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            core.reloadCoreFiles();
            player.sendMessage(core.getMessage("general.reload-success"));
            SoundService.guiConfirm(player, core);
            return true;
        }

        player.sendMessage("§cUsage: /mineaclehomes reload");
        SoundService.guiError(player, core);
        return true;
    }

    private String blockedWorldMessage() {
        String configured = core.getMessagesConfig().getString("homes.blocked-world");

        if (configured != null && !configured.isBlank()) {
            return TextColor.color(configured);
        }

        return TextColor.color("&cYou cannot set homes in this world");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String commandName = command.getName().toLowerCase(Locale.ROOT);
        List<String> completions = new ArrayList<>();

        if (!(sender instanceof Player player)) {
            return completions;
        }

        if (commandName.equals("mineaclehomes")) {
            if (args.length == 1 && "reload".startsWith(args[0].toLowerCase(Locale.ROOT))) {
                completions.add("reload");
            }

            return completions;
        }

        if (commandName.equals("sethome")) {
            return completions;
        }

        if (commandName.equals("delhome")) {
            if (args.length >= 1) {
                String partial = String.join(" ", args).toLowerCase(Locale.ROOT);

                for (String name : homeService.getSavedHomeNames(player)) {
                    if (name.toLowerCase(Locale.ROOT).startsWith(partial)) {
                        completions.add(name);
                    }
                }
            }

            return completions;
        }

        if (commandName.equals("renamehome")) {
            if (args.length == 1) {
                String partial = args[0].toLowerCase(Locale.ROOT);

                for (String name : homeService.getSavedHomeNames(player)) {
                    if (name.toLowerCase(Locale.ROOT).startsWith(partial)) {
                        completions.add(name);
                    }
                }
            }

            return completions;
        }

        return completions;
    }

    private Component actionBar(String message) {
        return LegacyComponentSerializer.legacySection().deserialize(TextColor.color(message));
    }
}
