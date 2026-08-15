package net.mineacle.core.homes.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.gui.MenuHistory;
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

    private static final List<String> HOME_SUBCOMMANDS =
            List.of("set", "del", "rename");

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
        String commandName = command.getName()
                .toLowerCase(Locale.ROOT);

        if (!(sender instanceof Player player)) {
            sender.sendMessage(
                    core.getMessage("general.players-only")
            );
            return true;
        }

        if (commandName.equals("mineaclehomes")) {
            handleAdminCommand(player, args);
            return true;
        }

        if (!player.hasPermission("mineaclehomes.use")) {
            player.sendMessage(
                    core.getMessage("general.no-permission")
            );
            SoundService.guiError(player, core);
            return true;
        }

        switch (commandName) {
            case "home" -> handleHomeCommand(player, args);
            case "sethome" -> handleSetHomeCommand(player, args);
            case "delhome" -> handleDeleteHomeCommand(player, args);
            case "renamehome" -> handleRenameHomeCommand(player, args);
            default -> {
                return false;
            }
        }
        return true;
    }

    private void handleHomeCommand(
            Player player,
            String[] args
    ) {
        if (args.length == 0) {
            openHomesRoot(player);
            return;
        }

        String subcommand = args[0]
                .toLowerCase(Locale.ROOT);
        String[] remaining = Arrays.copyOfRange(
                args,
                1,
                args.length
        );

        switch (subcommand) {
            case "set" -> handleSetHomeCommand(
                    player,
                    remaining
            );
            case "del", "delete" -> handleDeleteHomeCommand(
                    player,
                    remaining
            );
            case "rename" -> handleRenameHomeCommand(
                    player,
                    remaining
            );
            default -> {
                player.sendMessage(
                        TextColor.color(
                                "&cUsage: /home [set|del|rename]"
                        )
                );
                SoundService.guiError(player, core);
            }
        }
    }

    private void openHomesRoot(Player player) {
        MenuHistory.openRoot(
                core,
                player,
                () -> HomesMainGui.open(
                        core,
                        player,
                        homeService
                )
        );
    }

    private void handleSetHomeCommand(
            Player player,
            String[] args
    ) {
        if (homeService.personalHomeSetBlocked(player)) {
            sendBoth(player, blockedWorldMessage());
            SoundService.guiError(player, core);
            return;
        }

        UUID playerId = player.getUniqueId();
        String requestedName = args.length == 0
                ? ""
                : String.join(" ", args).trim();
        Integer existingId = null;

        if (!requestedName.isBlank()) {
            if (homeService.invalidName(requestedName)) {
                player.sendMessage(
                        core.getMessage("homes.invalid-name")
                );
                SoundService.guiError(player, core);
                return;
            }

            Integer anyExisting = homeService.findAnyByName(
                    playerId,
                    requestedName
            );

            if (anyExisting != null) {
                if (homeService.slotLocked(
                        player,
                        anyExisting
                )) {
                    player.sendMessage(
                            core.getMessage("homes.already-exists")
                                    .replace(
                                            "%home%",
                                            requestedName
                                    )
                    );
                    SoundService.guiError(player, core);
                    return;
                }

                existingId = anyExisting;
            }
        }

        int targetId;

        if (existingId != null) {
            /*
             * Updating an already-saved grandfathered Mineacle+ home is
             * allowed. This moves the existing entitlement; it does not
             * create a new paid slot.
             */
            targetId = existingId;
        } else {
            /*
             * New homes are created only inside the player's current active
             * entitlement. A former Plus member cannot refill an empty paid
             * slot without regaining mineacle.plus.
             */
            Integer emptySlot = homeService.findFirstEmptySlot(player);

            if (emptySlot == null) {
                player.sendMessage(
                        core.getMessage("homes.no-empty-slot")
                );
                SoundService.guiError(player, core);
                return;
            }

            targetId = emptySlot;
        }

        String displayName = requestedName.isBlank()
                ? homeService.getDefaultDisplayName(targetId)
                : requestedName;

        if (homeService.nameUnavailableForSlot(
                playerId,
                targetId,
                displayName
        )) {
            player.sendMessage(
                    core.getMessage("homes.already-exists")
                            .replace("%home%", displayName)
            );
            SoundService.guiError(player, core);
            return;
        }

        homeService.set(
                playerId,
                targetId,
                player.getLocation(),
                displayName
        );

        String message = core.getMessage("homes.set")
                .replace(
                        "%home%",
                        homeService.getDisplayName(
                                playerId,
                                targetId
                        )
                );
        sendBoth(player, message);
        SoundService.homeSet(player, core);
    }

    private void handleDeleteHomeCommand(
            Player player,
            String[] args
    ) {
        if (args.length < 1) {
            player.sendMessage(
                    TextColor.color(
                            "&cUsage: /delhome <home>"
                    )
            );
            SoundService.guiError(player, core);
            return;
        }

        String requestedName = String.join(" ", args).trim();
        Integer id = homeService.findAccessibleHomeIdByName(
                player,
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
        MenuHistory.openRoot(
                core,
                player,
                () -> ConfirmDeleteHomeGui.openPlayerDelete(
                        core,
                        player,
                        homeService.getDisplayName(
                                player.getUniqueId(),
                                id
                        )
                )
        );
    }

    private void handleRenameHomeCommand(
            Player player,
            String[] args
    ) {
        RenameRequest request = parseRenameRequest(player, args);

        if (request == null) {
            player.sendMessage(
                    TextColor.color(
                            "&cUsage: /renamehome <existing home> <new home name>"
                    )
            );
            SoundService.guiError(player, core);
            return;
        }

        String newName = request.newName();

        if (homeService.invalidName(newName)) {
            player.sendMessage(
                    core.getMessage("homes.invalid-name")
            );
            SoundService.guiError(player, core);
            return;
        }

        UUID playerId = player.getUniqueId();

        if (homeService.nameUnavailableForSlot(
                playerId,
                request.homeId(),
                newName
        )) {
            player.sendMessage(
                    core.getMessage("homes.already-exists")
                            .replace("%home%", newName)
            );
            SoundService.guiError(player, core);
            return;
        }

        String oldDisplayName = homeService.getDisplayName(
                playerId,
                request.homeId()
        );
        homeService.rename(
                playerId,
                request.homeId(),
                newName
        );

        String message = core.getMessage("homes.renamed")
                .replace("%old_home%", oldDisplayName)
                .replace(
                        "%new_home%",
                        homeService.getDisplayName(
                                playerId,
                                request.homeId()
                        )
                );
        sendBoth(player, message);
        SoundService.guiConfirm(player, core);
    }

    private RenameRequest parseRenameRequest(
            Player player,
            String[] args
    ) {
        if (args.length < 2) {
            return null;
        }

        /*
         * Longest saved-name prefix wins, allowing multi-word names such as:
         * /renamehome My Base New Base
         *
         * The lookup includes grandfathered Mineacle+ slots.
         */
        for (int split = args.length - 1; split >= 1; split--) {
            String oldName = String.join(
                    " ",
                    Arrays.copyOfRange(args, 0, split)
            ).trim();
            String newName = String.join(
                    " ",
                    Arrays.copyOfRange(args, split, args.length)
            ).trim();
            Integer homeId = homeService.findAccessibleHomeIdByName(
                    player,
                    oldName
            );

            if (homeId != null && !newName.isBlank()) {
                return new RenameRequest(
                        homeId,
                        newName
                );
            }
        }

        return null;
    }

    private void handleAdminCommand(
            Player player,
            String[] args
    ) {
        if (!player.hasPermission("mineaclehomes.admin")) {
            player.sendMessage(
                    core.getMessage("general.no-permission")
            );
            SoundService.guiError(player, core);
            return;
        }

        if (args.length == 1
                && args[0].equalsIgnoreCase("reload")) {
            core.reloadCoreFiles();
            player.sendMessage(
                    core.getMessage("general.reload-success")
            );
            SoundService.guiConfirm(player, core);
            return;
        }

        player.sendMessage(
                TextColor.color(
                        "&cUsage: /mineaclehomes reload"
                )
        );
        SoundService.guiError(player, core);
    }

    private String blockedWorldMessage() {
        String configured = core.getMessagesConfig().getString(
                "homes.blocked-world",
                "&cYou cannot set homes in this world"
        );
        return TextColor.color(configured);
    }

    @Override
    public @NotNull List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            String @NotNull [] args
    ) {
        String commandName = command.getName()
                .toLowerCase(Locale.ROOT);
        List<String> completions = new ArrayList<>();

        if (!(sender instanceof Player player)) {
            return completions;
        }

        if (commandName.equals("mineaclehomes")) {
            if (player.hasPermission("mineaclehomes.admin")
                    && args.length == 1
                    && "reload".startsWith(
                    args[0].toLowerCase(Locale.ROOT)
            )) {
                completions.add("reload");
            }
            return completions;
        }

        if (commandName.equals("home")) {
            return homeTabComplete(player, args);
        }

        if (commandName.equals("delhome")
                && args.length >= 1) {
            return matchingHomeNames(
                    player,
                    String.join(" ", args)
            );
        }

        if (commandName.equals("renamehome")
                && args.length == 1) {
            return matchingHomeNames(player, args[0]);
        }

        return completions;
    }

    private List<String> homeTabComplete(
            Player player,
            String[] args
    ) {
        if (args.length == 1) {
            String partial = args[0]
                    .toLowerCase(Locale.ROOT);
            List<String> matches = new ArrayList<>();

            for (String option : HOME_SUBCOMMANDS) {
                if (option.startsWith(partial)) {
                    matches.add(option);
                }
            }

            return List.copyOf(matches);
        }

        if (args.length >= 2) {
            String subcommand = args[0]
                    .toLowerCase(Locale.ROOT);

            if (subcommand.equals("del")
                    || subcommand.equals("delete")
                    || subcommand.equals("rename")) {
                String partial = String.join(
                        " ",
                        Arrays.copyOfRange(
                                args,
                                1,
                                args.length
                        )
                );
                return matchingHomeNames(player, partial);
            }
        }

        return List.of();
    }

    private List<String> matchingHomeNames(
            Player player,
            String input
    ) {
        String partial = input == null
                ? ""
                : input.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();

        for (String name : homeService.getSavedHomeNames(player)) {
            if (name.toLowerCase(Locale.ROOT)
                    .startsWith(partial)) {
                matches.add(name);
            }
        }

        return List.copyOf(matches);
    }

    private void sendBoth(Player player, String message) {
        String colored = TextColor.color(message);
        player.sendMessage(colored);
        player.sendActionBar(actionBar(colored));
    }

    private Component actionBar(String message) {
        return LegacyComponentSerializer
                .legacySection()
                .deserialize(
                        TextColor.color(message)
                );
    }

    private record RenameRequest(
            int homeId,
            String newName
    ) {
    }
}
