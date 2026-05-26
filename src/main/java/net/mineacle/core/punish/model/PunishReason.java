package net.mineacle.core.punish.model;

import java.util.List;

public record PunishReason(
    String key,
    int slot,
    String material,
    String name,
    List<String> lore,
    String reason
) { }
