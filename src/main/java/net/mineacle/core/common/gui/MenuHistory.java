package net.mineacle.core.common.gui;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.Plugin;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class MenuHistory {

    private static final int MAX_BACK_DEPTH = 24;

    private static final Map<UUID, HistoryState> STATES =
            new ConcurrentHashMap<>();

    private MenuHistory() {
    }

    public static void openRoot(
            Plugin plugin,
            Player player,
            Runnable openAction
    ) {
        validate(plugin, player, openAction);

        HistoryState state = STATES.computeIfAbsent(
                player.getUniqueId(),
                ignored -> new HistoryState()
        );

        synchronized (state) {
            state.backStack.clear();
        }

        openTracked(plugin, player, state, openAction);
    }

    public static void openChild(
            Plugin plugin,
            Player player,
            Runnable previousMenuAction,
            Runnable openAction
    ) {
        validate(plugin, player, openAction);
        Objects.requireNonNull(
                previousMenuAction,
                "previousMenuAction"
        );

        HistoryState state = STATES.computeIfAbsent(
                player.getUniqueId(),
                ignored -> new HistoryState()
        );

        synchronized (state) {
            state.backStack.push(previousMenuAction);

            while (state.backStack.size() > MAX_BACK_DEPTH) {
                state.backStack.removeLast();
            }
        }

        openTracked(plugin, player, state, openAction);
    }

    public static void openWithoutBackTrigger(
            Plugin plugin,
            Player player,
            Runnable openAction
    ) {
        validate(plugin, player, openAction);

        HistoryState state = STATES.computeIfAbsent(
                player.getUniqueId(),
                ignored -> new HistoryState()
        );

        openTracked(plugin, player, state, openAction);
    }

    /**
     * Opens the previous tracked menu immediately. Use this for explicit Back
     * buttons. The remaining stack stays intact, so repeated Back actions and
     * ESC continue walking through the complete menu path.
     */
    public static boolean back(
            Plugin plugin,
            Player player
    ) {
        if (plugin == null || player == null) {
            return false;
        }

        UUID playerId = player.getUniqueId();
        HistoryState state = STATES.get(playerId);

        if (state == null) {
            return false;
        }

        Runnable previous;

        synchronized (state) {
            if (state.backStack.isEmpty()) {
                return false;
            }

            previous = state.backStack.pop();
        }

        openTracked(plugin, player, state, previous);
        return true;
    }

    public static boolean handleClose(
            Plugin plugin,
            Player player
    ) {
        if (player == null) {
            return false;
        }

        HistoryState state = STATES.get(player.getUniqueId());

        if (state == null) {
            return false;
        }

        Inventory inventory;

        synchronized (state) {
            inventory = state.currentInventory;
        }

        return handleClose(plugin, player, inventory);
    }

    public static boolean handleClose(
            Plugin plugin,
            Player player,
            Inventory closedInventory
    ) {
        if (plugin == null || player == null) {
            return false;
        }

        UUID playerId = player.getUniqueId();
        HistoryState state = STATES.get(playerId);

        if (state == null) {
            return false;
        }

        Runnable previousMenu;

        synchronized (state) {
            if (state.suppressedCloses > 0) {
                state.suppressedCloses--;

                if (closedInventory == state.currentInventory) {
                    state.currentInventory = null;
                }

                return false;
            }

            if (state.currentInventory != null
                    && closedInventory != null
                    && closedInventory != state.currentInventory) {
                return false;
            }

            state.currentInventory = null;

            if (state.backStack.isEmpty()) {
                STATES.remove(playerId, state);
                return false;
            }

            previousMenu = state.backStack.pop();
        }

        Bukkit.getScheduler().runTaskLater(
                plugin,
                () -> {
                    if (!player.isOnline()) {
                        clear(player);
                        return;
                    }

                    HistoryState current = STATES.get(playerId);

                    if (current != state) {
                        return;
                    }

                    openTracked(
                            plugin,
                            player,
                            state,
                            previousMenu
                    );
                },
                1L
        );

        return true;
    }

    public static boolean isTracked(Player player) {
        return player != null
                && STATES.containsKey(player.getUniqueId());
    }

    /**
     * Closes a menu for chat/search input without destroying its back stack.
     * The workflow can reopen the same menu with openWithoutBackTrigger(...)
     * and ESC will still return to the menu that preceded it.
     */
    public static void closeForInput(
            Plugin plugin,
            Player player
    ) {
        if (plugin == null || player == null) {
            return;
        }

        HistoryState state = STATES.get(player.getUniqueId());

        if (state == null) {
            player.closeInventory();
            return;
        }

        synchronized (state) {
            if (state.currentInventory != null) {
                state.suppressedCloses++;
            }

            state.currentInventory = null;
        }

        player.closeInventory();
    }

    /**
     * Deliberately exits the complete GUI workflow.
     */
    public static void close(
            Plugin plugin,
            Player player
    ) {
        if (plugin == null || player == null) {
            return;
        }

        HistoryState state = STATES.get(player.getUniqueId());

        if (state != null) {
            synchronized (state) {
                state.backStack.clear();

                if (state.currentInventory != null) {
                    state.suppressedCloses++;
                }
            }
        }

        player.closeInventory();
        clear(player);
    }

    public static void clear(Player player) {
        if (player != null) {
            STATES.remove(player.getUniqueId());
        }
    }

    public static void clear(UUID playerId) {
        if (playerId != null) {
            STATES.remove(playerId);
        }
    }

    public static void clearAll() {
        STATES.clear();
    }

    private static void openTracked(
            Plugin plugin,
            Player player,
            HistoryState state,
            Runnable openAction
    ) {
        UUID playerId = player.getUniqueId();
        long generation;

        synchronized (state) {
            if (state.currentInventory != null) {
                state.suppressedCloses++;
            }

            generation = ++state.generation;
        }

        try {
            openAction.run();
        } catch (RuntimeException | Error exception) {
            synchronized (state) {
                if (state.suppressedCloses > 0) {
                    state.suppressedCloses--;
                }

                if (state.currentInventory == null
                        && state.backStack.isEmpty()) {
                    STATES.remove(playerId, state);
                }
            }

            throw exception;
        }

        Inventory current = currentExternalInventory(player);

        synchronized (state) {
            state.currentInventory = current;

            if (current == null) {
                if (state.backStack.isEmpty()) {
                    state.suppressedCloses = 0;
                    STATES.remove(playerId, state);
                }

                return;
            }
        }

        Bukkit.getScheduler().runTaskLater(
                plugin,
                () -> {
                    HistoryState currentState = STATES.get(playerId);

                    if (currentState != state) {
                        return;
                    }

                    synchronized (state) {
                        if (state.generation == generation) {
                            state.suppressedCloses = 0;
                        }
                    }
                },
                2L
        );
    }

    private static Inventory currentExternalInventory(
            Player player
    ) {
        if (player == null || !player.isOnline()) {
            return null;
        }

        Inventory top = player.getOpenInventory().getTopInventory();
        InventoryType type = top.getType();

        if (type == InventoryType.CRAFTING
                || type == InventoryType.CREATIVE
                || type == InventoryType.PLAYER) {
            return null;
        }

        return top;
    }

    private static void validate(
            Plugin plugin,
            Player player,
            Runnable openAction
    ) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(openAction, "openAction");
    }

    private static final class HistoryState {

        private final Deque<Runnable> backStack =
                new ArrayDeque<>();

        private Inventory currentInventory;
        private int suppressedCloses;
        private long generation;
    }
}
