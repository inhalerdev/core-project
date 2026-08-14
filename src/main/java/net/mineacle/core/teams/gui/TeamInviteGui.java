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
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public final class TeamInviteGui {

    public static final String TITLE = "Team Invites";

    public static final int ACCEPT_SLOT = 11;
    public static final int CENTER_SLOT = 13;
    public static final int DENY_SLOT = 15;

    private static final String PRIMARY =
            "&#8436FE";
    private static final String SECONDARY =
            "&#B078FF";
    private static final String ACCENT =
            "&#D0AFFF";
    private static final String BODY =
            "&#bbbbbb";

    private TeamInviteGui() {
    }

    public static void open(
            Player player,
            TeamInviteService inviteService,
            TeamService teamService
    ) {
        TeamInviteRecord invite =
                inviteService.getInvite(
                        player.getUniqueId()
                );
        InviteHolder holder =
                new InviteHolder();
        Inventory inventory =
                Bukkit.createInventory(
                        holder,
                        27,
                        GuiText.title(TITLE)
                );
        holder.inventory = inventory;

        if (invite == null) {
            inventory.setItem(
                    CENTER_SLOT,
                    item(
                            Material.GRAY_DYE,
                            BODY
                                    + "No Team Invites",
                            List.of(
                                    BODY
                                            + "No invite waiting"
                            )
                    )
            );
            player.openInventory(
                    inventory
            );
            return;
        }

        TeamRecord team =
                teamService.getTeamById(
                        invite.teamId()
                );

        if (team == null) {
            inviteService.denyInvite(
                    player.getUniqueId()
            );
            inventory.setItem(
                    CENTER_SLOT,
                    item(
                            Material.BARRIER,
                            "&cInvite Expired",
                            List.of(
                                    BODY
                                            + "That team no longer exists"
                            )
                    )
            );
            player.openInventory(
                    inventory
            );
            return;
        }

        OfflinePlayer inviter =
                Bukkit.getOfflinePlayer(
                        invite.inviterId()
                );
        String inviterName =
                DisplayNames.displayName(
                        inviter
                );

        inventory.setItem(
                ACCEPT_SLOT,
                item(
                        Material
                                .LIME_STAINED_GLASS_PANE,
                        "&aAccept",
                        List.of(
                                BODY
                                        + "Join "
                                        + PRIMARY
                                        + team.name()
                        )
                )
        );
        inventory.setItem(
                CENTER_SLOT,
                item(
                        Material.PURPLE_BANNER,
                        PRIMARY + team.name(),
                        List.of(
                                BODY
                                        + "Invited by "
                                        + SECONDARY
                                        + inviterName,
                                BODY
                                        + "Expires in "
                                        + ACCENT
                                        + inviteService
                                        .remainingSeconds(
                                                player.getUniqueId()
                                        )
                                        + "s"
                        )
                )
        );
        inventory.setItem(
                DENY_SLOT,
                item(
                        Material
                                .RED_STAINED_GLASS_PANE,
                        "&cDeny",
                        List.of(
                                BODY
                                        + "Decline this invite"
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

    public static final class InviteHolder
            implements InventoryHolder {

        private Inventory inventory;

        private InviteHolder() {
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }
}
