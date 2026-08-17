package net.mineacle.core.auctionhouse.model;

import org.bukkit.Material;

import java.util.Objects;
import java.util.UUID;

public record AuctionHistoryEntry(
        UUID transactionId,
        Type type,
        UUID playerId,
        UUID counterpartId,
        Material material,
        String itemName,
        int amount,
        long priceCents,
        long timestamp
) {

    public AuctionHistoryEntry {
        Objects.requireNonNull(
                transactionId,
                "transactionId"
        );
        Objects.requireNonNull(
                type,
                "type"
        );
        Objects.requireNonNull(
                playerId,
                "playerId"
        );
        Objects.requireNonNull(
                material,
                "material"
        );
        itemName =
                sanitizeItemName(
                        itemName
                );
        amount =
                Math.max(
                        1,
                        amount
                );
        priceCents =
                Math.max(
                        0L,
                        priceCents
                );
        timestamp =
                Math.max(
                        0L,
                        timestamp
                );
    }

    private static String sanitizeItemName(
            String input
    ) {
        String value =
                input == null
                        || input.isBlank()
                        ? "Item"
                        : input
                        .replace(
                                '\n',
                                ' '
                        )
                        .replace(
                                '\r',
                                ' '
                        )
                        .trim();

        if (value.length() > 128) {
            return value.substring(
                    0,
                    128
            );
        }

        return value;
    }

    public enum Type {
        LISTED("Listed"),
        PURCHASED("Purchased"),
        SOLD("Sold"),
        CANCELLED("Cancelled"),
        RECLAIMED("Reclaimed");

        private final String label;

        Type(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }
}
