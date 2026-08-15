package net.mineacle.core.common.chat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class ChatPauseService {

    private static final int PAUSE_SECONDS = 5;
    private static final long PAUSE_TICKS =
            PAUSE_SECONDS * 20L;
    private static final long PAUSE_NANOS =
            PAUSE_SECONDS * 1_000_000_000L;
    private static final long NANOS_PER_TICK =
            50_000_000L;
    private static final int MAX_BUFFERED_MESSAGES = 64;

    private static final Map<UUID, PauseState> PAUSES =
            new HashMap<>();

    private ChatPauseService() {
    }

    public static void pauseForLink(
            Core core,
            Player player
    ) {
        pause(
                core,
                player,
                true
        );
    }

    public static void pauseForPlus(
            Core core,
            Player player
    ) {
        /*
         * Mineacle+ prompts already write their store copy into chat before
         * SoundService.mineaclePlus(...) is called. Keep the warning in the
         * action bar so the clickable/store line remains the newest chat line.
         */
        pause(
                core,
                player,
                false
        );
    }

    public static void deliver(
            Core core,
            Player sender,
            Player recipient,
            Component message
    ) {
        if (core == null
                || recipient == null
                || message == null) {
            return;
        }

        if (!Bukkit.isPrimaryThread()) {
            core.getServer()
                    .getScheduler()
                    .runTask(
                            core,
                            () -> deliver(
                                    core,
                                    sender,
                                    recipient,
                                    message
                            )
                    );
            return;
        }

        if (!recipient.isOnline()) {
            return;
        }

        /*
         * A player may still intentionally talk while their incoming chat is
         * paused. Their own message remains visible to them immediately.
         */
        if (sender != null
                && sender.getUniqueId()
                .equals(
                        recipient.getUniqueId()
                )) {
            recipient.sendMessage(message);
            return;
        }

        UUID recipientId =
                recipient.getUniqueId();
        PauseState state =
                PAUSES.get(recipientId);

        if (state == null) {
            recipient.sendMessage(message);
            return;
        }

        if (System.nanoTime()
                >= state.expiresAtNanos) {
            resumeNow(
                    recipientId,
                    state
            );
            recipient.sendMessage(message);
            return;
        }

        if (state.buffer.size()
                >= MAX_BUFFERED_MESSAGES) {
            state.buffer.removeFirst();
            state.overflowed = true;
        }

        state.buffer.addLast(message);
    }

    public static void clear(UUID playerId) {
        if (playerId == null) {
            return;
        }

        PauseState state =
                PAUSES.remove(playerId);

        if (state != null
                && state.resumeTask != null) {
            state.resumeTask.cancel();
        }
    }

    public static void clearAll() {
        for (PauseState state :
                PAUSES.values()) {
            if (state.resumeTask != null) {
                state.resumeTask.cancel();
            }
        }

        PAUSES.clear();
    }

    private static void pause(
            Core core,
            Player player,
            boolean announceInChat
    ) {
        if (core == null
                || player == null
                || !player.isOnline()) {
            return;
        }

        if (!Bukkit.isPrimaryThread()) {
            core.getServer()
                    .getScheduler()
                    .runTask(
                            core,
                            () -> pause(
                                    core,
                                    player,
                                    announceInChat
                            )
                    );
            return;
        }

        UUID playerId =
                player.getUniqueId();
        PauseState previous =
                PAUSES.remove(playerId);
        ArrayDeque<Component> buffered =
                new ArrayDeque<>();
        boolean overflowed = false;

        if (previous != null) {
            if (previous.resumeTask != null) {
                previous.resumeTask.cancel();
            }

            buffered.addAll(
                    previous.buffer
            );
            overflowed =
                    previous.overflowed;
        }

        PauseState state =
                new PauseState(
                        System.nanoTime()
                                + PAUSE_NANOS,
                        buffered,
                        overflowed
                );

        PAUSES.put(
                playerId,
                state
        );

        state.resumeTask =
                core.getServer()
                        .getScheduler()
                        .runTaskLater(
                                core,
                                () -> resume(
                                        core,
                                        playerId,
                                        state
                                ),
                                PAUSE_TICKS
                        );

        Component actionBar =
                legacy(
                        "&eChat paused for 5 seconds"
                );
        player.sendActionBar(actionBar);

        if (announceInChat) {
            player.sendMessage(
                    legacy(
                            "&eChat paused for 5 seconds — "
                                    + "incoming messages will appear after"
                    )
            );
        }
    }

    private static void resume(
            Core core,
            UUID playerId,
            PauseState expected
    ) {
        PauseState current =
                PAUSES.get(playerId);

        if (current != expected) {
            return;
        }

        long remaining =
                current.expiresAtNanos
                        - System.nanoTime();

        if (remaining > 0L) {
            long remainingTicks =
                    Math.max(
                            1L,
                            (remaining
                                    + NANOS_PER_TICK
                                    - 1L)
                                    / NANOS_PER_TICK
                    );

            current.resumeTask =
                    core.getServer()
                            .getScheduler()
                            .runTaskLater(
                                    core,
                                    () -> resume(
                                            core,
                                            playerId,
                                            expected
                                    ),
                                    remainingTicks
                            );
            return;
        }

        resumeNow(
                playerId,
                current
        );
    }

    private static void resumeNow(
            UUID playerId,
            PauseState expected
    ) {
        PauseState current =
                PAUSES.get(playerId);

        if (current != expected) {
            return;
        }

        PAUSES.remove(playerId);

        if (current.resumeTask != null) {
            current.resumeTask.cancel();
            current.resumeTask = null;
        }

        Player player =
                Bukkit.getPlayer(playerId);

        if (player == null
                || !player.isOnline()) {
            current.buffer.clear();
            return;
        }

        if (current.overflowed) {
            player.sendMessage(
                    legacy(
                            "&eSome older chat was skipped "
                                    + "while the link was open"
                    )
            );
        }

        while (!current.buffer.isEmpty()) {
            player.sendMessage(
                    current.buffer.removeFirst()
            );
        }
    }

    private static Component legacy(
            String value
    ) {
        return LegacyComponentSerializer
                .legacySection()
                .deserialize(
                        TextColor.color(value)
                );
    }

    private static final class PauseState {

        private final long expiresAtNanos;
        private final ArrayDeque<Component> buffer;
        private boolean overflowed;
        private BukkitTask resumeTask;

        private PauseState(
                long expiresAtNanos,
                ArrayDeque<Component> buffer,
                boolean overflowed
        ) {
            this.expiresAtNanos =
                    expiresAtNanos;
            this.buffer = buffer;
            this.overflowed = overflowed;
        }
    }
}
