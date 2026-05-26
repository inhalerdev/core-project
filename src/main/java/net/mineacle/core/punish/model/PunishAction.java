package net.mineacle.core.punish.model;

import org.bukkit.Material;

public enum PunishAction {
    WARN("warn", "Warn", Material.PAPER, 19, false, false),
    KICK("kick", "Kick", Material.IRON_BOOTS, 20, false, false),
    MUTE("mute", "Permanent Mute", Material.WRITABLE_BOOK, 21, false, true),
    TEMP_MUTE("tempmute", "Temporary Mute", Material.CLOCK, 22, true, false),
    TEMP_BAN("tempban", "Temporary Ban", Material.IRON_AXE, 23, true, false),
    PERMANENT_BAN("ban", "Permanent Ban", Material.BARRIER, 24, false, true);

    private final String key;
    private final String displayName;
    private final Material material;
    private final int slot;
    private final boolean durationRequired;
    private final boolean permanent;

    PunishAction(String key, String displayName, Material material, int slot, boolean durationRequired, boolean permanent) {
        this.key = key;
        this.displayName = displayName;
        this.material = material;
        this.slot = slot;
        this.durationRequired = durationRequired;
        this.permanent = permanent;
    }

    public String key() {
        return key;
    }

    public String displayName() {
        return displayName;
    }

    public Material material() {
        return material;
    }

    public int slot() {
        return slot;
    }

    public boolean durationRequired() {
        return durationRequired;
    }

    public boolean permanent() {
        return permanent;
    }

    public static PunishAction atSlot(int slot) {
        for (PunishAction action : values()) {
            if (action.slot == slot) {
                return action;
            }
        }
        return null;
    }
}
