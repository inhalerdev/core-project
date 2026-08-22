package net.mineacle.core.orders.model;

import org.bukkit.Material;

import java.math.BigInteger;
import java.util.UUID;

public final class OrderRecord {

    public enum PricingMode {
        LEGACY_TOTAL,
        LIMIT_PER_ITEM
    }

    private final UUID id;
    private final UUID ownerId;
    private final String ownerName;
    private final Material material;
    private final int requestedAmount;
    private final PricingMode pricingMode;
    private final long limitPricePerItemCents;
    private final long totalEscrowCents;
    private final long createdAtMillis;

    private int deliveredAmount;
    private int collectedAmount;
    private long escrowRemainingCents;
    private long actualSpentCents;
    private long releasedEscrowCents;
    private boolean active;

    public OrderRecord(
            UUID id,
            UUID ownerId,
            String ownerName,
            Material material,
            int requestedAmount,
            int deliveredAmount,
            int collectedAmount,
            PricingMode pricingMode,
            long limitPricePerItemCents,
            long totalEscrowCents,
            long escrowRemainingCents,
            long actualSpentCents,
            long releasedEscrowCents,
            long createdAtMillis,
            boolean active
    ) {
        this.id = id;
        this.ownerId = ownerId;
        this.ownerName = ownerName == null
                ? ""
                : ownerName;
        this.material = material;
        this.requestedAmount = Math.max(
                1,
                requestedAmount
        );
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
        this.pricingMode = pricingMode == null
                ? PricingMode.LEGACY_TOTAL
                : pricingMode;
        this.limitPricePerItemCents = Math.max(
                1L,
                limitPricePerItemCents
        );
        this.totalEscrowCents = Math.max(
                0L,
                totalEscrowCents
        );
        this.escrowRemainingCents = Math.clamp(
                escrowRemainingCents,
                0L,
                this.totalEscrowCents
        );

        long settledEscrow = Math.max(
                0L,
                this.totalEscrowCents
                        - this.escrowRemainingCents
        );
        this.actualSpentCents = Math.clamp(
                actualSpentCents,
                0L,
                settledEscrow
        );
        this.releasedEscrowCents = Math.clamp(
                releasedEscrowCents,
                0L,
                settledEscrow
                        - this.actualSpentCents
        );
        this.createdAtMillis = Math.max(
                0L,
                createdAtMillis
        );
        this.active = active
                && remainingAmount() > 0
                && this.escrowRemainingCents > 0L;
    }

    public static OrderRecord limitOrder(
            UUID id,
            UUID ownerId,
            String ownerName,
            Material material,
            int requestedAmount,
            long limitPricePerItemCents,
            long createdAtMillis
    ) {
        long escrow = safeTotal(
                limitPricePerItemCents,
                requestedAmount
        );

        return new OrderRecord(
                id,
                ownerId,
                ownerName,
                material,
                requestedAmount,
                0,
                0,
                PricingMode.LIMIT_PER_ITEM,
                limitPricePerItemCents,
                escrow,
                escrow,
                0L,
                0L,
                createdAtMillis,
                true
        );
    }

