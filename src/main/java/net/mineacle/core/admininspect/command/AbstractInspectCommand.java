package net.mineacle.core.admininspect.command;

import net.mineacle.core.Core;
import net.mineacle.core.admininspect.service.AdminInspectService;
import net.mineacle.core.admininspect.service.AdminInspectService.InspectType;
import net.mineacle.core.admininspect.service.AdminInspectService.OpenResult;
import net.mineacle.core.admininspect.service.OfflineInspectService;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.player.VanishRegistry;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

abstract class AbstractInspectCommand
        implements CommandExecutor, TabCompleter {

    private final Core core;
    private final AdminInspectService service;
    private final OfflineInspectService offlineService;
    private final InspectType type;

    protected AbstractInspectCommand(
            Core core,
            AdminInspectService service,
            OfflineInspectService offlineService,
            InspectType type
    ) {
        this.core = core;
        this.service = service;
        this.offlineService = offlineService;
        this.type = type;
    }

    @Override
    public final boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String @NotNull [] args
    ) {
        if (!(sender instanceof Player viewer)) {
            sender.sendMessage(
                    core.getMessage("general.players-only")
            );
            return true;
        }

        if (args.length != 1) {
            service.fail(
                    viewer,
                    OpenResult.USAGE,
                    type
            );
            return true;
        }

        Player target = resolveOnlineForInspector(
                viewer,
                args[0]
        );

        if (target == null) {
            offlineService.open(
                    viewer,
                    args[0],
                    type
            );
            return true;
        }

        OpenResult result = service.open(
                viewer,
                target,
                type
        );

        if (result != OpenResult.SUCCESS) {
            service.fail(
                    viewer,
                    result,
                    type
            );
        }

        return true;
    }

    @Override
    public final List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String @NotNull [] args
    ) {
        if (!(sender instanceof Player viewer)
                || args.length != 1) {
            return List.of();
        }

        Map<String, String> merged =
                new LinkedHashMap<>();

        for (String value : service.completions(
                viewer,
                type,
                args[0]
        )) {
            merged.putIfAbsent(
                    value.toLowerCase(Locale.ROOT),
                    value
            );
        }

        for (String value : offlineService.completions(
                viewer,
                type,
                args[0]
        )) {
            merged.putIfAbsent(
                    value.toLowerCase(Locale.ROOT),
                    value
            );
        }

        List<String> result =
                new ArrayList<>(merged.values());
        result.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(result);
    }

    private Player resolveOnlineForInspector(
            Player viewer,
            String input
    ) {
        String normalized = normalize(input);
        Player match = null;

        for (Player online : Bukkit.getOnlinePlayers()) {
            boolean hidden = VanishRegistry.isVanished(
                    online.getUniqueId()
            );
            boolean authorizedHidden =
                    viewer.hasPermission(
                            AdminInspectService.HIDDEN_PERMISSION
                    );

            if (!viewer.canSee(online)
                    && !(hidden && authorizedHidden)) {
                continue;
            }

            if (!normalize(
                    DisplayNames.commandDisplayName(online)
            ).equals(normalized)) {
                continue;
            }

            if (match != null
                    && !match.getUniqueId()
                    .equals(online.getUniqueId())) {
                return null;
            }

            match = online;
        }

        return match;
    }

    private String normalize(String input) {
        return TextColor.strip(input == null ? "" : input)
                .trim()
                .toLowerCase(Locale.ROOT);
    }
}
