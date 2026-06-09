package net.mineacle.core.chat.service;

import net.mineacle.core.Core;
import org.bukkit.entity.Player;

public final class NicknameSettings {

    private final Core core;

    public NicknameSettings(Core core) {
        this.core = core;
    }

    public boolean enabled() {
        return core.getConfig().getBoolean("nickname.enabled", true);
    }

    public boolean showCommandWhenDisabled() {
        return core.getConfig().getBoolean("nickname.show-command-when-disabled", false);
    }

    public boolean registerCommand() {
        return enabled() || showCommandWhenDisabled();
    }

    public boolean canUse(Player player) {
        return player != null
                && enabled()
                && (player.hasPermission("mineacle.plus") || player.hasPermission("mineaclechat.nick"));
    }
}
