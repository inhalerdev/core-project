package net.mineacle.core.homes.gui;

import net.kyori.adventure.text.Component;
import net.mineacle.core.Core;
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

    private static final String PRIMARY =
            "&#8436FE";
    private static final String SECONDARY =
            "&#B078FF";
    private static final String ACCENT =
            "&#D0AFFF";
    private static final String BODY =
            "&#bbbbbb";

    private HomesMainGui() {
    }

    public static void open(
            Core core,
            Player player,
            HomeService homeService
    ) {
        Inventory inventory =
                Bukkit.createInventory(
                        null,
                        36,
                        title(core)
                );
        UUID playerId =
                player.getUniqueId();

        for (int id = 1;
             id <= HOME_SLOT_COUNT;
             id++) {
            int bedSlot =
                    bedSlot(id);
            int dyeSlot =
                    dyeSlot(id);

            if (homeService.slotLocked(
                    player,
                    id
            )) {
                inventory.setItem(
                        bedSlot,
                        lockedItem(
                                Material.LIGHT_GRAY_BED,
                                id
                        )
                );
                inventory.setItem(
                        dyeSlot,
                        lockedItem(
                                Material.GRAY_DYE,
                                id
                        )
                );
                continue;
            }

            String displayName =
                    homeService.getDisplayName(
                            playerId,
                            id
                    );

            if (homeService.exists(
                    playerId,
                    id
            )) {
                inventory.setItem(
                        bedSlot,
                        item(
                                Material.PURPLE_BED,
                                SECONDARY
                                        + displayName,
                                List.of(
                                        BODY
                                                + "Click to teleport"
                                )
                        )
                );
                inventory.setItem(
                        dyeSlot,
                        item(
                                Material.PURPLE_DYE,
                                SECONDARY
                                        + displayName,
                                List.of(
                                        BODY
                                                + "Click to "
                                                + "&cdelete"
                                )
                        )
                );
                continue;
            }

            inventory.setItem(
                    bedSlot,
                    item(
                            Material.WHITE_BED,
                            BODY + displayName,
                            List.of(
                                    BODY
                                            + "Click to set here"
                            )
                    )
            );
            inventory.setItem(
                    dyeSlot,
                    item(
                            Material.LIGHT_GRAY_DYE,
                            BODY + displayName,
                            List.of(
                                    BODY
                                            + "Click to set here"
                            )
                    )
            );
        }

        setupTeamHome(
                core,
                player,
                inventory
        );
        player.openInventory(
                inventory
        );
    }

    private static ItemStack lockedItem(
            Material material,
            int homeId
    ) {
        return item(
                material,
                BODY
                        + "Home "
                        + homeId,
                List.of(
                        BODY
                                + "Locked",
                        BODY
                                + "Requires "
                                + PRIMARY
                                + "Mineacle+"
                )
        );
    }

    private static void setupTeamHome(
            Core core,
            Player player,
            Inventory inventory
    ) {
        int bannerSlot =
                core.getConfig()
                        .getInt(
                                "homes.team-home.banner-slot",
                                10
                        );
        int dyeSlot =
                core.getConfig()
                        .getInt(
                                "homes.team-home.dye-slot",
                                19
                        );

        if (!core.getConfig()
                .getBoolean(
                        "homes.team-home.enabled",
                        true
                )) {
            return;
        }

        TeamService teamService =
                TeamsModule.teamService();

        if (teamService == null) {
            List<String> unavailable =
                    List.of(
                            "&cTeams are temporarily unavailable"
                    );

            inventory.setItem(
                    bannerSlot,
                    item(
                            Material.LIGHT_GRAY_BANNER,
                            BODY
                                    + "Team Home",
                            unavailable
                    )
            );
            inventory.setItem(
                    dyeSlot,
                    item(
                            Material.GRAY_DYE,
                            BODY
                                    + "Team Home",
                            unavailable
                    )
            );
            return;
        }

        TeamRecord team =
                teamService.getTeamByPlayer(
                        player.getUniqueId()
                );

        if (team == null) {
            List<String> noTeam =
                    List.of(
                            BODY
                                    + "No team",
                            BODY
                                    + "Click to autofill "
                                    + ACCENT
                                    + "/team create"
                    );

            inventory.setItem(
                    bannerSlot,
                    item(
                            Material.LIGHT_GRAY_BANNER,
                            BODY
                                    + "Team Home",
                            noTeam
                    )
            );
            inventory.setItem(
                    dyeSlot,
                    item(
                            Material.GRAY_DYE,
                            BODY
                                    + "No Team",
                            noTeam
                    )
            );
            return;
        }

        TeamHomeService teamHomeService =
                new TeamHomeService(core);
        boolean hasHome =
                teamHomeService.hasTeamHome(
                        team.teamId()
                );
        boolean founder =
                teamService.canManageTeamHome(
                        player.getUniqueId()
                );

        if (!hasHome) {
            if (founder) {
                List<String> setLore =
                        List.of(
                                BODY
                                        + "Team: "
                                        + SECONDARY
                                        + team.name(),
                                BODY
                                        + "Status: "
                                        + BODY
                                        + "Not Set",
                                "",
                                BODY
                                        + "Click to set here"
                        );

                inventory.setItem(
                        bannerSlot,
                        item(
                                Material.WHITE_BANNER,
                                SECONDARY
                                        + "Team Home",
                                setLore
                        )
                );
                inventory.setItem(
                        dyeSlot,
                        item(
                                Material.LIGHT_GRAY_DYE,
                                SECONDARY
                                        + "Team Home",
                                setLore
                        )
                );
                return;
            }

            List<String> waitingLore =
                    List.of(
                            BODY
                                    + "Team: "
                                    + SECONDARY
                                    + team.name(),
                            BODY
                                    + "Status: "
                                    + BODY
                                    + "Not Set",
                            "",
                            BODY
                                    + "Founder can set Team Home"
                    );

            inventory.setItem(
                    bannerSlot,
                    item(
                            Material.LIGHT_GRAY_BANNER,
                            BODY
                                    + "Team Home",
                            waitingLore
                    )
            );
            inventory.setItem(
                    dyeSlot,
                    item(
                            Material.GRAY_DYE,
                            BODY
                                    + "Team Home",
                            waitingLore
                    )
            );
            return;
        }

        inventory.setItem(
                bannerSlot,
                item(
                        Material.PURPLE_BANNER,
                        SECONDARY
                                + "Team Home",
                        List.of(
                                BODY
                                        + "Team: "
                                        + SECONDARY
                                        + team.name(),
                                BODY
                                        + "Status: "
                                        + "&aSet",
                                "",
                                BODY
                                        + "Click to teleport"
                        )
                )
        );

        if (founder) {
            inventory.setItem(
                    dyeSlot,
                    item(
                            Material.RED_DYE,
                            "&cDelete Team Home",
                            List.of(
                                    BODY
                                            + "Team: "
                                            + SECONDARY
                                            + team.name(),
                                    BODY
                                            + "Requires confirmation"
                            )
                    )
            );
            return;
        }

        inventory.setItem(
                dyeSlot,
                item(
                        Material.GRAY_DYE,
                        BODY
                                + "Team Home",
                        List.of(
                                BODY
                                        + "Team: "
                                        + SECONDARY
                                        + team.name(),
                                BODY
                                        + "Founder manages this home"
                        )
                )
        );
    }

    public static int homeIdForBedSlot(
            int slot
    ) {
        return homeIdForSlot(
                slot,
                FIRST_BED_SLOT
        );
    }

    public static int homeIdForDyeSlot(
            int slot
    ) {
        return homeIdForSlot(
                slot,
                FIRST_DYE_SLOT
        );
    }

    private static int homeIdForSlot(
            int slot,
            int firstSlot
    ) {
        int homeId =
                slot - firstSlot + 1;

        return homeId >= 1
                && homeId
                <= HOME_SLOT_COUNT
                ? homeId
                : 0;
    }

    private static int bedSlot(
            int homeId
    ) {
        return FIRST_BED_SLOT
                + homeId - 1;
    }

    private static int dyeSlot(
            int homeId
    ) {
        return FIRST_DYE_SLOT
                + homeId - 1;
    }

    private static Component title(
            Core core
    ) {
        String plain =
                GuiText.plain(
                        core.getMessage(
                                "homes.gui.title"
                        )
                );

        return GuiText.title(
                plain.isBlank()
                        ? "Player Homes"
                        : plain
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
        item.setItemMeta(meta);
        return item;
    }
}
