package net.mineacle.core.teams.gui;

import net.mineacle.core.common.gui.GuiText;
import net.mineacle.core.teams.service.TeamInviteService;
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

public final class TeamStartGui {

    public static final String TITLE = "Team Menu";

    public static final int CREATE_SLOT = 11;
    public static final int INVITES_SLOT = 13;
    public static final int INFO_SLOT = 15;

    private static final String PRIMARY =
            "&#8436FE";
    private static final String SECONDARY =
            "&#B078FF";
    private static final String ACCENT =
            "&#D0AFFF";
    private static final String BODY =
            "&#bbbbbb";

    private TeamStartGui() {
    }

    public static void open(
            Player player,
            TeamInviteService inviteService
    ) {
        StartHolder holder =
                new StartHolder();
        Inventory inventory =
                Bukkit.createInventory(
                        holder,
                        27,
                        GuiText.title(TITLE)
                );
        holder.inventory = inventory;

        boolean hasInvite =
                inviteService.hasInvite(
                        player.getUniqueId()
                );

        inventory.setItem(
                CREATE_SLOT,
                item(
                        Material.PURPLE_BANNER,
                        PRIMARY
                                + "Create Team",
                        List.of(
                                BODY
                                        + "Start a team",
                                BODY
                                        + "Click to autofill "
                                        + ACCENT
                                        + "/team create"
                        )
                )
        );

        inventory.setItem(
                INVITES_SLOT,
                item(
                        hasInvite
                                ? Material
                                .LIME_STAINED_GLASS_PANE
                                : Material
                                .GRAY_STAINED_GLASS_PANE,
                        hasInvite
                                ? "&aPending Invite"
                                : BODY
                                + "No Team Invites",
                        List.of(
                                hasInvite
                                        ? BODY
                                        + "Click to review"
                                        : BODY
                                        + "No invite waiting"
                        )
                )
        );

        inventory.setItem(
                INFO_SLOT,
                item(
                        Material.NETHER_STAR,
                        SECONDARY + "Teams",
                        List.of(
                                BODY
                                        + "Team Home • Team Chat • Team PvP",
                                BODY
                                        + "Fast roster and role management"
                        )
                )
        );

        player.openInventory(
                inventory
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

    public static final class StartHolder
            implements InventoryHolder {

        private Inventory inventory;

        private StartHolder() {
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }
}
