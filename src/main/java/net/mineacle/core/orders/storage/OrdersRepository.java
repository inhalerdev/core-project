package net.mineacle.core.orders.storage;

import net.mineacle.core.orders.model.OrderRecord;

import java.util.Collection;
import java.util.UUID;

public interface OrdersRepository {

    void load();

    void save();

    void shutdown();

    Collection<OrderRecord> all();

    Collection<OrderRecord> active();

    Collection<OrderRecord> byOwner(UUID ownerId);

    int activeCountByOwner(UUID ownerId);

    OrderRecord get(UUID id);

    void put(OrderRecord order);

    void remove(UUID id);
}
