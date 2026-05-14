package net.mineacle.core.orders.model;

import org.bukkit.Material;

import java.util.UUID;

public final class OrderRecord {

    private final UUID id;
    private final UUID ownerId;
    private final String ownerName;
    private final Material material;
    private final int requestedAmount;
    private int deliveredAmount;
    private int collectedAmount;
    private final long pricePerItemCents;
    private long escrowRemainingCents;
    private final long createdAtMillis;
    private boolean active;

    public OrderRecord(
            UUID id,
            UUID ownerId,
            String ownerName,
            Material material,
            int requestedAmount,
            int deliveredAmount,
            int collectedAmount,
            long pricePerItemCents,
            long escrowRemainingCents,
            long createdAtMillis,
            boolean active
    ) {
        this.id = id;
        this.ownerId = ownerId;
        this.ownerName = ownerName;
        this.material = material;
        this.requestedAmount = requestedAmount;
        this.deliveredAmount = deliveredAmount;
        this.collectedAmount = collectedAmount;
        this.pricePerItemCents = pricePerItemCents;
        this.escrowRemainingCents = escrowRemainingCents;
        this.createdAtMillis = createdAtMillis;
        this.active = active;
    }

    public UUID id() {
        return id;
    }

    public UUID ownerId() {
        return ownerId;
    }

    public String ownerName() {
        return ownerName;
    }

    public Material material() {
        return material;
    }

    public int requestedAmount() {
        return requestedAmount;
    }

    public int deliveredAmount() {
        return deliveredAmount;
    }

    public int collectedAmount() {
        return collectedAmount;
    }

    public long pricePerItemCents() {
        return pricePerItemCents;
    }

    public long escrowRemainingCents() {
        return escrowRemainingCents;
    }

    public long createdAtMillis() {
        return createdAtMillis;
    }

    public boolean active() {
        return active;
    }

    public int remainingAmount() {
        return Math.max(0, requestedAmount - deliveredAmount);
    }

    public int collectableAmount() {
        return Math.max(0, deliveredAmount - collectedAmount);
    }

    public boolean complete() {
        return remainingAmount() <= 0 || escrowRemainingCents <= 0L;
    }

    public void addDelivered(int amount) {
        deliveredAmount = Math.min(requestedAmount, deliveredAmount + Math.max(0, amount));

        if (complete()) {
            active = false;
        }
    }

    public void addCollected(int amount) {
        collectedAmount = Math.min(deliveredAmount, collectedAmount + Math.max(0, amount));
    }

    public void removeEscrow(long cents) {
        escrowRemainingCents = Math.max(0L, escrowRemainingCents - Math.max(0L, cents));

        if (complete()) {
            active = false;
        }
    }

    public void cancel() {
        active = false;
    }
}
