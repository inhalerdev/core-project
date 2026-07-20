package net.mineacle.core.orders.storage;

import net.mineacle.core.orders.model.OrderRecord;

import java.util.Collection;
import java.util.UUID;

public interface OrdersRepository {

    void load();

    boolean save();

    Collection<OrderRecord> all();

    OrderRecord get(UUID id);

    boolean put(OrderRecord order);

    boolean remove(UUID id);
}
