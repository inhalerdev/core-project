package net.mineacle.core.homes.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.homes.gui.ConfirmDeleteHomeGui;
import net.mineacle.core.homes.gui.HomesMainGui;
import net.mineacle.core.homes.service.HomeGuiState;
import net.mineacle.core.homes.service.HomeService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class HomeCommand implements CommandExecutor, TabCompleter {

    private final Core core;
    private final HomeService homeService;
    private final HomeGuiState guiState;

    public HomeCommand(
            Core core,
            HomeService homeService,
            HomeGuiState guiState
    ) {
        this.core = core;
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
        String commandName = command.getName().toLowerCase(Locale.ROOT);

        if (!(sender instanceof Player player)) {
            sender.sendMessage(core.getMessage("general.players-only"));
            return true;
        }

        if (commandName.equals("mineaclehomes")) {
            handleAdminCommand(player, args);
            return true;
        }

        if (!player.hasPermission("mineaclehomes.use")) {
            player.sendMessage(core.getMessage("general.no-permission"));
            SoundService.guiError(player, core);
            return true;
        }

        switch (commandName) {
            case "home" -> HomesMainGui.open(core, player, homeService);
            case "sethome" -> handleSetHomeCommand(player, args);
            case "delhome" -> handleDeleteHomeCommand(player, args);
            case "renamehome" -> handleRenameHomeCommand(player, args);
            default -> {
                return false;
            }
        }
        return true;
    }

    private void handleSetHomeCommand(Player player, String[] args) {
        if (!homeService.canSetPersonalHomeHere(player)) {
            sendBoth(player, blockedWorldMessage());
            SoundService.guiError(player, core);
            return;
        }

        UUID uuid = player.getUniqueId();
        int maxHomes = homeService.getMaxHomes(player);
        String requestedName = args.length == 0
                ? ""
                : String.join(" ", args).trim();

        Integer existingId = null;

        if (!requestedName.isBlank()) {
            if (!homeService.isValidName(requestedName)) {
                player.sendMessage(core.getMessage("homes.invalid-name"));
                SoundService.guiError(player, core);
                return;
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
                return;
            }
            targetId = emptySlot;
        }

        String displayName = requestedName.isBlank()
                ? homeService.getDefaultDisplayName(targetId)
                : requestedName;

        homeService.set(uuid, targetId, player.getLocation(), displayName);
        String message = core.getMessage("homes.set")
                .replace("%home%", homeService.getDisplayName(uuid, targetId));
        sendBoth(player, message);
        SoundService.homeSet(player, core);
    }

    private void handleDeleteHomeCommand(Player player, String[] args) {
        if (args.length < 1) {
            player.sendMessage(TextColor.color("&cUsage: /delhome <home>"));
            SoundService.guiError(player, core);
            return;
        }

        String requestedName = String.join(" ", args).trim();
        int maxHomes = homeService.getMaxHomes(player);
        Integer id = homeService.findHomeIdByName(
                player.getUniqueId(),
                maxHomes,
                requestedName
        );

        if (id == null) {
            player.sendMessage(
                    core.getMessage("homes.not-set")
                            .replace("%home%", requestedName)
            );
            SoundService.guiError(player, core);
            return;
        }

        guiState.startPersonal(player, id);
        SoundService.guiClick(player, core);
        ConfirmDeleteHomeGui.openPlayerDelete(
                core,
                player,
                homeService.getDisplayName(player.getUniqueId(), id)
        );
    }

    private void handleRenameHomeCommand(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(
                    TextColor.color(
                            "&cUsage: /renamehome <existing home> <new home name>"
                    )
            );
            SoundService.guiError(player, core);
            return;
        }

        String oldName = args[0].trim();
        String newName = String.join(
                " ",
                Arrays.copyOfRange(args, 1, args.length)
        ).trim();

        if (!homeService.isValidName(newName)) {
            player.sendMessage(core.getMessage("homes.invalid-name"));
            SoundService.guiError(player, core);
            return;
        }

        UUID uuid = player.getUniqueId();
        int maxHomes = homeService.getMaxHomes(player);
        Integer id = homeService.findHomeIdByName(uuid, maxHomes, oldName);

        if (id == null) {
            player.sendMessage(
                    core.getMessage("homes.not-set")
                            .replace("%home%", oldName)
            );
            SoundService.guiError(player, core);
            return;
        }

        Integer duplicate = homeService.findByName(uuid, maxHomes, newName);
        if (duplicate != null && !duplicate.equals(id)) {
            player.sendMessage(
                    core.getMessage("homes.already-exists")
                            .replace("%home%", newName)
            );
            SoundService.guiError(player, core);
            return;
        }

        String oldDisplayName = homeService.getDisplayName(uuid, id);
        homeService.rename(uuid, id, newName);
        String message = core.getMessage("homes.renamed")
                .replace("%old_home%", oldDisplayName)
                .replace("%new_home%", homeService.getDisplayName(uuid, id));
        sendBoth(player, message);
        SoundService.guiConfirm(player, core);
    }

    private void handleAdminCommand(Player player, String[] args) {
        if (!player.hasPermission("mineaclehomes.admin")) {
            player.sendMessage(core.getMessage("general.no-permission"));
            SoundService.guiError(player, core);
            return;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            core.reloadCoreFiles();
            player.sendMessage(core.getMessage("general.reload-success"));
            SoundService.guiConfirm(player, core);
            return;
        }

        player.sendMessage(TextColor.color("&cUsage: /mineaclehomes reload"));
        SoundService.guiError(player, core);
    }

    private String blockedWorldMessage() {
        String configured = core.getMessagesConfig().getString("homes.blocked-world");
        if (configured != null && !configured.isBlank()) {
            return TextColor.color(configured);
        }
        return TextColor.color("&cYou cannot set homes in this world");
    }

    @Override
    public @NotNull List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            String @NotNull [] args
    ) {
        String commandName = command.getName().toLowerCase(Locale.ROOT);
        List<String> completions = new ArrayList<>();

        if (!(sender instanceof Player player)) {
            return completions;
        }

        if (commandName.equals("mineaclehomes")) {
            if (args.length == 1
                    && "reload".startsWith(args[0].toLowerCase(Locale.ROOT))) {
                completions.add("reload");
            }
            return completions;
        }

        if (commandName.equals("delhome") && args.length >= 1) {
            String partial = String.join(" ", args).toLowerCase(Locale.ROOT);
            for (String name : homeService.getSavedHomeNames(player)) {
                if (name.toLowerCase(Locale.ROOT).startsWith(partial)) {
                    completions.add(name);
                }
            }
            return completions;
        }

        if (commandName.equals("renamehome") && args.length == 1) {
            String partial = args[0].toLowerCase(Locale.ROOT);
            for (String name : homeService.getSavedHomeNames(player)) {
                if (name.toLowerCase(Locale.ROOT).startsWith(partial)) {
                    completions.add(name);
                }
            }
        }

        return completions;
    }

    private void sendBoth(Player player, String message) {
        String colored = TextColor.color(message);
        player.sendMessage(colored);
        player.sendActionBar(actionBar(colored));
    }

    private Component actionBar(String message) {
        return LegacyComponentSerializer.legacySection()
                .deserialize(TextColor.color(message));
    }
}
