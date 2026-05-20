package net.mineacle.core.links;

import java.util.List;

public record LinkItem(
        String key,
        int slot,
        String material,
        String texture,
        String name,
        List<String> lore,
        String url,
        String promptHeader,
        List<String> promptLines,
        String clickText,
        String hover,
        String fallbackColor
) {
}
