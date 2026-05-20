package net.mineacle.core.teams.gui;

import net.mineacle.core.Core;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.teams.service.TeamInviteService;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

public final class TeamStartGui {

    public static final String TITLE = ChatColor.DARK_GRAY + "Team Menu";

    public static final int CREATE_SLOT = 11;
    public static final int INVITES_SLOT = 13;
    public static final int INFO_SLOT = 15;

    private TeamStartGui() {
    }

    public static void open(Core core, Player player, TeamInviteService inviteService) {
        Inventory inventory = Bukkit.createInventory(null, 27, TITLE);

        boolean hasInvite = inviteService.hasInvite(player.getUniqueId());

        inventory.setItem(CREATE_SLOT, item(
                Material.PURPLE_BANNER,
                "&dCreate Team",
                List.of(
                        "&#bbbbbbCreate your own team",
                        "&#bbbbbbClick to autofill &d/team create"
                )
        ));

        inventory.setItem(INVITES_SLOT, item(
                hasInvite ? Material.LIME_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE,
                hasInvite ? "&dTeam Invites" : "&#bbbbbbNo Team Invites",
                List.of(
                        hasInvite ? "&#bbbbbbYou have a pending team invite" : "&#bbbbbbYou do not have any team invites",
                        "&#bbbbbbClick to view invites"
                )
        ));

        inventory.setItem(INFO_SLOT, item(
                Material.BOOK,
                "&dTeams",
                List.of(
                        "&#bbbbbbCreate a team, invite friends",
                        "&#bbbbbbset Team Home, toggle Team PvP",
                        "&#bbbbbband use Team Chat"
                )
        ));

        player.openInventory(inventory);
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