package net.mineacle.core.common.gui;

import java.util.List;

public final class GuiSearchLore {

    private GuiSearchLore() {
    }

    public static List<String> inactive(
            @SuppressWarnings("unused")
            String subject
    ) {
        return List.of(
                "&#bbbbbbClick to search"
        );
    }

    public static List<String> active(
            String query
    ) {
        String display =
                query == null
                        || query.isBlank()
                        ? "None"
                        : query.replace(
                        '_',
                        ' '
                );

        return List.of(
                "&#bbbbbbCurrent: &#D0AFFF"
                        + display,
                "",
                "&#bbbbbbLeft-click to search again",
                "&#bbbbbbRight-click to clear"
        );
    }
}
