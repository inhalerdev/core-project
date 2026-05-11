package net.mineacle.core.tpa.service;

import java.util.UUID;

public record TpaRequest(
        UUID requesterId,
        UUID targetId,
        TpaRequestType type,
        long createdAt
) {
}