package net.mineacle.core.bounty;

public enum BountySortMode {
    AMOUNT("Amount"),
    RECENT("Recently Set"),
    NAME("Name");

    private final String displayName;

    BountySortMode(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public BountySortMode next() {
        BountySortMode[] values = values();
        return values[(ordinal() + 1) % values.length];
    }
}
