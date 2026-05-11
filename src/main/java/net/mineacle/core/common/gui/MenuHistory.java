package net.mineacle.core.common.gui;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class MenuHistory {

    private static final Map<UUID, Deque<Runnable>> BACK_STACKS = new ConcurrentHashMap<>();
    private static final Map<UUID, Boolean> PROGRAMMATIC_OPEN = new ConcurrentHashMap<>();

    private MenuHistory() {
    }

    public static void openRoot(Plugin plugin, Player player, Runnable openAction) {
        clear(player);
        openWithoutBackTrigger(plugin, player, openAction);
    }

    public static void openChild(Plugin plugin, Player player, Runnable previousMenuAction, Runnable openAction) {
        BACK_STACKS.computeIfAbsent(player.getUniqueId(), ignored -> new ArrayDeque<>()).push(previousMenuAction);
        openWithoutBackTrigger(plugin, player, openAction);
    }

    public static void openWithoutBackTrigger(Plugin plugin, Player player, Runnable openAction) {
        UUID uuid = player.getUniqueId();
        PROGRAMMATIC_OPEN.put(uuid, true);

        openAction.run();

        Bukkit.getScheduler().runTaskLater(plugin, () -> PROGRAMMATIC_OPEN.remove(uuid), 2L);
    }

    public static void handleClose(Plugin plugin, Player player) {
        UUID uuid = player.getUniqueId();

        if (PROGRAMMATIC_OPEN.remove(uuid) != null) {
            return;
        }

        Deque<Runnable> stack = BACK_STACKS.get(uuid);

        if (stack == null || stack.isEmpty()) {
            clear(player);
            return;
        }

        Runnable previousMenu = stack.pop();

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) {
                clear(player);
                return;
            }

            openWithoutBackTrigger(plugin, player, previousMenu);
        }, 1L);
    }

    public static void clear(Player player) {
        UUID uuid = player.getUniqueId();
        BACK_STACKS.remove(uuid);
        PROGRAMMATIC_OPEN.remove(uuid);
    }
}