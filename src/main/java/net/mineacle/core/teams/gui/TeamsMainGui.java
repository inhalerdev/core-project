package net.mineacle.core.teams.gui;

import net.mineacle.core.Core;
import net.mineacle.core.common.gui.GuiText;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.economy.EconomyModule;
import net.mineacle.core.economy.service.EconomyService;
import net.mineacle.core.teams.model.TeamMemberRecord;
import net.mineacle.core.teams.model.TeamRecord;
import net.mineacle.core.teams.model.TeamRole;
import net.mineacle.core.teams.service.TeamHomeService;
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
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class TeamsMainGui {

    public static final int SIZE = 54;
    public static final int CONTENT_SLOTS = 45;

    /*
     * Bottom-row grouping:
     *
     * 45  Team Bans    management-only, far left
     * 46  empty        visual separator
     * 47  Sort         shared utility position
     * 48  Team Home    primary team control
     * 49  Team Info    primary team control
     * 50  empty        visual separator
     * 51  Team Chat    optional setting
     * 52  Team PvP     optional setting
     * 53  empty
     */
    public static final int BANS_SLOT = 45;
    public static final int SORT_SLOT = 47;
    public static final int TEAM_HOME_SLOT = 48;
    public static final int TEAM_INFO_SLOT = 49;
    public static final int TEAM_CHAT_SLOT = 51;
    public static final int TEAM_PVP_SLOT = 52;

    private static final String SECONDARY =
            "&#B078FF";
    private static final String ACCENT =
            "&#D0AFFF";
    private static final String BODY =
            "&#bbbbbb";
    private static final String MONEY =
            "&#11fc7b";

    private static final Map<UUID, TeamSortMode> SORT_MODES =
            new HashMap<>();

    private TeamsMainGui() {
    }

    public static void open(
            Core core,
            Player player,
            TeamService teamService,
            TeamInviteService inviteService
    ) {
        TeamRecord team =
                teamService.getTeamByPlayer(
                        player.getUniqueId()
                );

        if (team == null) {
            TeamStartGui.open(
                    player,
                    inviteService
            );
            return;
        }

        List<UUID> members =
                sortedMembers(
                        player,
                        team.teamId(),
                        teamService
                );
        int memberCount =
                members.size();
        TeamMemberRecord viewerMember =
                teamService.getMember(
                        player.getUniqueId()
                );
        TeamRole viewerRole =
                viewerMember == null
                        ? TeamRole.MEMBER
                        : viewerMember.role();

        MainHolder holder =
                new MainHolder(
                        team.teamId()
                );
        Inventory inventory =
                Bukkit.createInventory(
                        holder,
                        SIZE,
                        GuiText.title(
                                team.name()
                                        + " ("
                                        + memberCount
                                        + "/"
                                        + teamService
                                        .maxMembers()
                                        + ")"
                        )
                );
        holder.inventory = inventory;

        int slot = 0;

        for (UUID memberId : members) {
            if (slot >= CONTENT_SLOTS) {
                break;
            }

            TeamMemberRecord member =
                    teamService.getMember(
                            memberId
                    );

            if (member == null) {
                continue;
            }

            OfflinePlayer offlinePlayer =
                    Bukkit.getOfflinePlayer(
                            memberId
                    );
            Player onlinePlayer =
                    Bukkit.getPlayer(
                            memberId
                    );
            boolean online =
                    onlinePlayer != null
                            && onlinePlayer.isOnline();
            String displayName =
                    DisplayNames.displayName(
                            offlinePlayer
                    );

            inventory.setItem(
                    slot,
                    playerHead(
                            offlinePlayer,
                            (online
                                    ? "&a"
                                    : BODY)
                                    + displayName,
                            List.of(
                                    BODY
                                            + "Balance: "
                                            + MONEY
                                            + balance(
                                            offlinePlayer
                                    ),
                                    BODY
                                            + "Role: "
                                            + member.role()
                                            .color()
                                            + member.role()
                                            .displayName(),
                                    "",
                                    BODY
                                            + "Click to view"
                            )
                    )
            );
            holder.memberSlots.put(
                    slot,
                    memberId
            );
            slot++;
        }

        if (teamService.canInvite(
                player.getUniqueId()
        )
                && memberCount
                < teamService.maxMembers()
                && slot < CONTENT_SLOTS) {
            holder.inviteSlot = slot;
            inventory.setItem(
                    slot,
                    item(
                            Material
                                    .LIME_STAINED_GLASS_PANE,
                            SECONDARY
                                    + "Invite Player",
                            List.of(
                                    BODY + "Click to invite a player",
                                    BODY + "Autofills "
                                            + ACCENT
                                            + "/team invite"
                            )
                    )
            );
        }

        TeamHomeService teamHomeService =
                new TeamHomeService(core);
        boolean hasTeamHome =
                teamHomeService.hasTeamHome(
                        team.teamId()
                );
        boolean teamChatEnabled =
                teamService
                        .isTeamChatEnabled(
                                player.getUniqueId()
                        );

        if (teamService.canManageBans(
                player.getUniqueId()
        )) {
            inventory.setItem(
                    BANS_SLOT,
                    bansItem()
            );
        }

        inventory.setItem(
                SORT_SLOT,
                sortItem(
                        currentSort(player)
                )
        );
        inventory.setItem(
                TEAM_HOME_SLOT,
                teamHomeItem(
                        hasTeamHome,
                        teamService
                                .canManageTeamHome(
                                        player.getUniqueId()
                                )
                )
        );
        inventory.setItem(
                TEAM_INFO_SLOT,
                teamInfoItem(
                        team,
                        memberCount,
                        teamService.maxMembers(),
                        viewerRole
                )
        );
        inventory.setItem(
                TEAM_CHAT_SLOT,
                teamChatItem(
                        teamChatEnabled
                )
        );
        inventory.setItem(
                TEAM_PVP_SLOT,
                pvpItem(
                        team.friendlyFire(),
                        teamService
                                .canTogglePvp(
                                        player.getUniqueId()
                                )
                )
        );

        player.openInventory(
                inventory
        );
    }

    public static List<UUID> sortedMembers(
            Player viewer,
            String teamId,
            TeamService teamService
    ) {
        List<UUID> members =
                new ArrayList<>(
                        teamService
                                .getTeamMembers(
                                        teamId
                                )
                );
        TeamSortMode mode =
                currentSort(viewer);

        Comparator<UUID> nameComparator =
                Comparator.comparing(
                        id ->
                                DisplayNames
                                        .displayName(
                                                Bukkit
                                                        .getOfflinePlayer(
                                                                id
                                                        )
                                        ),
                        String.CASE_INSENSITIVE_ORDER
                );

        members.sort(
                switch (mode) {
                    case RANK ->
                            Comparator
                                    .comparingInt(
                                            (UUID id) -> {
                                                TeamMemberRecord member =
                                                        teamService
                                                                .getMember(
                                                                        id
                                                                );

                                                return member
                                                        == null
                                                        ? Integer
                                                        .MAX_VALUE
                                                        : member
                                                        .role()
                                                        .priority();
                                            }
                                    )
                                    .thenComparing(
                                            id ->
                                                    Bukkit
                                                            .getPlayer(
                                                                    id
                                                            )
                                                            == null
                                    )
                                    .thenComparing(
                                            nameComparator
                                    );
                    case ONLINE ->
                            Comparator
                                    .comparing(
                                            (UUID id) ->
                                                    Bukkit
                                                            .getPlayer(
                                                                    id
                                                            )
                                                            == null
                                    )
                                    .thenComparingInt(
                                            id -> {
                                                TeamMemberRecord member =
                                                        teamService
                                                                .getMember(
                                                                        id
                                                                );

                                                return member
                                                        == null
                                                        ? Integer
                                                        .MAX_VALUE
                                                        : member
                                                        .role()
                                                        .priority();
                                            }
                                    )
                                    .thenComparing(
                                            nameComparator
                                    );
                    case NAME ->
                            nameComparator;
                }
        );

        return List.copyOf(
                members
        );
    }

    public static TeamSortMode currentSort(
            Player player
    ) {
        return SORT_MODES.getOrDefault(
                player.getUniqueId(),
                TeamSortMode.RANK
        );
    }

    public static void cycleSort(
            Player player,
            boolean previous
    ) {
        TeamSortMode current =
                currentSort(player);

        SORT_MODES.put(
                player.getUniqueId(),
                previous
                        ? current.previous()
                        : current.next()
        );
    }

    public static void clearPlayerState(
            UUID playerId
    ) {
        if (playerId != null) {
            SORT_MODES.remove(
                    playerId
            );
        }
    }

    public static void clearAllState() {
        SORT_MODES.clear();
    }

    private static ItemStack bansItem() {
        return item(
                Material.IRON_BARS,
                SECONDARY + "Team Bans",
                List.of(
                        BODY + "Click to manage bans"
                )
        );
    }

    private static ItemStack teamHomeItem(
            boolean hasHome,
            boolean canManageHome
    ) {
        if (hasHome) {
            return item(
                    Material.PURPLE_BANNER,
                    SECONDARY + "Team Home",
                    canManageHome
                            ? List.of(
                            "&aReady",
                            BODY + "Left-click to teleport",
                            BODY + "Right-click to "
                                    + "&cdelete"
                    )
                            : List.of(
                            "&aReady",
                            BODY + "Click to teleport"
                    )
            );
        }

        if (canManageHome) {
            return item(
                    Material.WHITE_BANNER,
                    SECONDARY + "Team Home",
                    List.of(
                            BODY + "Not set",
                            BODY + "Click to set here"
                    )
            );
        }

        return item(
                Material.LIGHT_GRAY_BANNER,
                BODY + "Team Home",
                List.of(
                        BODY + "Not set",
                        BODY + "Waiting for Founder"
                )
        );
    }

    private static ItemStack teamChatItem(
            boolean enabled
    ) {
        return item(
                enabled
                        ? Material.LIME_DYE
                        : Material.GRAY_DYE,
                SECONDARY + "Team Chat",
                List.of(
                        enabled
                                ? "&aEnabled"
                                : BODY + "Disabled",
                        BODY + "Click to toggle"
                )
        );
    }

    private static ItemStack teamInfoItem(
            TeamRecord team,
            int memberCount,
            int maxMembers,
            TeamRole viewerRole
    ) {
        return item(
                Material.NETHER_STAR,
                SECONDARY + "Team Info",
                List.of(
                        BODY + "Team: "
                                + SECONDARY
                                + team.name(),
                        BODY + "Founder: "
                                + SECONDARY
                                + DisplayNames.displayName(
                                Bukkit.getOfflinePlayer(
                                        team.founder()
                                )
                        ),
                        BODY + "Members: "
                                + ACCENT
                                + memberCount
                                + BODY
                                + "/"
                                + ACCENT
                                + maxMembers,
                        BODY + "Your Role: "
                                + viewerRole.color()
                                + viewerRole.displayName()
                )
        );
    }

    private static ItemStack sortItem(
            TeamSortMode current
    ) {
        List<String> lore =
                new ArrayList<>();

        lore.add(
                BODY + "Current: "
                        + ACCENT
                        + current.displayName()
        );
        lore.add("");

        for (TeamSortMode mode :
                TeamSortMode.values()) {
            lore.add(
                    (mode == current
                            ? ACCENT
                            : BODY)
                            + mode.displayName()
            );
        }

        lore.add("");
        lore.add(
                BODY + "Left-click: Next"
        );
        lore.add(
                BODY + "Right-click: Previous"
        );

        return item(
                Material.ANVIL,
                SECONDARY + "Sort",
                lore
        );
    }

    private static ItemStack pvpItem(
            boolean friendlyFire,
            boolean canToggle
    ) {
        List<String> lore =
                new ArrayList<>();

        lore.add(
                friendlyFire
                        ? "&cEnabled"
                        : "&aDisabled"
        );
        lore.add(
                friendlyFire
                        ? BODY
                        + "Teammates can damage each other"
                        : BODY
                        + "Teammates are protected"
        );

        if (canToggle) {
            lore.add(
                    BODY + "Click to toggle"
            );
        } else {
            lore.add(
                    BODY + "Founder / MVP only"
            );
        }

        return item(
                Material.DIAMOND_SWORD,
                SECONDARY + "Team PvP",
                lore
        );
    }

    private static String balance(
            OfflinePlayer player
    ) {
        EconomyService economyService =
                EconomyModule.economyService();

        if (economyService == null
                || player == null) {
            return "$0";
        }

        return economyService.format(
                economyService.getBalanceCents(
                        player.getUniqueId()
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

    public static final class MainHolder
            implements InventoryHolder {

        private final String teamId;
        private final Map<Integer, UUID> memberSlots =
                new HashMap<>();
        private int inviteSlot = -1;
        private Inventory inventory;

        private MainHolder(
                String teamId
        ) {
            this.teamId = teamId;
        }

        public String teamId() {
            return teamId;
        }

        public UUID memberAt(
                int slot
        ) {
            return memberSlots.get(slot);
        }

        public int inviteSlot() {
            return inviteSlot;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }
}
