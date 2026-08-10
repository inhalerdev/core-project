package net.mineacle.core.teams.gui;

import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.teams.model.TeamMemberRecord;
import net.mineacle.core.teams.model.TeamRole;
import net.mineacle.core.teams.service.TeamService;
import org.bukkit.Bukkit;
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

    private static final String PRIMARY = "&#8436FE";
    private static final String SECONDARY = "&#B078FF";
    private static final String ACCENT = "&#D0AFFF";
    private static final String BODY = "&#bbbbbb";

    private TeamMemberGui() {
    }

    public static void open(
            Player viewer,
            UUID targetId,
            TeamService teamService
    ) {
        OfflinePlayer target = Bukkit.getOfflinePlayer(targetId);
        TeamMemberRecord viewerMember = teamService.getMember(viewer.getUniqueId());
        TeamMemberRecord targetMember = teamService.getMember(targetId);
        String plainName = DisplayNames.displayName(target);
        String titleName = TextColor.strip(plainName);
        String role = targetMember == null
                ? "Unknown"
                : targetMember.role().displayName();
        boolean online = Bukkit.getPlayer(targetId) != null;

        Inventory inventory = Bukkit.createInventory(
                null,
                27,
                color(PRIMARY + TITLE_PREFIX + titleName)
        );

        inventory.setItem(
                4,
                playerHead(
                        target,
                        (online ? "&a" : BODY) + plainName,
                        List.of(
                                BODY + "Role: " + SECONDARY + role,
                                BODY + "Status: "
                                        + (online ? "&aOnline" : BODY + "Offline"),
                                "",
                                ACCENT + "Use the options below"
                        )
                )
        );

        inventory.setItem(
                13,
                item(
                        Material.BOOK,
                        PRIMARY + "View Stats",
                        List.of(
                                BODY + "Open this player's stats",
                                "",
                                ACCENT + "Click to view"
                        )
                )
        );

        if (viewerMember == null || targetMember == null) {
            viewer.openInventory(inventory);
            return;
        }

        boolean viewingSelf = viewer.getUniqueId().equals(targetId);
        TeamRole viewerRole = viewerMember.role();
        TeamRole targetRole = targetMember.role();

        if (viewingSelf) {
            inventory.setItem(
                    22,
                    item(
                            Material.PAPER,
                            PRIMARY + "Your Team Profile",
                            List.of(
                                    BODY + "Role: " + SECONDARY + role,
                                    BODY + "Use Team Chat from the toolbar"
                            )
                    )
            );
            viewer.openInventory(inventory);
            return;
        }

        if (viewerRole == TeamRole.FOUNDER
                && targetRole != TeamRole.FOUNDER) {
            if (targetRole == TeamRole.MEMBER) {
                inventory.setItem(
                        10,
                        item(
                                Material.LIME_DYE,
                                "&aPromote",
                                List.of(
                                        BODY + "Promote this member",
                                        ACCENT + "Founder only"
                                )
                        )
                );
            }

            if (targetRole == TeamRole.ADMIN) {
                inventory.setItem(
                        11,
                        item(
                                Material.ORANGE_DYE,
                                SECONDARY + "Demote",
                                List.of(
                                        BODY + "Demote this admin",
                                        ACCENT + "Founder only"
                                )
                        )
                );
            }

            inventory.setItem(
                    15,
                    item(
                            Material.BARRIER,
                            "&cKick",
                            List.of(
                                    BODY + "Remove this player",
                                    ACCENT + "Requires confirmation"
                            )
                    )
            );
            inventory.setItem(
                    16,
                    item(
                            Material.REDSTONE_BLOCK,
                            "&cBan",
                            List.of(
                                    BODY + "Kick and block this player",
                                    BODY + "Duration: " + SECONDARY + "7 days"
                            )
                    )
            );
            inventory.setItem(
                    22,
                    item(
                            Material.NETHER_STAR,
                            PRIMARY + "Transfer Founder",
                            List.of(
                                    BODY + "Transfer team ownership",
                                    ACCENT + "Requires confirmation"
                            )
                    )
            );
        } else if (viewerRole == TeamRole.ADMIN
                && targetRole == TeamRole.MEMBER) {
            inventory.setItem(
                    15,
                    item(
                            Material.BARRIER,
                            "&cKick",
                            List.of(
                                    BODY + "Remove this player",
                                    ACCENT + "Admins can kick members"
                            )
                    )
            );
            inventory.setItem(
                    16,
                    item(
                            Material.REDSTONE_BLOCK,
                            "&cBan",
                            List.of(
                                    BODY + "Kick and block this player",
                                    BODY + "Duration: " + SECONDARY + "7 days"
                            )
                    )
            );
        }

        viewer.openInventory(inventory);
    }

    private static ItemStack playerHead(
            OfflinePlayer owner,
            String name,
            List<String> lore
    ) {
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
