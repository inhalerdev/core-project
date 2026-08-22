package net.mineacle.core.orders.storage;

import net.mineacle.core.orders.model.OrderRecord;

import java.util.Collection;
import java.util.UUID;

public interface OrdersRepository {

    boolean save();

    void shutdown();

    Collection<OrderRecord> active();

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
     * Synchronously persists the complete post-removal snapshot before
     * returning success.
     */
    boolean removeDurable(UUID id);
}
