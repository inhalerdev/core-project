package net.mineacle.core.auctionhouse.model;

import org.bukkit.inventory.ItemStack;

import java.util.UUID;

public final class AuctionHouseListing {

    private final UUID id;
    private final UUID owner;
    private final String ownerName;
    private final ItemStack item;
    private final long priceCents;
    private final long createdAt;

    public AuctionHouseListing(UUID id, UUID owner, String ownerName, ItemStack item, long priceCents, long createdAt) {
        this.id = id;
        this.owner = owner;
        this.ownerName = ownerName;
        this.item = item;
        this.priceCents = priceCents;
        this.createdAt = createdAt;
    }

    public UUID id() {
        return id;
    }

    public UUID owner() {
        return owner;
    }

    public String ownerName() {
        return ownerName;
    }

    public ItemStack item() {
        return item;
    }

    public long priceCents() {
        return priceCents;
    }

    public long createdAt() {
        return createdAt;
    }
}
