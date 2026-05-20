package net.mineacle.core.links;

import java.util.List;

public record LinkEntry(
        String key,
        List<String> aliases,
        String url,
        String header,
        List<String> lines,
        String button,
        String hover,
        String fallbackColor
) {
    public boolean matches(String input) {
        if (input == null) {
            return false;
        }

        if (key.equalsIgnoreCase(input)) {
            return true;
        }

        for (String alias : aliases) {
            if (alias.equalsIgnoreCase(input)) {
                return true;
            }
        }

        return false;
    }
}
