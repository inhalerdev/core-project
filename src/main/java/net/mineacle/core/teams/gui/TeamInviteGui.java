package net.mineacle.core.teams.gui;

import net.mineacle.core.common.gui.GuiText;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.teams.model.TeamInviteRecord;
import net.mineacle.core.teams.model.TeamRecord;
import net.mineacle.core.teams.service.TeamInviteService;
import net.mineacle.core.teams.service.TeamService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

public final class TeamInviteGui {

    public static final String TITLE = "Team Invites";

    public static final int ACCEPT_SLOT = 11;
    public static final int CENTER_SLOT = 13;
    public static final int DENY_SLOT = 15;

    private static final String SECONDARY = "&#B078FF";
    private static final String BODY = "&#bbbbbb";

    private TeamInviteGui() {
    }

    public static void open(
            Player player,
            TeamInviteService inviteService,
            TeamService teamService
    ) {
        TeamInviteRecord invite = inviteService.getInvite(player.getUniqueId());
        Inventory inventory = Bukkit.createInventory(
                null,
                27,
                GuiText.title(TITLE)
        );

        if (invite == null) {
            inventory.setItem(
                    CENTER_SLOT,
                    item(
                            Material.GRAY_STAINED_GLASS_PANE,
                            BODY + "No Team Invites",
                            List.of(BODY + "You do not have any pending team invites")
                    )
            );
            player.openInventory(inventory);
            return;
        }

        TeamRecord team = teamService.getTeamById(invite.teamId());

        if (team == null) {
            inviteService.denyInvite(player.getUniqueId());
            inventory.setItem(
                    CENTER_SLOT,
                    item(
                            Material.BARRIER,
                            "&cInvite Expired",
                            List.of(BODY + "That team no longer exists")
                    )
            );
            player.openInventory(inventory);
            return;
        }

        OfflinePlayer inviter = Bukkit.getOfflinePlayer(invite.inviterId());
        String inviterName = DisplayNames.displayName(inviter);

        inventory.setItem(
                ACCEPT_SLOT,
                item(
                        Material.LIME_CONCRETE,
                        "&aAccept",
                        List.of(
                                BODY + "Join " + SECONDARY + team.name(),
                                BODY + "Invited by " + SECONDARY + inviterName
                        )
                )
        );
        inventory.setItem(
                CENTER_SLOT,
                item(
                        Material.PURPLE_BANNER,
                        SECONDARY + team.name(),
                        List.of(
                                BODY + "Team invite",
                                BODY + "Invited by " + SECONDARY + inviterName,
                                BODY + "Expires in " + SECONDARY
                                        + inviteService.remainingSeconds(player.getUniqueId()) + "s"
                        )
                )
        );
        inventory.setItem(
                DENY_SLOT,
                item(
                        Material.RED_CONCRETE,
                        "&cDeny",
                        List.of(
                                BODY + "Decline this invite",
                                BODY + "Team " + SECONDARY + team.name()
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

        GuiText.apply(meta, name, lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }
}
