package net.mineacle.core.rtp.service;

import org.bukkit.Location;

import java.util.UUID;

public record OriginRtpRequest(
        UUID playerId,
        String playerName,
        Location startLocation,
        boolean plus,
        long createdAtMillis
) {
}