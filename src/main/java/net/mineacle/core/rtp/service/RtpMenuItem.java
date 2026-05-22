package net.mineacle.core.rtp.service;

import org.bukkit.Material;

import java.util.List;

public record RtpMenuItem(
        String key,
        int slot,
        Material material,
        String name,
        List<String> lore,
        String destination,
        String actionType,
        String actionServer,
        String fallbackCommand
) {
}
