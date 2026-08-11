package net.mineacle.core.common.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

public final class GuiText {

    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer
                    .legacySection();
    private static final PlainTextComponentSerializer PLAIN =
            PlainTextComponentSerializer
                    .plainText();

    private GuiText() {
    }

    public static Component component(
            String input
    ) {
        return LEGACY
                .deserialize(
                        TextColor.color(input)
                )
                .decoration(
                        TextDecoration.ITALIC,
                        false
                );
    }

    /**
     * Inventory titles intentionally use vanilla/default title styling.
     * Configured Mineacle color codes are stripped at this boundary so accent
     * colors cannot leak into container titles.
     */
    public static Component title(
            String input
    ) {
        return Component.text(
                        plain(input).trim()
                )
                .decoration(
                        TextDecoration.ITALIC,
                        false
                );
    }

    public static List<Component> lore(
            List<String> lines
    ) {
        if (lines == null
                || lines.isEmpty()) {
            return List.of();
        }

        List<Component> result =
                new ArrayList<>(
                        lines.size()
                );

        for (String line : lines) {
            result.add(
                    component(line)
            );
        }

        return List.copyOf(result);
    }

    public static String plain(
            Component component
    ) {
        return component == null
                ? ""
                : PLAIN.serialize(component);
    }

    /**
     * String input does not need a Component round-trip simply to remove
     * legacy/hex formatting.
     */
    public static String plain(
            String input
    ) {
        return TextColor.strip(input);
    }

    public static void apply(
            ItemMeta meta,
            String displayName,
            List<String> lore
    ) {
        meta.displayName(
                component(displayName)
        );
        meta.lore(
                lore(lore)
        );
    }
}
