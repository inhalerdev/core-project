package net.mineacle.core.teams.gui;

public enum TeamSortMode {

    JOIN_DATE("Join Date"),
    PERMISSIONS("Permissions"),
    ALPHABETICALLY("Alphabetically"),
    ONLINE_MEMBERS("Online Members"),
    MONEY("Money");

    private final String displayName;

    TeamSortMode(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public TeamSortMode next() {
        TeamSortMode[] modes = values();
        return modes[(ordinal() + 1) % modes.length];
    }
}