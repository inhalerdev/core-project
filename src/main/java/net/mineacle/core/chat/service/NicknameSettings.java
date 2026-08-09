package net.mineacle.core.chat.service;

import net.mineacle.core.Core;
import org.bukkit.entity.Player;

public final class NicknameSettings {

    private final Core core;

    public NicknameSettings(Core core) {
        this.core = core;
    }

    public boolean enabled() {
        return core.getConfig().getBoolean(
                "nickname.enabled",
                true
        );
    }

    public boolean allowDefault() {
        return core.getConfig().getBoolean(
                "nickname.allow-default",
                false
        );
    }

    public String plusPermission() {
        return core.getConfig().getString(
                "nickname.plus-permission",
                "mineacle.plus"
        );
    }

    public String permission() {
        return core.getConfig().getString(
                "nickname.permission",
                "mineaclechat.nick"
        );
    }

    public boolean canUse(Player player) {
        if (player == null || !enabled()) {
            return false;
        }

        if (allowDefault()) {
            return true;
        }

        return player.hasPermission(plusPermission())
                || player.hasPermission(permission());
    }

    /**
     * Runtime permission checks are used because Plus and staff permissions
     * are intentionally separate.
     */
    public String commandPermission() {
        return "";
    }
}
