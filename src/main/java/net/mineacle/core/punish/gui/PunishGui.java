package net.mineacle.core.punish.gui;

import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.punish.model.PunishAction;
import net.mineacle.core.punish.model.PunishDuration;
import net.mineacle.core.punish.model.PunishReason;
import net.mineacle.core.punish.model.PunishSession;
import net.mineacle.core.punish.service.PunishService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class PunishGui {
    public static final int TARGET_SLOT = 4;
    public static final int CONFIRM_SLOT = 49;
    public static final int CLOSE_SLOT = 53;

    private PunishGui() { }

    public static void open(Player admin, PunishService service) {
        PunishSession session = service.session(admin);
        if (session == null) {
            admin.closeInventory();
            return;
        }

        Inventory inventory = Bukkit.createInventory(null, 54, service.title());
        OfflinePlayer target = Bukkit.getOfflinePlayer(session.targetUuid());

        inventory.setItem(TARGET_SLOT, targetItem(target, session));
        for (PunishAction action : PunishAction.values()) {
            inventory.setItem(action.slot(), actionItem(action, session.action() == action));
        }
        for (PunishReason reason : service.reasons()) {
            inventory.setItem(reason.slot(), configuredItem(reason.material(), reason.name(), selectedLore(reason.lore(), session.reason() != null && session.reason().key().equals(reason.key()))));
        }
        for (PunishDuration duration : service.durations()) {
            inventory.setItem(duration.slot(), configuredItem(duration.material(), duration.name(), selectedLore(duration.lore(), session.duration() != null && session.duration().key().equals(duration.key()))));
        }

        inventory.setItem(CONFIRM_SLOT, item(Material.LIME_DYE, "&#ff88ffReview Punishment", List.of(
            "&#bbbbbbOpens the final confirmation menu",
            "&#bbbbbbLiteBans runs after double confirm"
        )));
        inventory.setItem(CLOSE_SLOT, item(Material.BARRIER, "&cClose", List.of("&#bbbbbbClose this menu")));
        admin.openInventory(inventory);
    }

    public static boolean isTitle(String title, PunishService service) {
        return TextColor.strip(title).equals(TextColor.strip(service.title()));
    }

    private static ItemStack targetItem(OfflinePlayer target, PunishSession session) {
        ItemStack stack = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) stack.getItemMeta();
        if (meta == null) {
            return stack;
        }
        meta.setOwningPlayer(target);
        meta.setDisplayName(TextColor.color("&#ff88ff" + session.targetName()));
        meta.setLore(List.of(
            TextColor.color("&#bbbbbbAction: " + selected(session.action() == null ? "None" : session.action().displayName())),
            TextColor.color("&#bbbbbbReason: " + selected(session.reason() == null ? "None" : session.reason().reason())),
            TextColor.color("&#bbbbbbDuration: " + selected(durationLabel(session))),
            TextColor.color(""),
            TextColor.color("&#bbbbbbSelect the punishment details below")
        ));
        stack.setItemMeta(meta);
        return stack;
    }

    private static String durationLabel(PunishSession session) {
        if (session.action() == null) {
            return "None";
        }
        if (!session.action().durationRequired()) {
            return session.action().permanent() ? "Permanent" : "Not needed";
        }
        return session.duration() == null ? "None" : session.duration().duration();
    }

    private static ItemStack actionItem(PunishAction action, boolean selected) {
        List<String> lore = new ArrayList<>();
        lore.add("&#bbbbbbType: &#ff88ff" + action.displayName());
        if (action.durationRequired()) {
            lore.add("&#bbbbbbRequires a duration below");
        } else if (action.permanent()) {
            lore.add("&#bbbbbbPermanent punishment");
        } else {
            lore.add("&#bbbbbbNo duration needed");
        }
        lore.add("");
        lore.add(selected ? "&#ff55ffSelected" : "&#bbbbbbClick to select");
        return item(action.material(), selected ? "&#ff55ff" + action.displayName() : "&#ff88ff" + action.displayName(), lore);
    }

    private static List<String> selectedLore(List<String> base, boolean selected) {
        List<String> lore = new ArrayList<>(base);
        lore.add("");
        lore.add(selected ? "&#ff55ffSelected" : "&#bbbbbbClick to select");
        return lore;
    }

    private static String selected(String value) {
        return "&#ff88ff" + value;
    }

    private static ItemStack configuredItem(String materialName, String name, List<String> lore) {
        Material material;
        try {
            material = Material.valueOf(materialName.toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            material = Material.PAPER;
        }
        return item(material, name, lore);
    }

    private static ItemStack item(Material material, String name, List<String> lore) {
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
}
