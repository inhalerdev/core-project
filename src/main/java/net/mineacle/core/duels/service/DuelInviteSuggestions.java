package net.mineacle.core.duels.service;

import net.mineacle.core.Core;
import net.mineacle.core.duels.model.DuelInvite;
import org.bukkit.entity.Player;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public final class DuelInviteSuggestions {

    private final Core core;
    private final DuelService duelService;

    private final Field incomingByTargetField;
    private final Field outgoingByChallengerField;

    private boolean reflectionWarningLogged;

    public DuelInviteSuggestions(
            Core core,
            DuelService duelService
    ) {
        this.core = core;
        this.duelService = duelService;
        this.incomingByTargetField = field(
                "incomingByTarget"
        );
        this.outgoingByChallengerField = field(
                "outgoingByChallenger"
        );
    }

    public List<String> options(Player player) {
        if (player == null) {
            return List.of();
        }

        UUID playerId = player.getUniqueId();
        List<String> options = new ArrayList<>(3);

        DuelInvite incoming = invite(
                incomingByTargetField,
                playerId
        );

        if (active(incoming)) {
            options.add("accept");
            options.add("deny");
        }

        DuelInvite outgoing = invite(
                outgoingByChallengerField,
                playerId
        );

        if (active(outgoing)) {
            options.add("cancel");
        }

        return List.copyOf(options);
    }

    private boolean active(DuelInvite invite) {
        return invite != null && !invite.expired();
    }

    @SuppressWarnings("unchecked")
    private DuelInvite invite(
            Field field,
            UUID playerId
    ) {
        if (field == null || playerId == null) {
            return null;
        }

        try {
            Object value = field.get(duelService);

            if (!(value instanceof Map<?, ?> map)) {
                return null;
            }

            Object invite = map.get(playerId);

            return invite instanceof DuelInvite duelInvite
                    ? duelInvite
                    : null;
        } catch (IllegalAccessException exception) {
            warnOnce(exception);
            return null;
        }
    }

    private Field field(String name) {
        try {
            Field field = DuelService.class
                    .getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException
                 | SecurityException exception) {
            warnOnce(exception);
            return null;
        }
    }

    private void warnOnce(Exception exception) {
        if (reflectionWarningLogged) {
            return;
        }

        reflectionWarningLogged = true;
        core.getLogger().log(
                Level.WARNING,
                "Could not inspect Duel invite state for "
                        + "contextual tab completion",
                exception
        );
    }
}
