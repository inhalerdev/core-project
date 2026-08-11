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

    public String permission() {
        return core.getConfig().getString(
                "nickname.permission",
                "mineaclechat.nick"
        );
    }

    /**
     * LuckPerms is the authority. MineacleCore only checks the capability
     * permission in the player's current context.
     */
    public boolean accessDenied(Player player) {
        return player == null
                || !enabled()
                || !player.hasPermission(
                        permission()
                );
    }
}
