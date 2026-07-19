package net.mineacle.core.auctionhouse.model;

import org.bukkit.inventory.ItemStack;

import java.util.Objects;
import java.util.UUID;

public final class AuctionHouseListing {

    private final UUID id;
    private final UUID owner;
    private final String ownerName;
    private final ItemStack item;
    private final long priceCents;
    private final long createdAt;

    public AuctionHouseListing(
            UUID id,
            UUID owner,
            String ownerName,
            ItemStack item,
            long priceCents,
            long createdAt
    ) {
        this.id = Objects.requireNonNull(id, "id");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.ownerName = ownerName == null || ownerName.isBlank() ? "Unknown" : ownerName;
        this.item = Objects.requireNonNull(item, "item").clone();
        this.priceCents = priceCents;
        this.createdAt = createdAt;
    }

    public UUID id() {
        return id;
    }

    public UUID owner() {
        return owner;
    }

    /**
     * Stable username fallback used for storage and audit recovery.
     * Player-facing output must resolve the current Mineacle display name instead.
     */
    public String ownerName() {
        return ownerName;
    }

    public ItemStack item() {
        return item.clone();
    }

    public long priceCents() {
        return priceCents;
    }

    public long createdAt() {
        return createdAt;
    }
}
