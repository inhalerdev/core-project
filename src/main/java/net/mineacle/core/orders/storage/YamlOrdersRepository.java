package net.mineacle.core.orders.storage;

import net.mineacle.core.Core;
import net.mineacle.core.orders.model.OrderRecord;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class YamlOrdersRepository implements OrdersRepository {

    private final Core core;
    private final Map<UUID, OrderRecord> orders = new LinkedHashMap<>();

    private File file;
    private FileConfiguration config;

    public YamlOrdersRepository(Core core) {
        this.core = core;
        load();
    }

    @Override
    public void load() {
        orders.clear();

        if (!core.getDataFolder().exists()) {
            core.getDataFolder().mkdirs();
        }

        file = new File(core.getDataFolder(), "orders.yml");

        if (!file.exists()) {
            try {
                file.createNewFile();
            } catch (IOException exception) {
                core.getLogger().severe("Could not create orders.yml");
                exception.printStackTrace();
            }
        }

        config = YamlConfiguration.loadConfiguration(file);

        ConfigurationSection section = config.getConfigurationSection("orders");

        if (section == null) {
            return;
        }

        for (String key : section.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                String path = "orders." + key;

                UUID ownerId = UUID.fromString(config.getString(path + ".owner-id", ""));
                String ownerName = config.getString(path + ".owner-name", "Unknown");
                Material material = Material.valueOf(config.getString(path + ".material", "STONE"));
                int requestedAmount = config.getInt(path + ".requested-amount", 1);
                int deliveredAmount = config.getInt(path + ".delivered-amount", 0);
                long pricePerItemCents = config.getLong(path + ".price-per-item-cents", 1L);
                long escrowRemainingCents = config.getLong(path + ".escrow-remaining-cents", 0L);
                long createdAtMillis = config.getLong(path + ".created-at-millis", System.currentTimeMillis());
                boolean active = config.getBoolean(path + ".active", true);

                orders.put(id, new OrderRecord(
                        id,
                        ownerId,
                        ownerName,
                        material,
                        requestedAmount,
                        deliveredAmount,
                        pricePerItemCents,
                        escrowRemainingCents,
                        createdAtMillis,
                        active
                ));
            } catch (Exception exception) {
                core.getLogger().warning("Could not load order: " + key);
            }
        }
    }

    @Override
    public void save() {
        if (config == null || file == null) {
            return;
        }

        config.set("orders", null);

        for (OrderRecord order : orders.values()) {
            String path = "orders." + order.id();

            config.set(path + ".owner-id", order.ownerId().toString());
            config.set(path + ".owner-name", order.ownerName());
            config.set(path + ".material", order.material().name());
            config.set(path + ".requested-amount", order.requestedAmount());
            config.set(path + ".delivered-amount", order.deliveredAmount());
            config.set(path + ".price-per-item-cents", order.pricePerItemCents());
            config.set(path + ".escrow-remaining-cents", order.escrowRemainingCents());
            config.set(path + ".created-at-millis", order.createdAtMillis());
            config.set(path + ".active", order.active());
        }

        try {
            config.save(file);
        } catch (IOException exception) {
            core.getLogger().severe("Could not save orders.yml");
            exception.printStackTrace();
        }
    }

    @Override
    public Collection<OrderRecord> all() {
        return orders.values();
    }

    @Override
    public OrderRecord get(UUID id) {
        return orders.get(id);
    }

    @Override
    public void put(OrderRecord order) {
        orders.put(order.id(), order);
        save();
    }

    @Override
    public void remove(UUID id) {
        orders.remove(id);
        save();
    }
}
