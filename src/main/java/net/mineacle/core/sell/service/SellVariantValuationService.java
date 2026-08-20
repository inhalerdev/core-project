package net.mineacle.core.sell.service;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.inventory.meta.FireworkEffectMeta;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.inventory.meta.MusicInstrumentMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.inventory.meta.SuspiciousStewMeta;

import java.util.EnumSet;
import java.util.Set;

/**
 * Metadata-sensitive vanilla liquidation rules for catalog v9.
 *
 * <p>These families cannot use a material-only recipe price.  Values are
 * deliberately conservative and static, while the validator still rejects
 * command/plugin-only metadata.  They are no longer collapsed to the old
 * universal one-cent floor.</p>
 */
public final class SellVariantValuationService {

    /** Compatibility with SellService's material-only variant lookup. */
    public static final long SAFE_VARIANT_UNIT_CENTS = 3L;

    private static final Set<Material> SUPPORTED = EnumSet.of(
            Material.POTION,
            Material.SPLASH_POTION,
            Material.LINGERING_POTION,
            Material.TIPPED_ARROW,
            Material.SUSPICIOUS_STEW,
            Material.FIREWORK_ROCKET,
            Material.FIREWORK_STAR,
            Material.WRITTEN_BOOK,
            Material.FILLED_MAP,
            Material.GOAT_HORN
    );

    public static boolean supportsMaterial(Material material) {
        return material != null && SUPPORTED.contains(material);
    }

    public static long catalogBaseCents(Material material) {
        return supportsMaterial(material)
                ? SAFE_VARIANT_UNIT_CENTS
                : 0L;
    }

    public boolean supports(Material material) {
        return supportsMaterial(material);
    }

    public Result evaluate(ItemStack item) {
        if (item == null
                || item.getType().isAir()
                || !supports(item.getType())) {
            return Result.notVariant();
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return Result.rejected("variant-metadata");
        }

        return switch (item.getType()) {
            case POTION,
                 SPLASH_POTION,
                 LINGERING_POTION,
                 TIPPED_ARROW -> potion(meta);
            case SUSPICIOUS_STEW -> suspiciousStew(meta);
            case FIREWORK_ROCKET -> fireworkRocket(meta);
            case FIREWORK_STAR -> fireworkStar(meta);
            case WRITTEN_BOOK -> writtenBook(meta);
            case FILLED_MAP -> filledMap(meta);
            case GOAT_HORN -> goatHorn(meta);
            default -> Result.notVariant();
        };
    }

    private Result potion(ItemMeta meta) {
        if (!(meta instanceof PotionMeta potionMeta)) {
            return Result.rejected("variant-metadata");
        }

        /* Custom effects/colors/names are plugin/command surfaces. */
        if (!potionMeta.hasBasePotionType()
                || potionMeta.getBasePotionType() == null
                || potionMeta.hasCustomEffects()
                || potionMeta.hasColor()
                || potionMeta.hasCustomPotionName()) {
            return Result.rejected("custom-variant");
        }

        // Base PotionType is validated above; v9 keeps all metadata-sensitive
        // variants at the same conservative, exploit-safe liquidation floor.
        return Result.allowedVariant();
    }

    private Result suspiciousStew(ItemMeta meta) {
        if (!(meta instanceof SuspiciousStewMeta stewMeta)
                || !stewMeta.hasCustomEffects()
                || stewMeta.getCustomEffects().size() != 1) {
            return Result.rejected("custom-variant");
        }
        return Result.allowedVariant();
    }

    private Result fireworkRocket(ItemMeta meta) {
        if (!(meta instanceof FireworkMeta fireworkMeta)) {
            return Result.rejected("variant-metadata");
        }
        int power = fireworkMeta.getPower();
        int effects = fireworkMeta.getEffectsSize();
        if (power < 1 || power > 3 || effects < 0 || effects > 7) {
            return Result.rejected("custom-variant");
        }
        /* The multi-output rocket recipe keeps this family intentionally cheap. */
        return Result.allowedVariant();
    }

    private Result fireworkStar(ItemMeta meta) {
        if (!(meta instanceof FireworkEffectMeta effectMeta)
                || !effectMeta.hasEffect()
                || effectMeta.getEffect() == null) {
            return Result.rejected("variant-metadata");
        }
        return Result.allowedVariant();
    }

    private Result writtenBook(ItemMeta meta) {
        if (!(meta instanceof BookMeta bookMeta)
                || !bookMeta.hasTitle()
                || !bookMeta.hasAuthor()
                || bookMeta.getPageCount() < 1
                || bookMeta.getPageCount() > 100) {
            return Result.rejected("variant-metadata");
        }
        return Result.allowedVariant();
    }

    private Result filledMap(ItemMeta meta) {
        if (!(meta instanceof MapMeta mapMeta)
                || !mapMeta.hasMapView()
                || mapMeta.getMapView() == null) {
            return Result.rejected("variant-metadata");
        }
        return Result.allowedVariant();
    }

    private Result goatHorn(ItemMeta meta) {
        if (!(meta instanceof MusicInstrumentMeta instrumentMeta)
                || instrumentMeta.getInstrument() == null) {
            return Result.rejected("variant-metadata");
        }
        return Result.allowedVariant();
    }

    public record Result(
            boolean variant,
            boolean accepted,
            long unitCents,
            String rejectionReason
    ) {
        private static Result notVariant() {
            return new Result(false, true, 0L, "");
        }

        private static Result allowedVariant() {
            return new Result(
                    true,
                    true,
                    SAFE_VARIANT_UNIT_CENTS,
                    ""
            );
        }

        private static Result rejected(String reason) {
            return new Result(
                    true,
                    false,
                    0L,
                    reason == null ? "variant-metadata" : reason
            );
        }
    }
}
