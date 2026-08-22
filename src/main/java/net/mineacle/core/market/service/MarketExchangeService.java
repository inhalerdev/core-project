package net.mineacle.core.market.service;

import net.mineacle.core.Core;
import net.mineacle.core.auctionhouse.service.AuctionHouseService;
import net.mineacle.core.orders.service.OrderService;
import net.mineacle.core.sell.service.SellService;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;
import java.util.Set;

/** Shared market authority connecting Sell, Orders and Auction House. */
public final class MarketExchangeService {

    private static final Set<Material> METADATA_MARKET_FAMILIES = Set.of(
            Material.POTION,
            Material.SPLASH_POTION,
            Material.LINGERING_POTION,
            Material.TIPPED_ARROW,
            Material.ENCHANTED_BOOK,
            Material.FILLED_MAP,
            Material.FIREWORK_ROCKET,
            Material.FIREWORK_STAR,
            Material.GOAT_HORN,
            Material.SUSPICIOUS_STEW,
            Material.WRITTEN_BOOK,
            Material.WRITABLE_BOOK,
            Material.PLAYER_HEAD
    );

    private final Core core;
    private final SellService sellService;

    private volatile OrderService orderService;
    private volatile AuctionHouseService auctionHouseService;

    public MarketExchangeService(
            Core core,
            SellService sellService
    ) {
        this.core = Objects.requireNonNull(core, "core");
        this.sellService = Objects.requireNonNull(
                sellService,
                "sellService"
        );
    }

    public Core core() {
        return core;
    }

    public SellService sellService() {
        return sellService;
    }

    public void bindOrders(OrderService service) {
        orderService = service;
    }

    public void unbindOrders(OrderService service) {
        if (orderService == service) {
            orderService = null;
        }
    }

    public void bindAuctionHouse(
            AuctionHouseService service
    ) {
        auctionHouseService = service;
    }

    public void unbindAuctionHouse(
            AuctionHouseService service
    ) {
        if (auctionHouseService == service) {
            auctionHouseService = null;
        }
    }

    /**
     * Current guaranteed server liquidation value for one canonical material.
     * A value of zero means the server does not currently guarantee a buyout.
     */
    public long serverGuaranteedUnitCents(
            Material material
    ) {
        if (material == null
                || material == Material.AIR
                || !material.isItem()
                || !sellService.isServerSellableMaterial(material)) {
            return 0L;
        }

        return Math.max(
                0L,
                sellService.serverUnitSellCents(
                        (java.util.UUID) null,
                        material
                )
        );
    }

    /**
     * Hard minimum resting bid. Live server Sell is always the economic floor
     * even when the configured administrative minimum is lower.
     */
    public long minimumOrderUnitCents(
            Material material,
            long configuredMinimumUnitCents
    ) {
        return Math.max(
                Math.max(1L, configuredMinimumUnitCents),
                serverGuaranteedUnitCents(material)
        );
    }

    /**
     * Orders are intentionally restricted to fungible, stackable commodities.
     * Metadata-sensitive families remain Auction House only until the market
     * has exact metadata-aware keys.
     */
    public boolean isFungibleOrderMaterial(
            Material material
    ) {
        if (material == null
                || material == Material.AIR
                || !material.isItem()
                || material.getMaxStackSize() <= 1
                || METADATA_MARKET_FAMILIES.contains(material)) {
            return false;
        }

        String name = material.name();

        if (name.endsWith("_BANNER")
                || name.endsWith("_WALL_BANNER")
                || name.endsWith("_HEAD")
                || name.endsWith("_SKULL")
                || name.endsWith("_SHULKER_BOX")) {
            return false;
        }

        return serverGuaranteedUnitCents(material) > 0L;
    }

    /**
     * A fungible Order can only consume a plain stack. Renames, custom model
     * data, PDC, enchantments, block-state data and other metadata remain with
     * the player instead of being flattened into a Material-only Order.
     */
    public boolean isNonCanonicalOrderStack(
            ItemStack item
    ) {
        if (item == null || item.getAmount() <= 0) {
            return true;
        }

        if (isFungibleOrderMaterial(item.getType())) {
            return item.hasItemMeta();
        }

        return true;
    }
}
