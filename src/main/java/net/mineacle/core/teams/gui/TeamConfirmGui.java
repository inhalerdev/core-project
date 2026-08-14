package net.mineacle.core.teams.gui;

import net.mineacle.core.common.gui.GuiText;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

public final class TeamConfirmGui {

    public static final String TITLE = "Confirm Action";

    public static final int CANCEL_SLOT = 11;
    public static final int ACTION_SLOT = 13;
    public static final int CONFIRM_SLOT = 15;

    private static final String BODY =
            "&#bbbbbb";

    private TeamConfirmGui() {
    }

    public static void open(
            Player player,
            String teamId,
            String action,
            UUID targetId,
            String actionName
    ) {
        ConfirmHolder holder =
                new ConfirmHolder(
                        teamId,
                        action,
                        targetId,
                        requiresSecondConfirm(action)
                );
        Inventory inventory =
                Bukkit.createInventory(
                        holder,
                        27,
                        GuiText.title(TITLE)
                );
        holder.inventory = inventory;

        inventory.setItem(
                CANCEL_SLOT,
                item(
                        Material.RED_STAINED_GLASS_PANE,
                        "&cCancel",
                        List.of(
                                BODY + "Return without changes"
                        )
                )
        );
        inventory.setItem(
                ACTION_SLOT,
                item(
                        Material.RED_DYE,
                        "&c" + actionName,
                        List.of(
                                holder.requiresSecondConfirm()
                                        ? BODY + "High-risk team action"
                                        : BODY + "Review this action"
                        )
                )
        );
        inventory.setItem(
                CONFIRM_SLOT,
                confirmItem(
                        false,
                        holder.requiresSecondConfirm()
                )
        );

        player.openInventory(inventory);
    }

    public static void showUnarmed(
            Inventory inventory
    ) {
        if (inventory == null
                || !(inventory.getHolder(false)
                instanceof ConfirmHolder holder)) {
            return;
        }

        inventory.setItem(
                CONFIRM_SLOT,
                confirmItem(
                        false,
                        holder.requiresSecondConfirm()
                )
        );
    }

    public static void showArmed(
            Inventory inventory
    ) {
        if (inventory == null
                || !(inventory.getHolder(false)
                instanceof ConfirmHolder holder)
                || !holder.requiresSecondConfirm()) {
            return;
        }

        inventory.setItem(
                CONFIRM_SLOT,
                confirmItem(
                        true,
                        true
                )
        );
    }

    private static boolean requiresSecondConfirm(
            String action
    ) {
        if (action == null) {
            return true;
        }

        return switch (action) {
            case "BAN",
                    "TRANSFER",
                    "LEAVE",
                    "DISBAND",
                    "DELETE_HOME" -> true;
            default -> false;
        };
    }

    private static ItemStack confirmItem(
            boolean armed,
            boolean requiresSecondConfirm
    ) {
        if (!requiresSecondConfirm) {
            return item(
                    Material.LIME_STAINED_GLASS_PANE,
                    "&aConfirm",
                    List.of(
                            BODY + "Click to continue"
                    )
            );
        }

        return item(
                Material.LIME_STAINED_GLASS_PANE,
                armed
                        ? "&aConfirm Again"
                        : "&aConfirm",
                List.of(
                        armed
                                ? BODY + "Click again to continue"
                                : BODY + "Click once to ready"
                )
        );
    }

    private static ItemStack item(
            Material material,
            String name,
            List<String> lore
    ) {
        ItemStack item =
                new ItemStack(material);
        ItemMeta meta =
                item.getItemMeta();

        if (meta == null) {
            return item;
        }

        GuiText.apply(
                meta,
                name,
                lore
        );
        meta.addItemFlags(
                ItemFlag.HIDE_ATTRIBUTES
        );
        item.setItemMeta(meta);
        return item;
    }

    public static final class ConfirmHolder
            implements InventoryHolder {

        private final String teamId;
        private final String action;
        private final UUID targetId;
        private final boolean requiresSecondConfirm;
        private Inventory inventory;

        private ConfirmHolder(
                String teamId,
                String action,
                UUID targetId,
                boolean requiresSecondConfirm
        ) {
            this.teamId = teamId;
            this.action = action;
            this.targetId = targetId;
            this.requiresSecondConfirm = requiresSecondConfirm;
        }

        public String teamId() {
            return teamId;
        }

        public String action() {
            return action;
        }

        public UUID targetId() {
            return targetId;
        }

        public boolean requiresSecondConfirm() {
            return requiresSecondConfirm;
        }

        public String token() {
            return (teamId == null ? "-" : teamId)
                    + ":"
                    + action
                    + ":"
                    + (targetId == null
                    ? "-"
                    : targetId.toString());
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }
}
