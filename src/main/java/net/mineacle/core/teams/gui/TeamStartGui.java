package net.mineacle.core.teams.gui;

import net.mineacle.core.Core;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.teams.service.TeamInviteService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

public final class TeamStartGui {

    public static final String TITLE = TextColor.color("&#8436FETeam Menu");

    public static final int CREATE_SLOT = 11;
    public static final int INVITES_SLOT = 13;
    public static final int INFO_SLOT = 15;

    private static final String PRIMARY = "&#8436FE";
    private static final String SECONDARY = "&#B078FF";
    private static final String ACCENT = "&#D0AFFF";
    private static final String BODY = "&#bbbbbb";

    private TeamStartGui() {
    }

    public static void open(
            Core core,
            Player player,
            TeamInviteService inviteService
    ) {
        Inventory inventory = Bukkit.createInventory(null, 27, TITLE);
        boolean hasInvite = inviteService.hasInvite(player.getUniqueId());

        inventory.setItem(
                CREATE_SLOT,
                item(
                        Material.PURPLE_BANNER,
                        PRIMARY + "Create Team",
                        List.of(
                                BODY + "Create your own team",
                                ACCENT + "Click to autofill " + PRIMARY + "/team create"
                        )
                )
        );

        inventory.setItem(
                INVITES_SLOT,
                item(
                        hasInvite
                                ? Material.LIME_STAINED_GLASS_PANE
                                : Material.GRAY_STAINED_GLASS_PANE,
                        hasInvite
                                ? SECONDARY + "Team Invites"
                                : BODY + "No Team Invites",
                        List.of(
                                hasInvite
                                        ? BODY + "You have a pending team invite"
                                        : BODY + "You do not have any team invites",
                                ACCENT + "Click to view invites"
                        )
                )
        );

        inventory.setItem(
                INFO_SLOT,
                item(
                        Material.BOOK,
                        PRIMARY + "Teams",
                        List.of(
                                BODY + "Create a team and invite friends",
                                BODY + "Set Team Home and manage Team PvP",
                                BODY + "Use Team Chat with your members"
                        )
                )
        );

        player.openInventory(inventory);
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

        meta.setDisplayName(TextColor.color(name));
        meta.setLore(lore.stream().map(TextColor::color).toList());
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }
}
