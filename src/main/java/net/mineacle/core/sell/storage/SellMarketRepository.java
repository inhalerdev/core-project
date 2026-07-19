package net.mineacle.core.sell.storage;

import java.util.List;
import java.util.UUID;

public interface SellMarketRepository extends AutoCloseable {

    record MarketStateData(
            String material,
            double marketMultiplier,
            double featuredMultiplier,
            long featuredUntil,
            long lastRepricedAt,
            long targetUnitsPerDay
    ) {
    }

    record BucketData(
            String material,
            long bucketStart,
            long unitsSold,
            long payoutCents
    ) {
    }

    record HistoryData(
            UUID playerId,
            String material,
            long amount,
            long totalCents,
            long lastSoldAt
    ) {
    }

    record Snapshot(
            List<MarketStateData> states,
            List<BucketData> buckets,
            List<HistoryData> history
    ) {

        public static Snapshot empty() {
            return new Snapshot(List.of(), List.of(), List.of());
        }
    }

    record SaveBatch(
            List<MarketStateData> states,
            List<BucketData> buckets,
            List<HistoryData> history,
            long pruneBucketsBefore
    ) {

        public boolean empty() {
            return states.isEmpty()
                    && buckets.isEmpty()
                    && history.isEmpty();
        }
    }

    void initialize() throws Exception;

    Snapshot load(long bucketsSince) throws Exception;

    void save(SaveBatch batch) throws Exception;

    String name();

    @Override
    default void close() throws Exception {
    }
}
