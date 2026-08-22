package net.mineacle.core.market.model;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable cross-market settlement record.
 *
 * <p>The journal is intentionally broader than the first /sell integration so
 * the same transaction format can later cover AH/Order crossing without
 * inventing another recovery protocol.</p>
 */
public record MarketTransaction(
        UUID transactionId,
        State state,
        UUID sellerId,
        List<SourceItem> sourceItems,
        List<SellLeg> sellLegs,
        long orderPayoutCents,
        long serverPayoutCents,
        long totalPayoutCents,
        long createdAtMillis,
        String quarantineReason
) {

    public enum State {
        PREPARED,
        SOURCE_REMOVED,
        ORDERS_COMMITTED,
        PAYOUT_STARTED,
        PAID,
        COMMITTED,
        QUARANTINED
    }

    public MarketTransaction {
        Objects.requireNonNull(transactionId, "transactionId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(sellerId, "sellerId");

        sourceItems = copySourceItems(sourceItems);
        sellLegs = sellLegs == null
                ? List.of()
                : List.copyOf(sellLegs);
        orderPayoutCents = Math.max(0L, orderPayoutCents);
        serverPayoutCents = Math.max(0L, serverPayoutCents);
        totalPayoutCents = Math.max(0L, totalPayoutCents);
        createdAtMillis = Math.max(0L, createdAtMillis);
        quarantineReason = quarantineReason == null
                ? ""
                : quarantineReason;
    }

    public MarketTransaction withState(State next) {
        return new MarketTransaction(
                transactionId,
                Objects.requireNonNull(next, "next"),
                sellerId,
                sourceItems,
                sellLegs,
                orderPayoutCents,
                serverPayoutCents,
                totalPayoutCents,
                createdAtMillis,
                quarantineReason
        );
    }

    public MarketTransaction quarantine(String reason) {
        return new MarketTransaction(
                transactionId,
                State.QUARANTINED,
                sellerId,
                sourceItems,
                sellLegs,
                orderPayoutCents,
                serverPayoutCents,
                totalPayoutCents,
                createdAtMillis,
                reason == null ? "unknown" : reason
        );
    }

    public boolean payoutInvariant() {
        try {
            return Math.addExact(
                    orderPayoutCents,
                    serverPayoutCents
            ) == totalPayoutCents;
        } catch (ArithmeticException exception) {
            return false;
        }
    }

    private static List<SourceItem> copySourceItems(
            List<SourceItem> source
    ) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }

        List<SourceItem> copy =
                new ArrayList<>(source.size());

        for (SourceItem item : source) {
            if (item != null) {
                copy.add(
                        new SourceItem(
                                item.slot(),
                                item.item()
                        )
                );
            }
        }

        return List.copyOf(copy);
    }

    public record SourceItem(
            int slot,
            ItemStack item
    ) {
        public SourceItem {
            if (slot < 0) {
                throw new IllegalArgumentException(
                        "source slot cannot be negative"
                );
            }
            Objects.requireNonNull(item, "item");
            if (item.getType().isAir()
                    || item.getAmount() <= 0) {
                throw new IllegalArgumentException(
                        "source item must contain a positive non-air stack"
                );
            }
            item = item.clone();
        }

        @Override
        public ItemStack item() {
            return item.clone();
        }
    }

    public record SellLeg(
            Material material,
            int requestedAmount,
            long serverUnitCents,
            List<OrderLeg> orderLegs,
            int orderAmount,
            long orderPayoutCents,
            int serverAmount,
            long serverPayoutCents
    ) {
        public SellLeg {
            Objects.requireNonNull(material, "material");
            requestedAmount = Math.max(0, requestedAmount);
            serverUnitCents = Math.max(0L, serverUnitCents);
            orderLegs = orderLegs == null
                    ? List.of()
                    : List.copyOf(orderLegs);
            orderAmount = Math.max(0, orderAmount);
            orderPayoutCents = Math.max(0L, orderPayoutCents);
            serverAmount = Math.max(0, serverAmount);
            serverPayoutCents = Math.max(0L, serverPayoutCents);
        }

        public boolean amountInvariant() {
            try {
                return Math.addExact(
                        orderAmount,
                        serverAmount
                ) == requestedAmount;
            } catch (ArithmeticException exception) {
                return false;
            }
        }

        public boolean payoutInvariant() {
            if (requestedAmount <= 0
                    || (serverAmount > 0 && serverUnitCents <= 0L)) {
                return false;
            }

            long childAmount = 0L;
            long childPayout = 0L;

            try {
                for (OrderLeg orderLeg : orderLegs) {
                    if (orderLeg == null
                            || !orderLeg.payoutInvariant()) {
                        return false;
                    }

                    childAmount = Math.addExact(
                            childAmount,
                            orderLeg.amount()
                    );
                    childPayout = Math.addExact(
                            childPayout,
                            orderLeg.payoutCents()
                    );
                }

                long expectedServerPayout = Math.multiplyExact(
                        serverUnitCents,
                        serverAmount
                );

                return childAmount == orderAmount
                        && childPayout == orderPayoutCents
                        && expectedServerPayout == serverPayoutCents;
            } catch (ArithmeticException exception) {
                return false;
            }
        }
    }

    public record OrderLeg(
            UUID orderId,
            UUID buyerId,
            int amount,
            long unitPriceCents,
            long payoutCents,
            long createdAtMillis
    ) {
        public OrderLeg {
            Objects.requireNonNull(orderId, "orderId");
            Objects.requireNonNull(buyerId, "buyerId");
            amount = Math.max(0, amount);
            unitPriceCents = Math.max(0L, unitPriceCents);
            payoutCents = Math.max(0L, payoutCents);
            createdAtMillis = Math.max(0L, createdAtMillis);
        }

        public boolean payoutInvariant() {
            if (amount <= 0
                    || unitPriceCents <= 0L
                    || payoutCents <= 0L) {
                return false;
            }

            try {
                return Math.multiplyExact(
                        unitPriceCents,
                        amount
                ) == payoutCents;
            } catch (ArithmeticException exception) {
                return false;
            }
        }
    }
}
