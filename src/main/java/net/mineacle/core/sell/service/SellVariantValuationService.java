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
 * Runtime safety boundary for vanilla item types whose legitimate survival
 * variants are represented by ItemMeta rather than by separate Materials.
 *
 * <p>v1.0.45 intentionally values every accepted variant at one cent per
 * item and disables market movement for these Materials in the catalog.
 * That is deliberately conservative: it gives every legitimate variant a
 * liquidation value without creating a brewing, firework, map, or book
 * conversion arbitrage surface. Higher per-variant values can be introduced
 * later only after the transaction ledger records a stable variant key.</p>
 */
public final class SellVariantValuationService {

    public static final long SAFE_VARIANT_UNIT_CENTS = 1L;

    private static final Set<Material> SUPPORTED =
            EnumSet.of(
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

    public boolean supports(
            Material material
    ) {
        return material != null
                && SUPPORTED.contains(material);
    }

    public Result evaluate(
            ItemStack item
    ) {
        if (item == null
                || item.getType().isAir()
                || !supports(item.getType())) {
            return Result.notVariant();
        }

        ItemMeta meta =
                item.getItemMeta();

        if (meta == null) {
            return Result.rejected(
                    "variant-metadata"
            );
        }

        return switch (item.getType()) {
            case POTION,
                 SPLASH_POTION,
                 LINGERING_POTION,
                 TIPPED_ARROW ->
                    potion(meta);
            case SUSPICIOUS_STEW ->
                    suspiciousStew(meta);
            case FIREWORK_ROCKET ->
                    fireworkRocket(meta);
            case FIREWORK_STAR ->
                    fireworkStar(meta);
            case WRITTEN_BOOK ->
                    writtenBook(meta);
            case FILLED_MAP ->
                    filledMap(meta);
            case GOAT_HORN ->
                    goatHorn(meta);
            default -> Result.notVariant();
        };
    }

    private Result potion(
            ItemMeta meta
    ) {
        if (!(meta instanceof PotionMeta potionMeta)) {
            return Result.rejected(
                    "variant-metadata"
            );
        }

        /*
         * Base PotionType variants are normal brewing output. Custom potion
         * effects/color/name are command/plugin surfaces and do not belong in
         * the automatic vanilla buyback path.
         */
        if (!potionMeta.hasBasePotionType()
                || potionMeta.getBasePotionType() == null
                || potionMeta.hasCustomEffects()
                || potionMeta.hasColor()
                || potionMeta.hasCustomPotionName()) {
            return Result.rejected(
                    "custom-variant"
            );
        }

        return Result.allowedVariant();
    }

    private Result suspiciousStew(
            ItemMeta meta
    ) {
        if (!(meta instanceof SuspiciousStewMeta stewMeta)) {
            return Result.rejected(
                    "variant-metadata"
            );
        }

        /*
         * Survival suspicious stew contains one flower/mooshroom effect.
         * Multiple stored effects indicate a command/plugin-generated item.
         */
        if (!stewMeta.hasCustomEffects()
                || stewMeta.getCustomEffects().size() != 1) {
            return Result.rejected(
                    "custom-variant"
            );
        }

        return Result.allowedVariant();
    }

    private Result fireworkRocket(
            ItemMeta meta
    ) {
        if (!(meta instanceof FireworkMeta fireworkMeta)) {
            return Result.rejected(
                    "variant-metadata"
            );
        }

        int power =
                fireworkMeta.getPower();
        int effects =
                fireworkMeta.getEffectsSize();

        /*
         * Survival crafting produces flight duration 1-3. A 3x3 crafting
         * grid can place at most seven firework stars alongside paper and
         * gunpowder, so larger values are not accepted automatically.
         */
        if (power < 1
                || power > 3
                || effects < 0
                || effects > 7) {
            return Result.rejected(
                    "custom-variant"
            );
        }

        return Result.allowedVariant();
    }

    private Result fireworkStar(
            ItemMeta meta
    ) {
        if (!(meta instanceof FireworkEffectMeta effectMeta)
                || !effectMeta.hasEffect()
                || effectMeta.getEffect() == null) {
            return Result.rejected(
                    "variant-metadata"
            );
        }

        return Result.allowedVariant();
    }

    private Result writtenBook(
            ItemMeta meta
    ) {
        if (!(meta instanceof BookMeta bookMeta)
                || !bookMeta.hasTitle()
                || !bookMeta.hasAuthor()
                || bookMeta.getPageCount() < 1
                || bookMeta.getPageCount() > 100) {
            return Result.rejected(
                    "variant-metadata"
            );
        }

        return Result.allowedVariant();
    }

    private Result filledMap(
            ItemMeta meta
    ) {
        if (!(meta instanceof MapMeta mapMeta)
                || !mapMeta.hasMapView()
                || mapMeta.getMapView() == null) {
            return Result.rejected(
                    "variant-metadata"
            );
        }

        return Result.allowedVariant();
    }

    private Result goatHorn(
            ItemMeta meta
    ) {
        if (!(meta instanceof MusicInstrumentMeta instrumentMeta)
                || instrumentMeta.getInstrument() == null) {
            return Result.rejected(
                    "variant-metadata"
            );
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
            return new Result(
                    false,
                    true,
                    0L,
                    ""
            );
        }

        private static Result allowedVariant() {
            return new Result(
                    true,
                    true,
                    SAFE_VARIANT_UNIT_CENTS,
                    ""
            );
        }

        private static Result rejected(
                String reason
        ) {
            return new Result(
                    true,
                    false,
                    0L,
                    reason == null
                            ? "variant-metadata"
                            : reason
            );
        }
    }
}
