package net.mineacle.core.teams.model;

import java.util.Locale;

public enum TeamRole {
    FOUNDER(0, "Founder", "&#8436FE"),
    MVP(1, "MVP", "&#B078FF"),
    VIP(2, "VIP", "&#D0AFFF"),
    MEMBER(3, "Member", "&#bbbbbb");

    private final int priority;
    private final String displayName;
    private final String color;

    TeamRole(
            int priority,
            String displayName,
            String color
    ) {
        this.priority = priority;
        this.displayName = displayName;
        this.color = color;
    }

    public int priority() {
        return priority;
    }

    public String displayName() {
        return displayName;
    }

    public String color() {
        return color;
    }

    public boolean isFounder() {
        return this == FOUNDER;
    }

    /**
     * Compatibility name retained for existing callers. "Admin" now means a
     * team-management role, not the legacy ADMIN enum value.
     */
    public boolean isAdmin() {
        return this == FOUNDER || this == MVP;
    }

    public boolean canInvite() {
        return this == FOUNDER
                || this == MVP
                || this == VIP;
    }

    public boolean canModerate() {
        return this == FOUNDER || this == MVP;
    }

    public boolean canTogglePvp() {
        return this == FOUNDER || this == MVP;
    }

    public boolean canManageTeamHome() {
        return this == FOUNDER;
    }

    public boolean canManageBans() {
        return this == FOUNDER || this == MVP;
    }

    public boolean canModerate(TeamRole target) {
        if (target == null
                || target == FOUNDER
                || !canModerate()) {
            return false;
        }

        if (this == FOUNDER) {
            return true;
        }

        return target == VIP || target == MEMBER;
    }

    public TeamRole promoted() {
        return switch (this) {
            case MEMBER -> VIP;
            case VIP -> MVP;
            case MVP, FOUNDER -> this;
        };
    }

    public TeamRole demoted() {
        return switch (this) {
            case MVP -> VIP;
            case VIP -> MEMBER;
            case MEMBER, FOUNDER -> this;
        };
    }

    public boolean canBePromoted() {
        return this == MEMBER || this == VIP;
    }

    public boolean canBeDemoted() {
        return this == MVP || this == VIP;
    }

    /**
     * Stored legacy ADMIN members migrate in memory to MVP. The next normal
     * write persists MVP, so no destructive migration pass is required.
     */
    public static TeamRole fromStored(String value) {
        if (value == null || value.isBlank()) {
            return MEMBER;
        }

        String normalized = value
                .trim()
                .toUpperCase(Locale.ROOT);

        if (normalized.equals("ADMIN")) {
            return MVP;
        }

        try {
            return valueOf(normalized);
        } catch (IllegalArgumentException ignored) {
            return MEMBER;
        }
    }
}
