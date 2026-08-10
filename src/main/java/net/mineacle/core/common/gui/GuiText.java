package net.mineacle.core.common.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

public final class GuiText {

    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.legacySection();
    private static final PlainTextComponentSerializer PLAIN =
            PlainTextComponentSerializer.plainText();

    private GuiText() {
    }

    public static Component component(String input) {
        return LEGACY.deserialize(
                TextColor.color(input == null ? "" : input)
        ).decoration(TextDecoration.ITALIC, false);
    }

    public static List<Component> lore(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }

        return lines.stream()
                .map(GuiText::component)
                .toList();
    }

    public static String plain(Component component) {
        return component == null ? "" : PLAIN.serialize(component);
    }

    public static String plain(String input) {
        return plain(component(input));
    }

    public static void apply(
            ItemMeta meta,
            String displayName,
            List<String> lore
    ) {
        meta.displayName(component(displayName));
        meta.lore(lore(lore));
    }
}
