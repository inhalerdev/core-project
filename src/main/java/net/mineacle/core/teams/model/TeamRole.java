package net.mineacle.core.teams.model;

public enum TeamRole {
    FOUNDER,
    ADMIN,
    MEMBER;

    public String displayName() {
        return switch (this) {
            case FOUNDER -> "Founder";
            case ADMIN -> "Admin";
            case MEMBER -> "Member";
        };
    }

    public boolean isAdmin() {
        return this == FOUNDER || this == ADMIN;
    }
}