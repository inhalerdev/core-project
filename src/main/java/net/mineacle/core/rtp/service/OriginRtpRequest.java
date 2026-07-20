package net.mineacle.core.rtp.service;

import java.util.UUID;

public record OriginRtpRequest(
        UUID sessionId,
        UUID playerId,
        boolean plus,
        String destination,
        long createdAtMillis
) {
}
