package net.mineacle.core.orders.storage;

import net.mineacle.core.orders.model.OrderRecord;

import java.util.Collection;
import java.util.UUID;

public interface OrdersRepository {

    void load();

    void save();

    Collection<OrderRecord> all();

    OrderRecord get(UUID id);

    void put(OrderRecord order);

    void remove(UUID id);
}
