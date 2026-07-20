package net.mineacle.core.orders.model;

import org.bukkit.Material;

import java.util.UUID;

public final class OrderRecord {

    private final UUID id;
    private final UUID ownerId;
    private final String ownerName;
    private final Material material;
    private final int requestedAmount;
    private final long totalEscrowCents;
    private final long createdAtMillis;

    private int deliveredAmount;
    private int collectedAmount;
    private long escrowRemainingCents;
    private boolean active;

    public OrderRecord(
            UUID id,
            UUID ownerId,
            String ownerName,
            Material material,
            int requestedAmount,
            int deliveredAmount,
            int collectedAmount,
            long totalEscrowCents,
            long escrowRemainingCents,
            long createdAtMillis,
            boolean active
    ) {
        this.id = id;
        this.ownerId = ownerId;
        this.ownerName = ownerName == null
                ? ""
                : ownerName;
        this.material = material;
        this.requestedAmount = Math.max(1, requestedAmount);
        this.deliveredAmount = clamp(
                deliveredAmount,
                0,
                this.requestedAmount
        );
        this.collectedAmount = clamp(
                collectedAmount,
                0,
                this.deliveredAmount
        );
        this.totalEscrowCents = Math.max(
                0L,
                totalEscrowCents
        );
        this.escrowRemainingCents = Math.max(
                0L,
                Math.min(
                        escrowRemainingCents,
                        this.totalEscrowCents
                )
        );
        this.createdAtMillis = createdAtMillis;
        this.active = active
                && remainingAmount() > 0
                && this.escrowRemainingCents > 0L;
    }

    public static OrderRecord legacy(
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
        return new OrderRecord(
                id,
                ownerId,
                ownerName,
                material,
                requestedAmount,
                deliveredAmount,
                collectedAmount,
                safeTotal(
                        pricePerItemCents,
                        requestedAmount
                ),
                escrowRemainingCents,
                createdAtMillis,
                active
        );
    }

    public UUID id() {
        return id;
    }

    public UUID ownerId() {
        return ownerId;
    }

    /**
     * Stored only as a migration/fallback value. Player-facing output should
     * resolve the current Mineacle display name from ownerId.
     */
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

    public long totalEscrowCents() {
        return totalEscrowCents;
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
        return Math.max(
                0,
                requestedAmount - deliveredAmount
        );
    }

    public int collectableAmount() {
        return Math.max(
                0,
                deliveredAmount - collectedAmount
        );
    }

    /**
     * Average display quote. Exact delivery payouts use payoutFor(...), which
     * distributes every escrow cent without silently rounding down the order.
     */
    public long pricePerItemCents() {
        if (requestedAmount <= 0) {
            return 0L;
        }

        return Math.max(
                1L,
                totalEscrowCents / requestedAmount
        );
    }

    public long payoutFor(int requestedDeliveryAmount) {
        int remainingItems = remainingAmount();

        if (remainingItems <= 0
                || escrowRemainingCents <= 0L
                || requestedDeliveryAmount <= 0) {
            return 0L;
        }

        int deliveryAmount = Math.min(
                requestedDeliveryAmount,
                remainingItems
        );

        if (deliveryAmount == remainingItems) {
            return escrowRemainingCents;
        }

        long numerator;

        try {
            numerator = Math.multiplyExact(
                    escrowRemainingCents,
                    deliveryAmount
            );
        } catch (ArithmeticException exception) {
            return Math.max(
                    deliveryAmount,
                    escrowRemainingCents
                            / remainingItems
                            * deliveryAmount
            );
        }

        return Math.max(
                deliveryAmount,
                numerator / remainingItems
        );
    }

    public void addDelivered(
            int amount,
            long payoutCents
    ) {
        int safeAmount = clamp(
                amount,
                0,
                remainingAmount()
        );
        long safePayout = Math.max(
                0L,
                Math.min(
                        payoutCents,
                        escrowRemainingCents
                )
        );

        deliveredAmount += safeAmount;
        escrowRemainingCents -= safePayout;

        if (remainingAmount() <= 0
                || escrowRemainingCents <= 0L) {
            active = false;
        }
    }

    public void addCollected(int amount) {
        collectedAmount = Math.min(
                deliveredAmount,
                collectedAmount + Math.max(0, amount)
        );
    }

    public void cancelAndRefund() {
        active = false;
        escrowRemainingCents = 0L;
    }

    public boolean settled() {
        return !active
                && collectableAmount() <= 0
                && escrowRemainingCents <= 0L;
    }

    public OrderRecord copy() {
        return new OrderRecord(
                id,
                ownerId,
                ownerName,
                material,
                requestedAmount,
                deliveredAmount,
                collectedAmount,
                totalEscrowCents,
                escrowRemainingCents,
                createdAtMillis,
                active
        );
    }

    private static int clamp(
            int value,
            int minimum,
            int maximum
    ) {
        return Math.max(
                minimum,
                Math.min(maximum, value)
        );
    }

    private static long safeTotal(
            long pricePerItemCents,
            int requestedAmount
    ) {
        try {
            return Math.multiplyExact(
                    Math.max(0L, pricePerItemCents),
                    Math.max(1, requestedAmount)
            );
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }
}
