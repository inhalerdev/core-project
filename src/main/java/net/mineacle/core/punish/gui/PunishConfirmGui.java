package net.mineacle.core.punish.gui;

import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.punish.model.PunishSession;
import net.mineacle.core.punish.service.PunishService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

public final class PunishConfirmGui {
    public static final int CANCEL_SLOT = 11;
    public static final int INFO_SLOT = 13;
    public static final int CONFIRM_SLOT = 15;

    private PunishConfirmGui() { }

    public static void open(Player admin, PunishService service) {
        PunishSession session = service.session(admin);
        if (session == null) {
            admin.closeInventory();
            return;
        }

        Inventory inventory = Bukkit.createInventory(null, 27, service.confirmTitle());
        inventory.setItem(CANCEL_SLOT, item(Material.RED_DYE, "&cCancel", List.of("&#bbbbbbCancel this punishment")));
        inventory.setItem(INFO_SLOT, item(Material.BOOK, "&#ff88ffPunishment Summary", List.of(
            "&#bbbbbbPlayer: &#ff88ff" + session.targetName(),
            "&#bbbbbbAction: &#ff88ff" + session.action().displayName(),
            "&#bbbbbbReason: &#ff88ff" + session.reason().reason(),
            "&#bbbbbbDuration: &#ff88ff" + duration(session),
            "",
            "&#bbbbbbCase ID is generated on confirm",
            "&#bbbbbbAppeals: &#ff88ff" + service.appealUrl()
        )));
        inventory.setItem(CONFIRM_SLOT, item(Material.LIME_DYE, "&#ff55ffConfirm", List.of(
            "&#bbbbbbClick confirm twice to continue",
            "&cThis will run the LiteBans command"
        )));
        admin.openInventory(inventory);
    }

    public static boolean isTitle(String title, PunishService service) {
        return TextColor.strip(title).equals(TextColor.strip(service.confirmTitle()));
    }

    private static String duration(PunishSession session) {
        if (session.action().durationRequired() && session.duration() != null) {
            return session.duration().duration();
        }
        return session.action().permanent() ? "Permanent" : "Not needed";
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
