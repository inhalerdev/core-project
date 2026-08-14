package net.mineacle.core.teams.gui;

import net.mineacle.core.common.gui.CenteredToolbar;
import net.mineacle.core.common.gui.GuiText;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.teams.model.TeamBanRecord;
import net.mineacle.core.teams.model.TeamRecord;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public final class TeamBansGui {

    public static final int SIZE = 54;
    private static final int CONTENT_SLOTS = 45;

    public static final int PREVIOUS_SLOT =
            CenteredToolbar.previousSlot(SIZE);
    public static final int BACK_SLOT = 48;
    public static final int REFRESH_SLOT =
            CenteredToolbar.centerSlot(SIZE);
    public static final int NEXT_SLOT =
            CenteredToolbar.nextSlot(SIZE);

    private static final String SECONDARY =
            "&#B078FF";
    private static final String ACCENT =
            "&#D0AFFF";
    private static final String BODY =
            "&#bbbbbb";

    private TeamBansGui() {
    }

    public static void open(
            Player player,
            TeamService teamService,
            int page
    ) {
        TeamRecord team =
                teamService.getTeamByPlayer(
                        player.getUniqueId()
                );

        if (team == null) {
            return;
        }

        List<TeamBanRecord> bans =
                teamService.activeBans(
                        team.teamId()
                );
        int maxPage =
                Math.max(
                        0,
                        (bans.size() - 1)
                                / CONTENT_SLOTS
                );
        int safePage =
                Math.clamp(
                        page,
                        0,
                        maxPage
                );

        BansHolder holder =
                new BansHolder(
                        team.teamId(),
                        safePage
                );
        Inventory inventory =
                Bukkit.createInventory(
                        holder,
                        SIZE,
                        GuiText.title(
                                "Team Bans (Page "
                                        + (safePage + 1)
                                        + "/"
                                        + (maxPage + 1)
                                        + ")"
                        )
                );
        holder.inventory = inventory;

        int start =
                safePage
                        * CONTENT_SLOTS;
        int end =
                Math.min(
                        bans.size(),
                        start
                                + CONTENT_SLOTS
                );

        for (int index = start;
             index < end;
             index++) {
            TeamBanRecord record =
                    bans.get(index);
            int slot =
                    index - start;
            OfflinePlayer banned =
                    Bukkit.getOfflinePlayer(
                            record.playerId()
                    );
            OfflinePlayer bannedBy =
                    Bukkit.getOfflinePlayer(
                            record.bannedBy()
                    );

            inventory.setItem(
                    slot,
                    playerHead(
                            banned,
                            "&c"
                                    + DisplayNames
                                    .displayName(
                                            banned
                                    ),
                            List.of(
                                    BODY
                                            + "Banned by: "
                                            + SECONDARY
                                            + DisplayNames
                                            .displayName(
                                                    bannedBy
                                            ),
                                    BODY
                                            + "Expires: "
                                            + ACCENT
                                            + remaining(
                                            record
                                    ),
                                    "",
                                    BODY
                                            + "Click to unban"
                            )
                    )
            );
            holder.banSlots.put(
                    slot,
                    record.playerId()
            );
        }

        if (bans.isEmpty()) {
            inventory.setItem(
                    22,
                    item(
                            Material.GRAY_DYE,
                            BODY
                                    + "No Active Bans",
                            List.of(
                                    BODY
                                            + "This team has no active bans"
                            )
                    )
            );
        }

        if (safePage > 0) {
            inventory.setItem(
                    PREVIOUS_SLOT,
                    navigation(
                            true,
                            safePage
                    )
            );
        }

        inventory.setItem(
                BACK_SLOT,
                item(
                        Material.ARROW,
                        SECONDARY + "Back",
                        List.of(
                                BODY
                                        + "Return to Team"
                        )
                )
        );
        inventory.setItem(
                REFRESH_SLOT,
                item(
                        Material.EMERALD,
                        SECONDARY
                                + "Refresh",
                        List.of(
                                BODY
                                        + "Reload active bans"
                        )
                )
        );

        if (safePage < maxPage) {
            inventory.setItem(
                    NEXT_SLOT,
                    navigation(
                            false,
                            safePage + 2
                    )
            );
        }

        player.openInventory(
                inventory
        );
    }

    private static String remaining(
            TeamBanRecord record
    ) {
        long millis =
                Math.max(
                        0L,
                        record.expiresAt()
                                - System.currentTimeMillis()
                );
        long days =
                TimeUnit.MILLISECONDS
                        .toDays(millis);

        if (days > 0L) {
            return days
                    + (days == 1L
                    ? " day"
                    : " days");
        }

        long hours =
                TimeUnit.MILLISECONDS
                        .toHours(millis);

        if (hours > 0L) {
            return hours
                    + (hours == 1L
                    ? " hour"
                    : " hours");
        }

        long minutes =
                Math.max(
                        1L,
                        TimeUnit.MILLISECONDS
                                .toMinutes(
                                        millis
                                )
                );

        return minutes
                + (minutes == 1L
                ? " minute"
                : " minutes");
    }

    private static ItemStack navigation(
            boolean previous,
            int page
    ) {
        return item(
                Material.ARROW,
                SECONDARY
                        + (
                        previous
                                ? "Previous Page"
                                : "Next Page"
                ),
                List.of(
                        BODY
                                + "Page "
                                + ACCENT
                                + page
                )
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

    public static final class BansHolder
            implements InventoryHolder {

        private final String teamId;
        private final int page;
        private final Map<Integer, UUID> banSlots =
                new HashMap<>();
        private Inventory inventory;

        private BansHolder(
                String teamId,
                int page
        ) {
            this.teamId = teamId;
            this.page = page;
        }

        public String teamId() {
            return teamId;
        }

        public int page() {
            return page;
        }

        public UUID bannedPlayerAt(
                int slot
        ) {
            return banSlots.get(slot);
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }
}
