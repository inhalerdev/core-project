package net.mineacle.core.bounty.gui;

import net.kyori.adventure.text.Component;
import net.mineacle.core.bounty.service.BountyService;
import net.mineacle.core.common.gui.GuiText;
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
import org.jetbrains.annotations.NotNull;

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
            Player player,
            OfflinePlayer target,
            long amountCents,
            int returnPage,
            BountyService bountyService
    ) {
        ConfirmHolder holder =
                new ConfirmHolder(
                        target.getUniqueId(),
                        amountCents,
                        returnPage
                );
        Inventory inventory =
                Bukkit.createInventory(
                        holder,
                        SIZE,
                        GuiText.title(
                                "Place Bounty"
                        )
                );
        holder.inventory = inventory;

        long current =
                bountyService.getAmount(
                        target.getUniqueId()
                );
        long total;

        try {
            total =
                    Math.addExact(
                            current,
                            amountCents
                    );
        } catch (
                ArithmeticException exception
        ) {
            total = Long.MAX_VALUE;
        }

        inventory.setItem(
                CANCEL_SLOT,
                item(
                        Material.RED_STAINED_GLASS_PANE,
                        "&cCancel",
                        "&#bbbbbbReturn without placing bounty"
                )
        );

        inventory.setItem(
                TARGET_SLOT,
                targetItem(
                        target,
                        amountCents,
                        current,
                        total,
                        bountyService
                )
        );

        inventory.setItem(
                CONFIRM_SLOT,
                item(
                        Material.LIME_STAINED_GLASS_PANE,
                        "&aPlace Bounty",
                        "&#bbbbbbPay: &a"
                                + bountyService.format(
                                amountCents
                        ),
                        "&#bbbbbbNew Bounty: &a"
                                + bountyService.format(
                                total
                        ),
                        "",
                        "&#bbbbbbClick to confirm"
                )
        );

        player.openInventory(inventory);
    }

    public static ConfirmHolder holder(
            Inventory inventory
    ) {
        if (inventory == null
                || !(inventory.getHolder()
                instanceof ConfirmHolder holder)) {
            return null;
        }

        return holder;
    }

    private static ItemStack targetItem(
            OfflinePlayer target,
            long contribution,
            long current,
            long total,
            BountyService bountyService
    ) {
        ItemStack item =
                new ItemStack(
                        Material.PLAYER_HEAD
                );
        ItemMeta rawMeta =
                item.getItemMeta();

        if (!(rawMeta
                instanceof SkullMeta meta)) {
            return item;
        }

        meta.setOwningPlayer(target);
        meta.displayName(
                GuiText.component(
                        "&#B078FF"
                                + bountyService.displayName(
                                target
                        )
                )
        );

        List<Component> lore =
                new ArrayList<>();
        lore.add(
                GuiText.component(
                        "&#bbbbbbCurrent: &a"
                                + bountyService.format(
                                current
                        )
                )
        );
        lore.add(
                GuiText.component(
                        "&#bbbbbbAdd: &a"
                                + bountyService.format(
                                contribution
                        )
                )
        );
        lore.add(
                GuiText.component(
                        "&#bbbbbbTotal: &a"
                                + bountyService.format(
                                total
                        )
                )
        );

        long payout =
                bountyService.taxedPayout(
                        total
                );

        if (payout != total) {
            lore.add(
                    GuiText.component(
                            "&#bbbbbbReward: &a"
                                    + bountyService.format(
                                    payout
                            )
                    )
            );
        }

        meta.lore(
                List.copyOf(lore)
        );
        meta.addItemFlags(
                ItemFlag.HIDE_ATTRIBUTES
        );
        item.setItemMeta(meta);

        return item;
    }

    private static ItemStack item(
            Material material,
            String name,
            String... loreLines
    ) {
        ItemStack item =
                new ItemStack(material);
        ItemMeta meta =
                item.getItemMeta();

        if (meta == null) {
            return item;
        }

        meta.displayName(
                GuiText.component(name)
        );
        meta.lore(
                GuiText.lore(
                        List.of(
                                loreLines
                        )
                )
        );
        meta.addItemFlags(
                ItemFlag.HIDE_ATTRIBUTES
        );
        item.setItemMeta(meta);

        return item;
    }

    public static final class ConfirmHolder
            implements InventoryHolder {

        private final UUID targetId;
        private final long amountCents;
        private final int returnPage;

        private boolean consumed;
        private Inventory inventory;

        private ConfirmHolder(
                UUID targetId,
                long amountCents,
                int returnPage
        ) {
            this.targetId = targetId;
            this.amountCents = amountCents;
            this.returnPage = returnPage;
        }

        public UUID targetId() {
            return targetId;
        }

        public long amountCents() {
            return amountCents;
        }

        public int returnPage() {
            return returnPage;
        }

        public synchronized boolean tryConsume() {
            if (consumed) {
                return false;
            }

            consumed = true;
            return true;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }
}
