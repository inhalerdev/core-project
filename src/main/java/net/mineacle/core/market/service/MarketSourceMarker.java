package net.mineacle.core.market.service;

import net.mineacle.core.Core;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.UUID;
import java.util.logging.Level;

/**
 * Durable proof that a Market transaction's source items reached playerdata.
 *
 * <p>The PREPARED journal is written before inventory mutation. Only after the
 * source stacks are removed do we persist SOURCE_REMOVED into the player's PDC
 * with Player#saveData(). A PREPARED journal without this durable marker is
 * therefore safe to abort after a crash.</p>
 */
public final class MarketSourceMarker {

    public enum Phase {
        SOURCE_REMOVED
    }

    public record Marker(
            UUID transactionId,
            Phase phase
    ) {
    }

    private final Core core;
    private final NamespacedKey key;

    public MarketSourceMarker(Core core) {
        this.core = core;
        this.key = new NamespacedKey(
                core,
                "market_source_transaction"
        );
    }

    public Marker read(Player player) {
        if (player == null) {
            return null;
        }

        String raw = player.getPersistentDataContainer().get(
                key,
                PersistentDataType.STRING
        );

        if (raw == null || raw.isBlank()) {
            return null;
        }

        String[] parts = raw.split("\\|", 2);

        if (parts.length != 2) {
            return null;
        }

        try {
            return new Marker(
                    UUID.fromString(parts[0]),
                    Phase.valueOf(parts[1])
            );
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    public boolean persistSourceRemoved(
            Player player,
            UUID transactionId
    ) {
        if (player == null || transactionId == null) {
            return false;
        }

        PersistentDataContainer container =
                player.getPersistentDataContainer();
        container.set(
                key,
                PersistentDataType.STRING,
                transactionId + "|" + Phase.SOURCE_REMOVED.name()
        );

        try {
            player.saveData();
            return true;
        } catch (RuntimeException exception) {
            core.getLogger().log(
                    Level.SEVERE,
                    "[Market] Could not persist source-removal player marker for "
                            + transactionId,
                    exception
            );
            return false;
        }
    }

    public void clearAndPersist(
            Player player,
            UUID transactionId
    ) {
        if (player == null) {
            return;
        }

        Marker current = read(player);

        if (transactionId != null
                && current != null
                && !transactionId.equals(
                current.transactionId()
        )) {
            return;
        }

        player.getPersistentDataContainer().remove(key);

        try {
            player.saveData();
        } catch (RuntimeException exception) {
            core.getLogger().log(
                    Level.SEVERE,
                    "[Market] Could not clear source-removal player marker"
                            + (transactionId == null
                            ? ""
                            : " for " + transactionId),
                    exception
            );
        }
    }

    public void clearInMemory(Player player) {
        if (player != null) {
            player.getPersistentDataContainer().remove(key);
        }
    }
}
