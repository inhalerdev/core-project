package net.mineacle.core.homes.gui;

import net.mineacle.core.Core;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.homes.service.HomeService;
import net.mineacle.core.teams.TeamsModule;
import net.mineacle.core.teams.model.TeamRecord;
import net.mineacle.core.teams.service.TeamHomeService;
import net.mineacle.core.teams.service.TeamService;
import org.bukkit.Bukkit;
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

    private static final String PRIMARY = "&#8436FE";
    private static final String SECONDARY = "&#B078FF";
    private static final String ACCENT = "&#D0AFFF";
    private static final String BODY = "&#bbbbbb";

    private HomesMainGui() {
    }

    public static void open(
            Core core,
            Player player,
            HomeService homeService
    ) {
        Inventory inventory = Bukkit.createInventory(
                null,
                9 * 4,
                title(core, "homes.gui.title")
        );
        UUID uuid = player.getUniqueId();
        boolean hasFreeCapacity = homeService.hasFreeHomeCapacity(player);

        for (int index = 0; index < 5; index++) {
            int id = index + 1;
            int bedSlot = BED_SLOTS[index];
            int dyeSlot = DYE_SLOTS[index];
            String displayName = homeService.getDisplayName(uuid, id);
            boolean exists = homeService.exists(uuid, id);

            if (exists) {
                inventory.setItem(
                        bedSlot,
                        item(
                                Material.PURPLE_BED,
                                PRIMARY + displayName,
                                List.of(
                                        BODY + "Click to " + ACCENT
                                                + "teleport " + BODY
                                                + "to this home"
                                )
                        )
                );
                inventory.setItem(
                        dyeSlot,
                        item(
                                Material.PURPLE_DYE,
                                PRIMARY + displayName,
                                List.of(
                                        BODY + "Click to &cdelete "
                                                + BODY + "this home"
                                )
                        )
                );
                continue;
            }

            if (hasFreeCapacity) {
                inventory.setItem(
                        bedSlot,
                        item(
                                Material.WHITE_BED,
                                PRIMARY + displayName,
                                List.of(
                                        BODY + "Click to save this location"
                                )
                        )
                );
                inventory.setItem(
                        dyeSlot,
                        item(
                                Material.GRAY_DYE,
                                PRIMARY + displayName,
                                List.of(
                                        BODY + "Click to save this location"
                                )
                        )
                );
            } else {
                inventory.setItem(
                        bedSlot,
                        item(
                                Material.LIGHT_GRAY_BED,
                                "&cHome Locked",
                                List.of(
                                        SECONDARY + "Mineacle+ "
                                                + BODY
                                                + "required to use this feature"
                                )
                        )
                );
                inventory.setItem(
                        dyeSlot,
                        item(
                                Material.GRAY_DYE,
                                "&cHome Locked",
                                List.of(
                                        SECONDARY + "Mineacle+ "
                                                + BODY
                                                + "required to use this feature"
                                )
                        )
                );
            }
        }

        setupTeamHome(core, player, inventory);
        player.openInventory(inventory);
    }

    private static void setupTeamHome(
            Core core,
            Player player,
            Inventory inventory
    ) {
        int bannerSlot = core.getConfig().getInt(
                "homes.team-home.banner-slot",
                10
        );
        int dyeSlot = core.getConfig().getInt(
                "homes.team-home.dye-slot",
                19
        );

        if (!core.getConfig().getBoolean(
                "homes.team-home.enabled",
                true
        )) {
            return;
        }

        TeamService teamService = TeamsModule.teamService();

        if (teamService == null) {
            inventory.setItem(
                    bannerSlot,
                    item(
                            Material.LIGHT_GRAY_BANNER,
                            "&cTeam Home Unavailable",
                            List.of(BODY + "Teams are temporarily unavailable")
                    )
            );
            inventory.setItem(
                    dyeSlot,
                    item(
                            Material.GRAY_DYE,
                            "&cTeam Home Unavailable",
                            List.of(BODY + "Teams are temporarily unavailable")
                    )
            );
            return;
        }

        TeamHomeService teamHomeService = new TeamHomeService(
                core,
                teamService
        );
        TeamRecord team = teamService.getTeamByPlayer(
                player.getUniqueId()
        );

        if (team == null) {
            List<String> lore = List.of(
                    BODY + "You are not in a team",
                    BODY + "Type " + PRIMARY + "/team create",
                    ACCENT + "to create a team"
            );
            inventory.setItem(
                    bannerSlot,
                    item(Material.LIGHT_GRAY_BANNER, PRIMARY + "No Team", lore)
            );
            inventory.setItem(
                    dyeSlot,
                    item(Material.GRAY_DYE, PRIMARY + "No Team", lore)
            );
            return;
        }

        boolean hasHome = teamHomeService.hasTeamHome(team.teamId());
        boolean isAdmin = teamService.isAdmin(player.getUniqueId());
        boolean isFounder = teamService.isFounder(player.getUniqueId());
        String teamDisplay = PRIMARY + team.name();

        if (!hasHome) {
            if (isAdmin) {
                List<String> lore = List.of(
                        BODY + "Team: " + teamDisplay,
                        BODY + "Click to set " + ACCENT + "Team Home",
                        BODY + "to your current location"
                );
                inventory.setItem(
                        bannerSlot,
                        item(Material.WHITE_BANNER, PRIMARY + "Team Home", lore)
                );
                inventory.setItem(
                        dyeSlot,
                        item(Material.LIGHT_GRAY_DYE, PRIMARY + "Team Home", lore)
                );
            } else {
                List<String> lore = List.of(
                        BODY + "Team: " + teamDisplay,
                        BODY + "Your team does not have a home yet",
                        BODY + "Ask a " + ACCENT + "team admin "
                                + BODY + "to set Team Home"
                );
                inventory.setItem(
                        bannerSlot,
                        item(Material.LIGHT_GRAY_BANNER, PRIMARY + "Team Home", lore)
                );
                inventory.setItem(
                        dyeSlot,
                        item(Material.GRAY_DYE, PRIMARY + "Team Home", lore)
                );
            }
            return;
        }

        inventory.setItem(
                bannerSlot,
                item(
                        Material.PURPLE_BANNER,
                        PRIMARY + "Team Home",
                        List.of(
                                BODY + "Team: " + teamDisplay,
                                BODY + "Click to " + ACCENT + "teleport "
                                        + BODY + "to Team Home"
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
                                    BODY + "Team: " + teamDisplay,
                                    BODY + "Click to &cdelete " + BODY + "Team Home"
                            )
                    )
            );
        } else {
            inventory.setItem(
                    dyeSlot,
                    item(
                            Material.GRAY_DYE,
                            PRIMARY + "Team Home",
                            List.of(
                                    BODY + "Your team home is set",
                                    BODY + "Only the founder can delete it"
                            )
                    )
            );
        }
    }

    private static String title(Core core, String path) {
        String plain = TextColor.strip(core.getMessage(path));
        return TextColor.color(
                PRIMARY + (plain == null || plain.isBlank() ? "Homes" : plain)
        );
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
        item.setItemMeta(meta);
        return item;
    }
}
