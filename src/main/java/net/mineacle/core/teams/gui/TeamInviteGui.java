package net.mineacle.core.teams.gui;

import net.mineacle.core.Core;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.teams.model.TeamInviteRecord;
import net.mineacle.core.teams.model.TeamRecord;
import net.mineacle.core.teams.service.TeamInviteService;
import net.mineacle.core.teams.service.TeamService;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

public final class TeamInviteGui {

    public static final String TITLE = ChatColor.DARK_GRAY + "Team Invites";

    public static final int ACCEPT_SLOT = 11;
    public static final int CENTER_SLOT = 13;
    public static final int DENY_SLOT = 15;

    private TeamInviteGui() {
    }

    public static void open(Core core, Player player, TeamInviteService inviteService, TeamService teamService) {
        TeamInviteRecord invite = inviteService.getInvite(player.getUniqueId());

        Inventory inventory = Bukkit.createInventory(null, 27, TITLE);

        if (invite == null) {
            inventory.setItem(CENTER_SLOT, item(
                    Material.GRAY_STAINED_GLASS_PANE,
                    "&#bbbbbbNo Team Invites",
                    List.of("&#bbbbbbYou do not have any pending team invites")
            ));

            player.openInventory(inventory);
            return;
        }

        TeamRecord team = teamService.getTeamById(invite.teamId());

        if (team == null) {
            inviteService.denyInvite(player.getUniqueId());

            inventory.setItem(CENTER_SLOT, item(
                    Material.BARRIER,
                    "&cInvite Expired",
                    List.of("&#bbbbbbThat team no longer exists")
            ));

            player.openInventory(inventory);
            return;
        }

        OfflinePlayer inviter = Bukkit.getOfflinePlayer(invite.inviterId());
        String inviterName = DisplayNames.displayName(inviter);

        inventory.setItem(ACCEPT_SLOT, item(
                Material.LIME_CONCRETE,
                "&dAccept",
                List.of(
                        "&#bbbbbbJoin &d" + team.name(),
                        "&#bbbbbbInvited by &d" + inviterName
                )
        ));

        inventory.setItem(CENTER_SLOT, item(
                Material.PURPLE_BANNER,
                "&d" + team.name(),
                List.of(
                        "&#bbbbbbTeam invite",
                        "&#bbbbbbInvited by &d" + inviterName,
                        "&#bbbbbbExpires in &d" + inviteService.remainingSeconds(player.getUniqueId()) + "s"
                )
        ));

        inventory.setItem(DENY_SLOT, item(
                Material.RED_CONCRETE,
                "&dDeny",
                List.of(
                        "&#bbbbbbDecline this invite",
                        "&#bbbbbbTeam &d" + team.name()
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