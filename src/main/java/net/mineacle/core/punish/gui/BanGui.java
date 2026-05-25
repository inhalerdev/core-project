package net.mineacle.core.punish.gui;

import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.punish.service.PunishService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class BanGui {

    public static final int CONFIRM_SLOT = 26;
    public static final int INFO_SLOT = 4;

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    private BanGui() {
    }

    public static void open(Player admin, OfflinePlayer target, PunishService service) {
        Inventory inventory = Bukkit.createInventory(null, 27, service.title());
        Session session = SESSIONS.computeIfAbsent(admin.getUniqueId(), uuid -> new Session(target.getUniqueId(), null, null));
        SESSIONS.put(admin.getUniqueId(), new Session(target.getUniqueId(), session.reason(), session.duration()));

        inventory.setItem(INFO_SLOT, item(Material.PLAYER_HEAD, "&#ff88ff" + name(target), java.util.List.of(
                "&#bbbbbbSelect a reason and duration",
                "&#bbbbbbThen confirm the ban"
        )));

        for (PunishService.ReasonOption reason : service.reasons()) {
            inventory.setItem(reason.slot(), item(material(reason.material()), reason.name(), reason.lore()));
        }

        for (PunishService.DurationOption duration : service.durations()) {
            inventory.setItem(duration.slot(), item(material(duration.material()), duration.name(), duration.lore()));
        }

        inventory.setItem(CONFIRM_SLOT, item(Material.LIME_DYE, "&#ff88ffConfirm Ban", java.util.List.of(
                "&#bbbbbbCreates an appeal ID",
                "&#bbbbbband runs LiteBans"
        )));

        admin.openInventory(inventory);
    }

    public static boolean isTitle(String title, PunishService service) {
        return title != null && org.bukkit.ChatColor.stripColor(title).equals(org.bukkit.ChatColor.stripColor(service.title()));
    }

    public static Session session(Player player) {
        return SESSIONS.get(player.getUniqueId());
    }

    public static void setReason(Player player, PunishService.ReasonOption reason) {
        Session current = SESSIONS.get(player.getUniqueId());
        if (current != null) {
            SESSIONS.put(player.getUniqueId(), new Session(current.targetUuid(), reason, current.duration()));
        }
    }

    public static void setDuration(Player player, PunishService.DurationOption duration) {
        Session current = SESSIONS.get(player.getUniqueId());
        if (current != null) {
            SESSIONS.put(player.getUniqueId(), new Session(current.targetUuid(), current.reason(), duration));
        }
    }

    public static void clear(Player player) {
        SESSIONS.remove(player.getUniqueId());
    }

    private static ItemStack item(Material material, String name, java.util.List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();

        if (meta == null) {
            return stack;
        }

        meta.setDisplayName(TextColor.color(name));
        meta.setLore(lore.stream().map(TextColor::color).toList());
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        stack.setItemMeta(meta);
        return stack;
    }

    private static Material material(String raw) {
        try {
            return Material.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return Material.PAPER;
        }
    }

    private static String name(OfflinePlayer player) {
        return player.getName() == null ? player.getUniqueId().toString() : player.getName();
    }

    public record Session(UUID targetUuid, PunishService.ReasonOption reason, PunishService.DurationOption duration) {
    }
}
