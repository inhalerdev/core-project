package net.mineacle.core.homes.gui;

import net.mineacle.core.Core;
import net.kyori.adventure.text.Component;
import net.mineacle.core.common.gui.GuiText;
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

    private static final int FIRST_BED_SLOT = 12;
    private static final int FIRST_DYE_SLOT = 21;
    private static final int HOME_SLOT_COUNT = 5;

    private static final String ACCENT = "&#D0AFFF";
    private static final String SECONDARY = "&#B078FF";
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
                title(core)
        );
        UUID uuid = player.getUniqueId();
        boolean hasFreeCapacity = homeService.hasFreeHomeCapacity(player);

        for (int id = 1; id <= HOME_SLOT_COUNT; id++) {
            int bedSlot = bedSlot(id);
            int dyeSlot = dyeSlot(id);
            String displayName = homeService.getDisplayName(uuid, id);
            boolean exists = homeService.exists(uuid, id);

            if (exists) {
                inventory.setItem(
                        bedSlot,
                        item(
                                Material.PURPLE_BED,
                                SECONDARY + displayName,
                                List.of(
                                        BODY + "Click to teleport to this home"
                                )
                        )
                );
                inventory.setItem(
                        dyeSlot,
                        item(
                                Material.PURPLE_DYE,
                                SECONDARY + displayName,
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
                                SECONDARY + displayName,
                                List.of(
                                        BODY + "Click to save this location"
                                )
                        )
                );
                inventory.setItem(
                        dyeSlot,
                        item(
                                Material.GRAY_DYE,
                                SECONDARY + displayName,
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

        TeamHomeService teamHomeService = new TeamHomeService(core);
        TeamRecord team = teamService.getTeamByPlayer(
                player.getUniqueId()
        );

        if (team == null) {
            List<String> lore = List.of(
                    BODY + "You are not in a team",
                    BODY + "Type " + ACCENT + "/team create",
                    BODY + "to create a team"
            );
            inventory.setItem(
                    bannerSlot,
                    item(Material.LIGHT_GRAY_BANNER, SECONDARY + "No Team", lore)
            );
            inventory.setItem(
                    dyeSlot,
                    item(Material.GRAY_DYE, SECONDARY + "No Team", lore)
            );
            return;
        }

        boolean hasHome = teamHomeService.hasTeamHome(team.teamId());
        boolean isAdmin = teamService.isAdmin(player.getUniqueId());
        boolean isFounder = teamService.isFounder(player.getUniqueId());
        String teamDisplay = SECONDARY + team.name();

        if (!hasHome) {
            if (isAdmin) {
                List<String> lore = List.of(
                        BODY + "Team: " + teamDisplay,
                        BODY + "Click to set Team Home",
                        BODY + "to your current location"
                );
                inventory.setItem(
                        bannerSlot,
                        item(Material.WHITE_BANNER, SECONDARY + "Team Home", lore)
                );
                inventory.setItem(
                        dyeSlot,
                        item(Material.LIGHT_GRAY_DYE, SECONDARY + "Team Home", lore)
                );
            } else {
                List<String> lore = List.of(
                        BODY + "Team: " + teamDisplay,
                        BODY + "Your team does not have a home yet",
                        BODY + "Ask a team admin to set Team Home"
                );
                inventory.setItem(
                        bannerSlot,
                        item(Material.LIGHT_GRAY_BANNER, SECONDARY + "Team Home", lore)
                );
                inventory.setItem(
                        dyeSlot,
                        item(Material.GRAY_DYE, SECONDARY + "Team Home", lore)
                );
            }
            return;
        }

        inventory.setItem(
                bannerSlot,
                item(
                        Material.PURPLE_BANNER,
                        SECONDARY + "Team Home",
                        List.of(
                                BODY + "Team: " + teamDisplay,
                                BODY + "Click to teleport to Team Home"
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
                            SECONDARY + "Team Home",
                            List.of(
                                    BODY + "Your team home is set",
                                    BODY + "Only the founder can delete it"
                            )
                    )
            );
        }
    }

    public static int homeIdForBedSlot(int slot) {
        return homeIdForSlot(slot, FIRST_BED_SLOT);
    }

    public static int homeIdForDyeSlot(int slot) {
        return homeIdForSlot(slot, FIRST_DYE_SLOT);
    }

    private static int homeIdForSlot(int slot, int firstSlot) {
        int homeId = slot - firstSlot + 1;
        return homeId >= 1 && homeId <= HOME_SLOT_COUNT ? homeId : 0;
    }

    private static int bedSlot(int homeId) {
        return FIRST_BED_SLOT + homeId - 1;
    }

    private static int dyeSlot(int homeId) {
        return FIRST_DYE_SLOT + homeId - 1;
    }

    private static Component title(Core core) {
        String plain = GuiText.plain(core.getMessage("homes.gui.title"));
        return GuiText.title(plain.isBlank() ? "Homes" : plain);
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
        item.setItemMeta(meta);
        return item;
    }
}
