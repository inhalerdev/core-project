package net.mineacle.core.homes.gui;

import net.mineacle.core.Core;
import net.mineacle.core.homes.service.HomeService;
import net.mineacle.core.teams.model.TeamRecord;
import net.mineacle.core.teams.service.TeamHomeService;
import net.mineacle.core.teams.service.TeamService;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;
import java.util.UUID;

public final class HomesMainGui {

    public static final int[] BED_SLOTS = {12, 13, 14, 15, 16};
    public static final int[] DYE_SLOTS = {21, 22, 23, 24, 25};

    private HomesMainGui() {
    }

    public static void open(Core core, Player player, HomeService homeService) {
        String title = ChatColor.translateAlternateColorCodes('&', core.getMessage("homes.gui.title"));
        Inventory inventory = Bukkit.createInventory(null, 9 * 4, title);

        UUID uuid = player.getUniqueId();
        boolean hasFreeCapacity = homeService.hasFreeHomeCapacity(player);

        for (int i = 0; i < 5; i++) {
            int id = i + 1;
            int bedSlot = BED_SLOTS[i];
            int dyeSlot = DYE_SLOTS[i];
            String displayName = homeService.getDisplayName(uuid, id);
            boolean exists = homeService.exists(uuid, id);

            if (exists) {
                inventory.setItem(
                        bedSlot,
                        item(Material.PURPLE_BED, "&d" + displayName, List.of("&fClick to &dteleport &fto this home"))
                );
                inventory.setItem(
                        dyeSlot,
                        item(Material.PURPLE_DYE, "&d" + displayName, List.of("&fClick to &cdelete &fthis home"))
                );
                continue;
            }

            if (hasFreeCapacity) {
                inventory.setItem(
                        bedSlot,
                        item(Material.WHITE_BED, "&f" + displayName, List.of("&7Click to save this location"))
                );
                inventory.setItem(
                        dyeSlot,
                        item(Material.GRAY_DYE, "&f" + displayName, List.of("&7Click to save this location"))
                );
            } else {
                inventory.setItem(
                        bedSlot,
                        item(Material.LIGHT_GRAY_BED, "&cHome Locked", List.of("&dMineacle+&f required to use this feature."))
                );
                inventory.setItem(
                        dyeSlot,
                        item(Material.GRAY_DYE, "&cHome Locked", List.of("&dMineacle+&f required to use this feature."))
                );
            }
        }

        setupTeamHome(core, player, inventory);
        player.openInventory(inventory);
    }

    private static void setupTeamHome(Core core, Player player, Inventory inventory) {
        int bannerSlot = core.getConfig().getInt("homes.team-home.banner-slot", 10);
        int dyeSlot = core.getConfig().getInt("homes.team-home.dye-slot", 19);

        if (!core.getConfig().getBoolean("homes.team-home.enabled", true)) {
            return;
        }

        TeamService teamService = new TeamService(core);
        TeamHomeService teamHomeService = new TeamHomeService(core, teamService);
        TeamRecord team = teamService.getTeamByPlayer(player.getUniqueId());

        if (team == null) {
            inventory.setItem(
                    bannerSlot,
                    item(
                            Material.LIGHT_GRAY_BANNER,
                            "&7No Team",
                            List.of(
                                    "&fYou are not in a team",
                                    "&7Type &d/team create <name>",
                                    "&7to create a team."
                            )
                    )
            );

            inventory.setItem(
                    dyeSlot,
                    item(
                            Material.GRAY_DYE,
                            "&7No Team",
                            List.of(
                                    "&fYou are not in a team",
                                    "&7Type &d/team create <name>",
                                    "&7to create a team."
                            )
                    )
            );
            return;
        }

        boolean hasHome = teamHomeService.hasTeamHome(team.teamId());
        boolean isAdmin = teamService.isAdmin(player.getUniqueId());
        boolean isFounder = teamService.isFounder(player.getUniqueId());
        String teamDisplay = teamService.formatTeamName(team);

        if (!hasHome) {
            if (isAdmin) {
                inventory.setItem(
                        bannerSlot,
                        item(
                                Material.WHITE_BANNER,
                                "&fTeam Home",
                                List.of(
                                        "&7Team: " + teamDisplay,
                                        "&7Click to set &dTeam Home",
                                        "&7to your current location."
                                )
                        )
                );

                inventory.setItem(
                        dyeSlot,
                        item(
                                Material.LIGHT_GRAY_DYE,
                                "&fTeam Home",
                                List.of(
                                        "&7Team: " + teamDisplay,
                                        "&7Click to set &dTeam Home",
                                        "&7to your current location."
                                )
                        )
                );
            } else {
                inventory.setItem(
                        bannerSlot,
                        item(
                                Material.LIGHT_GRAY_BANNER,
                                "&7Team Home",
                                List.of(
                                        "&7Team: " + teamDisplay,
                                        "&fYour team does not have a home yet",
                                        "&7Ask your &dteam owner &7to set Team Home"
                                )
                        )
                );

                inventory.setItem(
                        dyeSlot,
                        item(
                                Material.GRAY_DYE,
                                "&7Team Home",
                                List.of(
                                        "&7Team: " + teamDisplay,
                                        "&fYour team does not have a home yet",
                                        "&7Ask your &dteam owner &7to set Team Home"
                                )
                        )
                );
            }
            return;
        }

        inventory.setItem(
                bannerSlot,
                item(
                        Material.PURPLE_BANNER,
                        teamDisplay + " &dTeam Home",
                        List.of(
                                "&fClick to teleport to &dTeam Home",
                                "&7Banner: &f" + "Purple"
                        )
                )
        );

        if (isFounder) {
            inventory.setItem(
                    dyeSlot,
                    item(
                            Material.PURPLE_DYE,
                            "&cDelete Team Home",
                            List.of(
                                    "&fClick to delete &dTeam Home",
                                    "&7Team: " + teamDisplay
                            )
                    )
            );
        } else {
            inventory.setItem(
                    dyeSlot,
                    item(
                            Material.GRAY_DYE,
                            "&7Team Home",
                            List.of(
                                    "&fYour team home is set.",
                                    "&7Only the founder can delete it."
                            )
                    )
            );
        }
    }

    private static ItemStack item(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        if (meta == null) {
            return item;
        }

        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
        meta.setLore(lore.stream().map(line -> ChatColor.translateAlternateColorCodes('&', line)).toList());

        item.setItemMeta(meta);
        return item;
    }
}