    public static OrderRecord legacy(
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
        long pricePerItem = requestedAmount <= 0
                ? 1L
                : Math.max(
                        1L,
                        totalEscrowCents / requestedAmount
                );
        long alreadySpent = Math.max(
                0L,
                totalEscrowCents
                        - Math.max(
                        0L,
                        escrowRemainingCents
                )
        );

        return new OrderRecord(
                id,
                ownerId,
                ownerName,
                material,
                requestedAmount,
                deliveredAmount,
                collectedAmount,
                PricingMode.LEGACY_TOTAL,
                pricePerItem,
                totalEscrowCents,
                escrowRemainingCents,
                alreadySpent,
                0L,
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

    public PricingMode pricingMode() {
        return pricingMode;
    }

    public boolean exactLimitPrice() {
        return pricingMode == PricingMode.LIMIT_PER_ITEM;
    }

    public long limitPricePerItemCents() {
        return limitPricePerItemCents;
    }

    public long totalEscrowCents() {
        return totalEscrowCents;
    }

    public long escrowRemainingCents() {
        return escrowRemainingCents;
    }

    public long actualSpentCents() {
        return actualSpentCents;
    }

    public long releasedEscrowCents() {
        return releasedEscrowCents;
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

    public long pricePerItemCents() {
        if (pricingMode == PricingMode.LIMIT_PER_ITEM) {
            return limitPricePerItemCents;
        }

        if (requestedAmount <= 0) {
            return 0L;
        }

        return Math.max(
                1L,
                totalEscrowCents / requestedAmount
        );
    }

    /**
     * Manual delivery payout. New limit orders pay their exact resting bid;
     * migrated legacy orders preserve the original proportional-total payout.
     */
    public long payoutFor(
            int requestedDeliveryAmount
    ) {
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

        if (pricingMode == PricingMode.LIMIT_PER_ITEM) {
            long exact = safeTotal(
                    limitPricePerItemCents,
                    deliveryAmount
            );

            if (exact == Long.MAX_VALUE) {
                return 0L;
            }

            if (exact > escrowRemainingCents) {
                return 0L;
            }

            return exact;
        }

        if (deliveryAmount == remainingItems) {
            return escrowRemainingCents;
        }

        long proportional = BigInteger.valueOf(
                        escrowRemainingCents
                )
                .multiply(
                        BigInteger.valueOf(
                                deliveryAmount
                        )
                )
                .divide(
                        BigInteger.valueOf(
                                remainingItems
                        )
                )
                .longValueExact();

        return Math.max(
                deliveryAmount,
                proportional
        );
    }

    public void addDelivered(
            int amount,
            long payoutCents
    ) {
        int safeAmount = Math.clamp(
                amount,
                0,
                remainingAmount()
        );
        long safePayout = Math.clamp(
                payoutCents,
                0L,
                escrowRemainingCents
        );

        if (safeAmount <= 0 || safePayout <= 0L) {
            return;
        }

        deliveredAmount += safeAmount;
        escrowRemainingCents -= safePayout;
        actualSpentCents = safeAddBounded(
                actualSpentCents,
                safePayout,
                totalEscrowCents
        );

        /*
         * A future crossed AH fill may execute below this order's limit. Keep
         * only the maximum escrow still required for the unfilled quantity and
         * account for the price improvement as released escrow. The caller that
         * performs such a discounted fill is responsible for returning the new
         * released amount to the buyer before finalizing the transaction.
         */
        if (pricingMode == PricingMode.LIMIT_PER_ITEM) {
            long requiredRemaining = safeRequired(
                    limitPricePerItemCents,
                    remainingAmount()
            );

            if (escrowRemainingCents > requiredRemaining) {
                long released =
                        escrowRemainingCents - requiredRemaining;
                escrowRemainingCents = requiredRemaining;
                releasedEscrowCents = safeAddBounded(
                        releasedEscrowCents,
                        released,
                        totalEscrowCents
                );
            }
        }

        if (remainingAmount() <= 0
                || escrowRemainingCents <= 0L) {
            active = false;
        }
    }

    public void addCollected(int amount) {
        collectedAmount = Math.min(
                deliveredAmount,
                collectedAmount
                        + Math.max(0, amount)
        );
    }

    public void cancelAndRefund() {
        releasedEscrowCents = safeAddBounded(
                releasedEscrowCents,
                escrowRemainingCents,
                totalEscrowCents
        );
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
                pricingMode,
                limitPricePerItemCents,
                totalEscrowCents,
                escrowRemainingCents,
                actualSpentCents,
                releasedEscrowCents,
                createdAtMillis,
                active
        );
    }

    private static long safeRequired(
            long pricePerItemCents,
            int amount
    ) {
        if (amount <= 0) {
            return 0L;
        }

        return safeTotal(
                pricePerItemCents,
                amount
        );
    }

    private static long safeTotal(
            long pricePerItemCents,
            int requestedAmount
    ) {
        try {
            return Math.multiplyExact(
                    Math.max(
                            1L,
                            pricePerItemCents
                    ),
                    Math.max(
                            1,
                            requestedAmount
                    )
            );
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private static long safeAddBounded(
            long left,
            long right,
            long maximum
    ) {
        try {
            return Math.min(
                    maximum,
                    Math.addExact(
                            Math.max(0L, left),
                            Math.max(0L, right)
                    )
            );
        } catch (ArithmeticException exception) {
            return maximum;
        }
    }
}
