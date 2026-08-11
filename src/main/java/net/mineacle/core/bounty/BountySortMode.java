package net.mineacle.core.bounty;

public enum BountySortMode {

    AMOUNT("Highest Bounty"),
    ONLINE("Online First"),
    RECENT("Recently Updated"),
    NAME("Name");

    private final String displayName;

    BountySortMode(
            String displayName
    ) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public BountySortMode next() {
        BountySortMode[] modes = values();

        return modes[
                (ordinal() + 1)
                        % modes.length
                ];
    }

    public BountySortMode previous() {
        BountySortMode[] modes = values();

        return modes[
                (ordinal()
                        + modes.length
                        - 1)
                        % modes.length
                ];
    }
}
