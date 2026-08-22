package net.mineacle.core.auctionhouse.service;

import net.mineacle.core.market.MarketModule;
import net.mineacle.core.market.model.MarketSellExecutionResult;
import net.mineacle.core.market.service.MarketExchangeService;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Pre-list bridge between Auction House asks and resting player Orders.
 *
 * <p>Only plain fungible stacks are eligible. The matched quantity is settled
 * by the shared Market journal first; any unmatched quantity remains in the
 * seller's hand and is then handed to AuctionHouseService's existing durable
 * LIST transaction path.</p>
 */
public final class AuctionOrderCrossing {

    private AuctionOrderCrossing() {
    }

    public record Result(
            ItemStack item,
            int requestedAmount,
            int matchedAmount,
            long matchedPayoutCents,
            boolean matchedPayoutCompleted,
            int remainingAmount,
            long remainingListingPriceCents,
            AuctionHouseService.CreateOutcome listingOutcome
    ) {
        public Result {
            item = item == null
                    ? null
                    : item.clone();
            requestedAmount = Math.max(0, requestedAmount);
            matchedAmount = Math.max(0, matchedAmount);
            matchedPayoutCents = Math.max(0L, matchedPayoutCents);
            remainingAmount = Math.max(0, remainingAmount);
            remainingListingPriceCents =
                    Math.max(0L, remainingListingPriceCents);
        }

        @Override
        public ItemStack item() {
            return item == null
                    ? null
                    : item.clone();
        }

        public boolean matchedAny() {
            return matchedAmount > 0
                    && matchedPayoutCents > 0L;
        }

        public boolean fullyMatched() {
            return matchedAny()
                    && remainingAmount == 0;
        }
    }

    public static Result create(
            Player player,
            AuctionHouseService service,
            long priceCents,
            int amount,
            ItemStack expectedItem
    ) {
        ItemStack held =
                service == null || player == null
                        ? null
                        : service.previewHeldItem(player);

        if (!eligibleForCrossing(
                player,
                service,
                held,
                expectedItem,
                priceCents,
                amount
        )) {
            return regular(
                    player,
                    service,
                    held,
                    priceCents,
                    amount,
                    expectedItem
            );
        }

        ItemStack saleItem =
                held.clone();
        saleItem.setAmount(amount);

        long minimum =
                service.minimumListingPriceCents(
                        player,
                        saleItem
                );

        if (minimum < 0L
                || priceCents < minimum
                || priceCents > service.maxPriceCents()) {
            return regular(
                    player,
                    service,
                    held,
                    priceCents,
                    amount,
                    expectedItem
            );
        }

        MarketExchangeService exchange =
                MarketModule.exchangeService();

        if (exchange == null
                || exchange.isNonCanonicalOrderStack(
                saleItem
        )) {
            return regular(
                    player,
                    service,
                    held,
                    priceCents,
                    amount,
                    expectedItem
            );
        }

        MarketSellExecutionResult market =
                exchange.executeAuctionAskCross(
                        player,
                        saleItem,
                        amount,
                        priceCents
                );

        if (!market.handled()
                || !market.accepted()
                || market.orderAmount() <= 0L
                || market.orderAmount() > amount) {
            return regular(
                    player,
                    service,
                    held,
                    priceCents,
                    amount,
                    expectedItem
            );
        }

        int matched =
                Math.toIntExact(
                        market.orderAmount()
                );
        int remaining =
                amount - matched;

        if (remaining <= 0) {
            return new Result(
                    saleItem,
                    amount,
                    matched,
                    market.orderCents(),
                    market.completed(),
                    0,
                    0L,
                    null
            );
        }

        long remainderPrice =
                proportionalCeil(
                        priceCents,
                        remaining,
                        amount
                );

        if (remainderPrice <= 0L) {
            remainderPrice = 1L;
        }

        AuctionHouseService.CreateOutcome outcome =
                service.createListing(
                        player,
                        remainderPrice,
                        remaining,
                        expectedItem
                );

        return new Result(
                saleItem,
                amount,
                matched,
                market.orderCents(),
                market.completed(),
                remaining,
                remainderPrice,
                outcome
        );
    }

    private static boolean eligibleForCrossing(
            Player player,
            AuctionHouseService service,
            ItemStack held,
            ItemStack expectedItem,
            long priceCents,
            int amount
    ) {
        if (player == null
                || service == null
                || !service.enabled()
                || !service.canList(player)
                || priceCents <= 0L
                || held == null
                || held.getType() == Material.AIR
                || amount <= 0
                || amount > held.getAmount()
                || amount > held.getMaxStackSize()) {
            return false;
        }

        return expectedItem == null
                || sameIgnoringAmount(
                held,
                expectedItem
        );
    }

    private static Result regular(
            Player player,
            AuctionHouseService service,
            ItemStack held,
            long priceCents,
            int amount,
            ItemStack expectedItem
    ) {
        AuctionHouseService.CreateOutcome outcome;

        if (service == null) {
            outcome =
                    new AuctionHouseService.CreateOutcome(
                            AuctionHouseService.CreateResult.DISABLED,
                            null
                    );
        } else {
            outcome =
                    service.createListing(
                            player,
                            priceCents,
                            amount,
                            expectedItem
                    );
        }

        return new Result(
                held,
                Math.max(0, amount),
                0,
                0L,
                false,
                Math.max(0, amount),
                Math.max(0L, priceCents),
                outcome
        );
    }

    private static boolean sameIgnoringAmount(
            ItemStack left,
            ItemStack right
    ) {
        if (left == null || right == null) {
            return left == right;
        }

        ItemStack a =
                left.clone();
        ItemStack b =
                right.clone();
        a.setAmount(1);
        b.setAmount(1);
        return a.isSimilar(b);
    }

    private static long proportionalCeil(
            long totalCents,
            int remaining,
            int originalAmount
    ) {
        try {
            long numerator =
                    Math.multiplyExact(
                            totalCents,
                            (long) remaining
                    );
            return Math.ceilDiv(
                    numerator,
                    (long) originalAmount
            );
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }
}
