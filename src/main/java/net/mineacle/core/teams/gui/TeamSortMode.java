package net.mineacle.core.teams.gui;

public enum TeamSortMode {
    RANK("Rank"),
    ONLINE("Online"),
    NAME("Name");

    private final String displayName;

    TeamSortMode(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public TeamSortMode next() {
        TeamSortMode[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    public TeamSortMode previous() {
        TeamSortMode[] values = values();
        return values[
                Math.floorMod(
                        ordinal() - 1,
                        values.length
                )
        ];
    }
}
