package net.mineacle.core.teams.gui;

import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.teams.model.TeamMemberRecord;
import net.mineacle.core.teams.model.TeamRole;
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
import org.bukkit.inventory.meta.SkullMeta;

import java.util.List;
import java.util.UUID;

public final class TeamMemberGui {

    public static final String TITLE_PREFIX = "Member: ";

    private TeamMemberGui() {
    }

    public static void open(Player viewer, UUID targetId, TeamService teamService) {
        OfflinePlayer target = Bukkit.getOfflinePlayer(targetId);
        TeamMemberRecord viewerMember = teamService.getMember(viewer.getUniqueId());
        TeamMemberRecord targetMember = teamService.getMember(targetId);

        String displayName = DisplayNames.prefixedDisplayName(target);
        String titleName = TextColor.strip(DisplayNames.displayName(target));
        String role = targetMember == null ? "Unknown" : targetMember.role().displayName();

        Inventory inventory = Bukkit.createInventory(
                null,
                27,
                ChatColor.DARK_GRAY + TITLE_PREFIX + titleName
        );

        inventory.setItem(4, playerHead(
                target,
                displayName,
                List.of(
                        "&#bbbbbbRank: &d" + role,
                        "&#bbbbbbClick options below to manage"
                )
        ));

        inventory.setItem(13, item(
                Material.BOOK,
                "&dView Stats",
                List.of("&#bbbbbbOpen this player's stats")
        ));

        if (viewerMember == null || targetMember == null) {
            viewer.openInventory(inventory);
            return;
        }

        boolean viewingSelf = viewer.getUniqueId().equals(targetId);
        TeamRole viewerRole = viewerMember.role();
        TeamRole targetRole = targetMember.role();

        if (viewingSelf) {
            inventory.setItem(22, item(
                    Material.PAPER,
                    "&dYour Team Profile",
                    List.of(
                            "&#bbbbbbRank: &d" + role,
                            "&#bbbbbbUse Team Chat from the team toolbar"
                    )
            ));

            viewer.openInventory(inventory);
            return;
        }

        if (viewerRole == TeamRole.FOUNDER && targetRole != TeamRole.FOUNDER) {
            if (targetRole == TeamRole.MEMBER) {
                inventory.setItem(10, item(
                        Material.LIME_DYE,
                        "&aPromote",
                        List.of(
                                "&#bbbbbbPromote this member",
                                "&#bbbbbbFounder only"
                        )
                ));
            }

            if (targetRole == TeamRole.ADMIN) {
                inventory.setItem(11, item(
                        Material.ORANGE_DYE,
                        "&6Demote",
                        List.of(
                                "&#bbbbbbDemote this admin",
                                "&#bbbbbbFounder only"
                        )
                ));
            }

            inventory.setItem(15, item(
                    Material.BARRIER,
                    "&cKick",
                    List.of(
                            "&#bbbbbbRemove this player from the team",
                            "&#bbbbbbRequires confirmation"
                    )
            ));

            inventory.setItem(16, item(
                    Material.REDSTONE_BLOCK,
                    "&4Ban",
                    List.of(
                            "&#bbbbbbKick and block this player",
                            "&#bbbbbbfrom joining for &d7 days"
                    )
            ));

            inventory.setItem(22, item(
                    Material.NETHER_STAR,
                    "&dTransfer Founder",
                    List.of(
                            "&#bbbbbbTransfer team ownership",
                            "&#bbbbbbRequires confirmation"
                    )
            ));
        } else if (viewerRole == TeamRole.ADMIN && targetRole == TeamRole.MEMBER) {
            inventory.setItem(15, item(
                    Material.BARRIER,
                    "&cKick",
                    List.of(
                            "&#bbbbbbRemove this player from the team",
                            "&#bbbbbbAdmins can kick members"
                    )
            ));

            inventory.setItem(16, item(
                    Material.REDSTONE_BLOCK,
                    "&4Ban",
                    List.of(
                            "&#bbbbbbKick and block this player",
                            "&#bbbbbbfrom joining for &d7 days"
                    )
            ));
        }

        viewer.openInventory(inventory);
    }

    private static ItemStack playerHead(OfflinePlayer owner, String name, List<String> lore) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta rawMeta = item.getItemMeta();

        if (!(rawMeta instanceof SkullMeta meta)) {
            return item;
        }

        meta.setOwningPlayer(owner);
        meta.setDisplayName(color(name));
        meta.setLore(lore.stream().map(TeamMemberGui::color).toList());
        item.setItemMeta(meta);

        return item;
    }

    private static ItemStack item(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        if (meta == null) {
            return item;
        }

        meta.setDisplayName(color(name));
        meta.setLore(lore.stream().map(TeamMemberGui::color).toList());
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);

        return item;
    }

    private static String color(String input) {
        return TextColor.color(input);
    }
}