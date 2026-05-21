package net.mineacle.core.bounty;

import net.mineacle.core.Core;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.List;

public final class BountyConfirmGui {

    public static final String TITLE = "Place Bounty";
    public static final int SIZE = 27;
    public static final int CANCEL_SLOT = 11;
    public static final int TARGET_SLOT = 13;
    public static final int CONFIRM_SLOT = 15;

    private BountyConfirmGui() {
    }

    public static void open(Core core, Player player, OfflinePlayer target, long amountCents, BountyService bountyService) {
        Inventory inventory = Bukkit.createInventory(null, SIZE, TITLE);

        inventory.setItem(CANCEL_SLOT, item(
                Material.RED_STAINED_GLASS_PANE,
                "&cCancel",
                List.of("&#bbbbbbDo not place this bounty")
        ));

        inventory.setItem(TARGET_SLOT, targetItem(target, amountCents, bountyService));

        inventory.setItem(CONFIRM_SLOT, item(
                Material.LIME_STAINED_GLASS_PANE,
                "&#ff88ffPlace Bounty",
                List.of(
                        "&#bbbbbbYou pay: &a" + bountyService.format(amountCents),
                        "&#bbbbbbTarget: &#ff88ff" + DisplayNames.displayName(target),
                        "",
                        "&#bbbbbbThis money is paid now",
                        "&#bbbbbbWhoever defeats them gets paid",
                        "",
                        "&#ff88ffClick to confirm"
                )
        ));

        player.openInventory(inventory);
    }

    private static ItemStack targetItem(OfflinePlayer target, long amountCents, BountyService bountyService) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();

        if (meta == null) {
            return item;
        }

        meta.setOwningPlayer(target);
        meta.setDisplayName(TextColor.color("&d" + DisplayNames.displayName(target)));
        meta.setLore(List.of(
                TextColor.color("&#bbbbbbTarget: &#ff88ff" + DisplayNames.displayName(target)),
                TextColor.color("&#bbbbbbReward: &a" + bountyService.format(amountCents)),
                "",
                TextColor.color("&#bbbbbbDefeat this player"),
                TextColor.color("&#bbbbbbto claim the reward")
        ));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack item(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        if (meta == null) {
            return item;
        }

        meta.setDisplayName(TextColor.color(name));
        meta.setLore(lore.stream().map(TextColor::color).toList());
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }
}
