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

        if (!viewer.hasPermission(type.permission())) {
            service.fail(
                    viewer,
                    OpenResult.NO_PERMISSION,
                    type
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
            String offlinePublicName =
                    exactOfflinePublicName(
                            viewer,
                            args[0]
                    );

            if (offlinePublicName == null) {
                service.fail(
                        viewer,
                        OpenResult.TARGET_UNAVAILABLE,
                        type
                );
                return true;
            }

            offlineService.open(
                    viewer,
                    offlinePublicName,
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

        for (String value : onlineCompletions(
                viewer,
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

    private String exactOfflinePublicName(
            Player viewer,
            String input
    ) {
        String normalized = normalize(input);

        if (normalized.isEmpty()) {
            return null;
        }

        String match = null;

        for (String value : offlineService.completions(
                viewer,
                type,
                input
        )) {
            if (!normalize(value).equals(normalized)) {
                continue;
            }

            match = value;
        }

        return match;
    }

    private List<String> onlineCompletions(
            Player viewer,
            String input
    ) {
        if (!viewer.hasPermission(type.permission())) {
            return List.of();
        }

        String partial = normalize(input);
        boolean includeSelf =
                viewer.hasPermission(type.selfPermission());
        boolean includeHidden =
                viewer.hasPermission(
                        AdminInspectService.HIDDEN_PERMISSION
                );
        boolean bypassProtected =
                viewer.hasPermission(
                        AdminInspectService
                                .PROTECTED_BYPASS_PERMISSION
                );

        Map<String, String> unique =
                new LinkedHashMap<>();

        for (Player target : Bukkit.getOnlinePlayers()) {
            if (!target.isOnline() || target.isDead()) {
                continue;
            }

            boolean self = target.getUniqueId()
                    .equals(viewer.getUniqueId());

            if (self && !includeSelf) {
                continue;
            }

            boolean hidden = VanishRegistry.isVanished(
                    target.getUniqueId()
            );

            if (!self && hidden && !includeHidden) {
                continue;
            }

            if (!self
                    && !viewer.canSee(target)
                    && !includeHidden) {
                continue;
            }

            if (!self
                    && target.hasPermission(
                    AdminInspectService.PROTECTED_PERMISSION
            )
                    && !bypassProtected) {
                continue;
            }

            String publicName =
                    DisplayNames.commandDisplayName(target);

            if (publicName == null || publicName.isBlank()) {
                continue;
            }

            if (!partial.isEmpty()
                    && !normalize(publicName)
                    .startsWith(partial)) {
                continue;
            }

            unique.putIfAbsent(
                    normalize(publicName),
                    publicName
            );
        }

        List<String> result =
                new ArrayList<>(unique.values());
        result.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(result);
    }

    private Player resolveOnlineForInspector(
            Player viewer,
            String input
    ) {
        String normalized = normalize(input);
        Player match = null;
        boolean includeHidden =
                viewer.hasPermission(
                        AdminInspectService.HIDDEN_PERMISSION
                );
        boolean bypassProtected =
                viewer.hasPermission(
                        AdminInspectService
                                .PROTECTED_BYPASS_PERMISSION
                );

        for (Player online : Bukkit.getOnlinePlayers()) {
            boolean self = online.getUniqueId()
                    .equals(viewer.getUniqueId());
            boolean hidden = VanishRegistry.isVanished(
                    online.getUniqueId()
            );

            if (!self && hidden && !includeHidden) {
                continue;
            }

            if (!self
                    && !viewer.canSee(online)
                    && !includeHidden) {
                continue;
            }

            if (!self
                    && online.hasPermission(
                    AdminInspectService.PROTECTED_PERMISSION
            )
                    && !bypassProtected) {
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
