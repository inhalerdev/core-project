package net.mineacle.core.teams.gui;

import net.mineacle.core.common.gui.GuiText;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.stats.VaultMoneyHook;
import net.mineacle.core.teams.model.TeamMemberRecord;
import net.mineacle.core.teams.model.TeamRole;
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
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

public final class TeamMemberGui {

    public static final String TITLE_PREFIX = "Member: ";

    public static final int PROMOTE_SLOT = 10;
    public static final int DEMOTE_SLOT = 11;
    public static final int STATS_SLOT = 13;
    public static final int KICK_SLOT = 15;
    public static final int BAN_SLOT = 16;
    public static final int TRANSFER_SLOT = 22;

    private static final String PRIMARY =
            "&#8436FE";
    private static final String SECONDARY =
            "&#B078FF";
    private static final String ACCENT =
            "&#D0AFFF";
    private static final String BODY =
            "&#bbbbbb";
    private static final String MONEY =
            "&#11fc7b";

    private TeamMemberGui() {
    }

    public static void open(
            Player viewer,
            UUID targetId,
            TeamService teamService
    ) {
        OfflinePlayer target =
                Bukkit.getOfflinePlayer(
                        targetId
                );
        TeamMemberRecord viewerMember =
                teamService.getMember(
                        viewer.getUniqueId()
                );
        TeamMemberRecord targetMember =
                teamService.getMember(
                        targetId
                );

        if (targetMember == null) {
            return;
        }

        String displayName =
                DisplayNames.displayName(
                        target
                );
        TeamRole targetRole =
                targetMember.role();
        boolean online =
                Bukkit.getPlayer(
                        targetId
                ) != null;

        MemberHolder holder =
                new MemberHolder(
                        targetId
                );
        Inventory inventory =
                Bukkit.createInventory(
                        holder,
                        27,
                        GuiText.title(
                                TITLE_PREFIX
                                        + displayName
                        )
                );
        holder.inventory = inventory;

        inventory.setItem(
                4,
                playerHead(
                        target,
                        targetRole.color()
                                + displayName,
                        List.of(
                                BODY
                                        + "Role: "
                                        + targetRole
                                        .color()
                                        + targetRole
                                        .displayName(),
                                BODY
                                        + "Status: "
                                        + (
                                        online
                                                ? "&aOnline"
                                                : BODY
                                                + "Offline"
                                ),
                                BODY
                                        + "Balance: "
                                        + MONEY
                                        + VaultMoneyHook
                                        .formattedBalance(
                                                target
                                        )
                        )
                )
        );

        inventory.setItem(
                STATS_SLOT,
                item(
                        Material.BOOK,
                        SECONDARY
                                + "View Stats",
                        List.of(
                                BODY
                                        + "Open player statistics",
                                BODY
                                        + "Click to view"
                        )
                )
        );

        if (viewerMember == null
                || viewer.getUniqueId()
                .equals(targetId)) {
            viewer.openInventory(
                    inventory
            );
            return;
        }

        TeamRole viewerRole =
                viewerMember.role();

        if (!viewerMember.teamId()
                .equals(
                        targetMember.teamId()
                )) {
            viewer.openInventory(
                    inventory
            );
            return;
        }

        if (viewerRole == TeamRole.FOUNDER
                && targetRole
                .canBePromoted()) {
            inventory.setItem(
                    PROMOTE_SLOT,
                    item(
                            Material.LIME_DYE,
                            "&aPromote",
                            List.of(
                                    BODY
                                            + targetRole
                                            .displayName()
                                            + " "
                                            + BODY
                                            + "→ "
                                            + targetRole
                                            .promoted()
                                            .color()
                                            + targetRole
                                            .promoted()
                                            .displayName(),
                                    BODY
                                            + "Requires confirmation"
                            )
                    )
            );
        }

        if (viewerRole == TeamRole.FOUNDER
                && targetRole
                .canBeDemoted()) {
            inventory.setItem(
                    DEMOTE_SLOT,
                    item(
                            Material.ORANGE_DYE,
                            SECONDARY
                                    + "Demote",
                            List.of(
                                    BODY
                                            + targetRole
                                            .displayName()
                                            + " "
                                            + BODY
                                            + "→ "
                                            + targetRole
                                            .demoted()
                                            .color()
                                            + targetRole
                                            .demoted()
                                            .displayName(),
                                    BODY
                                            + "Requires confirmation"
                            )
                    )
            );
        }

        if (viewerRole.canModerate(
                targetRole
        )) {
            inventory.setItem(
                    KICK_SLOT,
                    item(
                            Material.BARRIER,
                            "&cKick",
                            List.of(
                                    BODY
                                            + "Remove this member",
                                    BODY
                                            + "Requires confirmation"
                            )
                    )
            );
            inventory.setItem(
                    BAN_SLOT,
                    item(
                            Material.REDSTONE_BLOCK,
                            "&cBan",
                            List.of(
                                    BODY
                                            + "Remove and block rejoining",
                                    BODY
                                            + "Duration: "
                                            + ACCENT
                                            + teamService
                                            .banDays()
                                            + " days",
                                    BODY
                                            + "Requires confirmation"
                            )
                    )
            );
        }

        if (viewerRole == TeamRole.FOUNDER
                && targetRole
                != TeamRole.FOUNDER) {
            inventory.setItem(
                    TRANSFER_SLOT,
                    item(
                            Material.NETHER_STAR,
                            PRIMARY
                                    + "Transfer Founder",
                            List.of(
                                    BODY
                                            + "Make "
                                            + SECONDARY
                                            + displayName
                                            + " "
                                            + BODY
                                            + "the founder",
                                    BODY
                                            + "You become "
                                            + TeamRole.MVP
                                            .color()
                                            + TeamRole.MVP
                                            .displayName(),
                                    BODY
                                            + "Requires confirmation"
                            )
                    )
            );
        }

        viewer.openInventory(
                inventory
        );
    }

    private static ItemStack playerHead(
            OfflinePlayer owner,
            String name,
            List<String> lore
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

        meta.setOwningPlayer(owner);
        GuiText.apply(
                meta,
                name,
                lore
        );
        item.setItemMeta(meta);
        return item;
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

    public static final class MemberHolder
            implements InventoryHolder {

        private final UUID targetId;
        private Inventory inventory;

        private MemberHolder(UUID targetId) {
            this.targetId = targetId;
        }

        public UUID targetId() {
            return targetId;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }
}
