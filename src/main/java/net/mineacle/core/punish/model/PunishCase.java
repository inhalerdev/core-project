package net.mineacle.core.punish.model;

import java.util.UUID;

public record PunishCase(
    String caseId,
    UUID targetUuid,
    String targetName,
    UUID adminUuid,
    String adminName,
    PunishAction action,
    String reason,
    String duration,
    long createdAtMillis
) { }
