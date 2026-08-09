package net.mineacle.core.bounty;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class BountyConfirmGui {

    public static final int SIZE = 27;
    public static final int CANCEL_SLOT = 11;
    public static final int TARGET_SLOT = 13;
    public static final int CONFIRM_SLOT = 15;

    private BountyConfirmGui() {
    }

    public static void open(
            Core core,
            Player player,
            OfflinePlayer target,
            long amountCents,
            BountyService bountyService
    ) {
        ConfirmHolder holder = new ConfirmHolder(
                target.getUniqueId(),
                amountCents
        );
        Inventory inventory = Bukkit.createInventory(
                holder,
                SIZE,
                legacy("Place Bounty")
        );
        holder.inventory = inventory;

        inventory.setItem(
                CANCEL_SLOT,
                item(
                        Material.RED_STAINED_GLASS_PANE,
                        "&cCancel",
                        List.of(
                                "&#bbbbbbDo not place this bounty"
                        )
                )
        );

        inventory.setItem(
                TARGET_SLOT,
                targetItem(
                        target,
                        amountCents,
                        bountyService
                )
        );

        inventory.setItem(
                CONFIRM_SLOT,
                item(
                        Material.LIME_STAINED_GLASS_PANE,
                        "&aPlace Bounty",
                        List.of(
                                "&#bbbbbbYou pay: &a"
                                        + bountyService.format(
                                        amountCents
                                ),
                                "&#bbbbbbTarget: &#bbbbbb"
                                        + bountyService.displayName(
                                        target
                                ),
                                "",
                                "&#bbbbbbThe payment is taken immediately",
                                "&#bbbbbbThe killer receives the reward",
                                "",
                                "&aClick to confirm"
                        )
                )
        );

        player.openInventory(inventory);
    }

    public static ConfirmHolder holder(Inventory inventory) {
        if (inventory == null
                || !(inventory.getHolder()
                instanceof ConfirmHolder holder)) {
            return null;
        }

        return holder;
    }

    private static ItemStack targetItem(
            OfflinePlayer target,
            long amountCents,
            BountyService bountyService
    ) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta rawMeta = item.getItemMeta();

        if (!(rawMeta instanceof SkullMeta meta)) {
            return item;
        }

        meta.setOwningPlayer(target);
        meta.displayName(
                legacy(
                        "&#bbbbbb"
                                + bountyService.displayName(target)
                )
        );
        meta.lore(noItalic(List.of(
                legacy(
                        "&#bbbbbbBounty: &a"
                                + bountyService.format(amountCents)
                ),
                legacy(""),
                legacy(
                        "&#bbbbbbDefeat this player to claim the reward"
                )
        )));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack item(
            Material material,
            String name,
            List<String> loreLines
    ) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        if (meta == null) {
            return item;
        }

        meta.displayName(legacy(name));

        List<Component> lore = new ArrayList<>();

        for (String line : loreLines) {
            lore.add(legacy(line));
        }

        meta.lore(noItalic(lore));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private static List<Component> noItalic(
            List<Component> input
    ) {
        List<Component> output = new ArrayList<>();

        for (Component component : input) {
            output.add(
                    component.decoration(
                            TextDecoration.ITALIC,
                            false
                    )
            );
        }

        return output;
    }

    private static Component legacy(String text) {
        return LegacyComponentSerializer.legacySection()
                .deserialize(
                        TextColor.color(text == null ? "" : text)
                )
                .decoration(TextDecoration.ITALIC, false);
    }

    public static final class ConfirmHolder
            implements InventoryHolder {

        private final UUID targetId;
        private final long amountCents;
        private boolean consumed;
        private Inventory inventory;

        private ConfirmHolder(
                UUID targetId,
                long amountCents
        ) {
            this.targetId = targetId;
            this.amountCents = amountCents;
        }

        public UUID targetId() {
            return targetId;
        }

        public long amountCents() {
            return amountCents;
        }

        public synchronized boolean tryConsume() {
            if (consumed) {
                return false;
            }

            consumed = true;
            return true;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
