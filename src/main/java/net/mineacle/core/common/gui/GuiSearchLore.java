package net.mineacle.core.common.gui;

import java.util.List;

public final class GuiSearchLore {

    private GuiSearchLore() {
    }

    public static List<String> inactive(String subject) {
        String target = normalizeSubject(subject);

        return List.of(
                "&#bbbbbbSearch " + target,
                "",
                "&#ff88ffClick to search"
        );
    }

    public static List<String> active(String query) {
        String display = query == null || query.isBlank()
                ? "None"
                : query.replace('_', ' ');

        return List.of(
                "&#bbbbbbCurrent Search",
                "&#ff88ff" + display,
                "",
                "&#bbbbbbLeft-click to search again",
                "&#bbbbbbRight-click to clear"
        );
    }

    private static String normalizeSubject(String subject) {
        if (subject == null || subject.isBlank()) {
            return "entries";
        }

        return subject.trim();
    }
}
