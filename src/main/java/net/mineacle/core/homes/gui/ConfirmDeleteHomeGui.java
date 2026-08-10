package net.mineacle.core.homes.gui;

import net.kyori.adventure.text.Component;
import net.mineacle.core.Core;
import net.mineacle.core.common.gui.GuiText;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

public final class ConfirmDeleteHomeGui {

    public static final int CANCEL_SLOT = 11;
    public static final int ACTION_SLOT = 13;
    public static final int CONFIRM_SLOT = 15;

    private static final String PRIMARY = "&#8436FE";
    private static final String ACCENT = "&#D0AFFF";
    private static final String BODY = "&#bbbbbb";

    private ConfirmDeleteHomeGui() {
    }

    public static void openPlayerDelete(
            Core core,
            Player player,
            String displayName
    ) {
        Inventory inventory = Bukkit.createInventory(
                null,
                27,
                title(core, "homes.gui.delete-title", "Delete Home")
        );

        inventory.setItem(
                CANCEL_SLOT,
                item(
                        Material.RED_STAINED_GLASS_PANE,
                        "&cCancel",
                        List.of(
                                BODY + "Do not continue",
                                ACCENT + "Click to cancel this action"
                        )
                )
        );
        inventory.setItem(
                ACTION_SLOT,
                item(
                        Material.RED_DYE,
                        "&cDelete " + displayName,
                        List.of(
                                BODY + "This action needs confirmation",
                                BODY + "Use the &agreen pane " + BODY + "to continue"
                        )
                )
        );
        inventory.setItem(
                CONFIRM_SLOT,
                item(
                        Material.LIME_STAINED_GLASS_PANE,
                        "&aConfirm",
                        List.of(
                                BODY + "Click once to ready this action",
                                ACCENT + "Click again to confirm"
                        )
                )
        );

        player.openInventory(inventory);
    }

    public static void openTeamDelete(Core core, Player player) {
        Inventory inventory = Bukkit.createInventory(
                null,
                27,
                title(core, "homes.gui.team-delete-title", "Delete Team Home")
        );

        inventory.setItem(
                CANCEL_SLOT,
                item(
                        Material.RED_STAINED_GLASS_PANE,
                        "&cCancel",
                        List.of(
                                BODY + "Do not continue",
                                ACCENT + "Click to cancel this action"
                        )
                )
        );
        inventory.setItem(
                ACTION_SLOT,
                item(
                        Material.RED_DYE,
                        "&cDelete Team Home",
                        List.of(
                                BODY + "This action needs confirmation",
                                BODY + "Use the &agreen pane " + BODY + "to continue"
                        )
                )
        );
        inventory.setItem(
                CONFIRM_SLOT,
                item(
                        Material.LIME_STAINED_GLASS_PANE,
                        "&aConfirm",
                        List.of(
                                BODY + "Click once to ready this action",
                                ACCENT + "Click again to confirm"
                        )
                )
        );

        player.openInventory(inventory);
    }

    private static Component title(
            Core core,
            String path,
            String fallback
    ) {
        String plain = GuiText.plain(core.getMessage(path));
        return GuiText.component(
                PRIMARY + (plain.isBlank() ? fallback : plain)
        );
    }

    private static ItemStack item(
            Material material,
            String name,
            List<String> lore
    ) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        if (meta == null) {
            return item;
        }

        GuiText.apply(meta, name, lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }
}
