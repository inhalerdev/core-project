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
        this.requestedAmount = Math.max(1, requestedAmount);
        this.deliveredAmount = Math.clamp(
                deliveredAmount,
                0,
                this.requestedAmount
        );
        this.collectedAmount = Math.clamp(
                collectedAmount,
                0,
                this.deliveredAmount
        );
        this.pricePerItemCents = Math.max(
                1L,
                pricePerItemCents
        );
        this.escrowRemainingCents = Math.max(
                0L,
                escrowRemainingCents
        );
        this.createdAtMillis = Math.max(
                0L,
                createdAtMillis
        );
        this.active = active && !complete();
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
        return requestedAmount - deliveredAmount;
    }

    public int collectableAmount() {
        return deliveredAmount - collectedAmount;
    }

    public boolean complete() {
        return remainingAmount() == 0
                || escrowRemainingCents == 0L;
    }

    public void addDelivered(int amount) {
        if (amount <= 0 || !active) {
            return;
        }

        deliveredAmount = Math.min(
                requestedAmount,
                deliveredAmount + amount
        );

        closeIfComplete();
    }

    public void addCollected(int amount) {
        if (amount <= 0) {
            return;
        }

        collectedAmount = Math.min(
                deliveredAmount,
                collectedAmount + amount
        );
    }

    public void removeEscrow(long cents) {
        if (cents <= 0L || escrowRemainingCents == 0L) {
            return;
        }

        escrowRemainingCents = Math.max(
                0L,
                escrowRemainingCents - cents
        );

        closeIfComplete();
    }

    public void cancel() {
        active = false;
    }

    private void closeIfComplete() {
        if (complete()) {
            active = false;
        }
    }
}
