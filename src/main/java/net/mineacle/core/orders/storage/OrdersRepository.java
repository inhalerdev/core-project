package net.mineacle.core.orders.storage;

import net.mineacle.core.orders.model.OrderRecord;

import java.util.Collection;
import org.bukkit.Material;
import java.util.UUID;

public interface OrdersRepository {

    boolean save();

    void shutdown();

    Collection<OrderRecord> active();

    /**
     * Active exact-limit orders for one material in execution priority:
     * highest bid first, then oldest, then UUID for deterministic ties.
     */
    Collection<OrderRecord> activeForMaterial(Material material);

    Collection<OrderRecord> byOwner(UUID ownerId);

    int activeCountByOwner(UUID ownerId);

    OrderRecord get(UUID id);

    boolean put(OrderRecord order);

    boolean remove(UUID id);

    /**
     * Synchronously persists the complete post-mutation snapshot before
     * returning success. Money-moving Order transactions use this path.
     */
    boolean putDurable(OrderRecord order);

    /**
     * Idempotent durable cross-market Order mutation. The transaction id is
     * persisted in the same orders.yml snapshot as every fill so recovery can
     * distinguish "write completed, journal advance crashed" from "write never
     * happened".
     */
    boolean putAllDurable(
            UUID marketTransactionId,
            Collection<OrderRecord> orders
    );

    boolean marketTransactionCommitted(
            UUID marketTransactionId
    );

    /**
     * Synchronously persists the complete post-removal snapshot before
     * returning success.
     */
    boolean removeDurable(UUID id);
}
