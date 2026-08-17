package net.mineacle.core.auctionhouse.model;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;
import java.util.UUID;

public final class AuctionHouseListing {

    private final UUID id;
    private final UUID owner;
    private final String ownerName;
    private final ItemStack item;
    private final Material material;
    private final int amount;
    private final byte[] serializedItem;
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
        this.id = Objects.requireNonNull(
                id,
                "id"
        );
        this.owner = Objects.requireNonNull(
                owner,
                "owner"
        );
        this.ownerName =
                ownerName == null
                        || ownerName.isBlank()
                        ? "Unknown"
                        : ownerName;
        this.item = Objects.requireNonNull(
                item,
                "item"
        ).clone();
        this.material = this.item.getType();
        this.amount = Math.max(
                1,
                this.item.getAmount()
        );
        this.serializedItem =
                this.item.serializeAsBytes();
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
     * Public Mineacle identity snapshot used only as a fallback when the
     * current nickname/display identity cannot be resolved from the UUID.
     * UUID is always the authoritative seller identity.
     */
    public String ownerName() {
        return ownerName;
    }

    public ItemStack item() {
        return item.clone();
    }

    public Material material() {
        return material;
    }

    public int amount() {
        return amount;
    }

    /**
     * Detached immutable listing payload. Auction House persistence and the
     * website mirror reuse this cached byte representation instead of invoking
     * Bukkit ItemStack serialization from asynchronous workers.
     */
    public byte[] serializedItemBytes() {
        return serializedItem.clone();
    }

    public long priceCents() {
        return priceCents;
    }

    public long createdAt() {
        return createdAt;
    }
}
