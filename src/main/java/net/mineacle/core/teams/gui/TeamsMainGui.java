package net.mineacle.core.teams.gui;

import net.mineacle.core.Core;
import net.mineacle.core.common.gui.GuiText;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.stats.VaultMoneyHook;
import net.mineacle.core.teams.model.TeamMemberRecord;
import net.mineacle.core.teams.model.TeamRecord;
import net.mineacle.core.teams.service.TeamHomeService;
import net.mineacle.core.teams.service.TeamInviteService;
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class TeamsMainGui {

    public static final int TEAM_HOME_SLOT = 47;
    public static final int TEAM_CHAT_SLOT = 48;
    public static final int TEAM_INFO_SLOT = 49;
    public static final int SORT_SLOT = 50;
    public static final int TEAM_PVP_SLOT = 51;

    private static final String PRIMARY = "&#8436FE";
    private static final String SECONDARY = "&#B078FF";
    private static final String BODY = "&#bbbbbb";

    private static final Map<UUID, TeamSortMode> SORT_MODES = new HashMap<>();

    private TeamsMainGui() {
    }

    public static void open(
            Core core,
            Player player,
            TeamService teamService,
            TeamInviteService inviteService
    ) {
        TeamRecord team = teamService.getTeamByPlayer(player.getUniqueId());

        if (team == null) {
            TeamStartGui.open(player, inviteService);
            return;
        }

        TeamHomeService teamHomeService = new TeamHomeService(core);
        boolean hasTeamHome = teamHomeService.hasTeamHome(team.teamId());
        boolean teamChatEnabled = teamService.isTeamChatEnabled(player.getUniqueId());
        List<UUID> members = sortedMembers(player, team.teamId(), teamService);
        int memberCount = members.size();

        Inventory inventory = Bukkit.createInventory(
                null,
                54,
                GuiText.title(
                        team.name()
                                + " (" + memberCount + "/"
                                + teamService.maxMembers() + ")"
                )
        );

        int slot = 0;
        for (UUID memberId : members) {
            if (slot >= 45) {
                break;
            }

            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(memberId);
            TeamMemberRecord member = teamService.getMember(memberId);
            String role = member == null ? "Member" : member.role().displayName();
            boolean online = Bukkit.getPlayer(memberId) != null;
            String displayName = DisplayNames.displayName(offlinePlayer);

            inventory.setItem(
                    slot,
                    playerHead(
                            offlinePlayer,
                            (online ? "&a" : BODY) + displayName,
                            List.of(
                                    BODY + "Role: " + SECONDARY + role,
                                    BODY + "Balance: &a"
                                            + VaultMoneyHook.formattedBalance(offlinePlayer),
                                    BODY + "Status: "
                                            + (online ? "&aOnline" : BODY + "Offline"),
                                    "",
                                    BODY + "Click to manage"
                            )
                    )
            );
            slot++;
        }

        if (teamService.isAdmin(player.getUniqueId())
                && memberCount < teamService.maxMembers()
                && slot < 45) {
            inventory.setItem(
                    slot,
                    item(
                            Material.LIME_STAINED_GLASS_PANE,
                            "&aInvite Player",
                            List.of(
                                    BODY + "Prepare a team invitation",
                                    "",
                                    BODY + "Click to autofill /team invite"
                            )
                    )
            );
        }

        inventory.setItem(TEAM_HOME_SLOT, teamHomeItem(hasTeamHome));
        inventory.setItem(TEAM_CHAT_SLOT, teamChatItem(teamChatEnabled));
        inventory.setItem(
                TEAM_INFO_SLOT,
                item(
                        Material.BOOK,
                        PRIMARY + "Team Info",
                        List.of(
                                BODY + "Name: " + SECONDARY + team.name(),
                                BODY + "Members: " + SECONDARY
                                        + memberCount + BODY + "/"
                                        + teamService.maxMembers()
                        )
                )
        );
        inventory.setItem(SORT_SLOT, sortItem(currentSort(player)));

        if (teamService.isAdmin(player.getUniqueId())) {
            inventory.setItem(TEAM_PVP_SLOT, pvpItem(team.friendlyFire()));
        }

        player.openInventory(inventory);
    }

    public static List<UUID> sortedMembers(
            Player viewer,
            String teamId,
            TeamService teamService
    ) {
        List<UUID> members = new ArrayList<>(
                teamService.getTeamMembers(teamId)
        );
        TeamSortMode mode = currentSort(viewer);

        members.sort(switch (mode) {
            case JOIN_DATE -> Comparator.comparingLong(id -> {
                TeamMemberRecord member = teamService.getMember(id);
                return member == null ? Long.MAX_VALUE : member.joinedAt();
            });
            case PERMISSIONS -> Comparator
                    .comparingInt((UUID id) -> {
                        TeamMemberRecord member = teamService.getMember(id);
                        return member == null
                                ? Integer.MAX_VALUE
                                : member.role().ordinal();
                    })
                    .thenComparing(
                            id -> DisplayNames.displayName(Bukkit.getOfflinePlayer(id)),
                            String.CASE_INSENSITIVE_ORDER
                    );
            case ALPHABETICALLY -> Comparator.comparing(
                    id -> DisplayNames.displayName(Bukkit.getOfflinePlayer(id)),
                    String.CASE_INSENSITIVE_ORDER
            );
            case ONLINE_MEMBERS -> Comparator
                    .comparing((UUID id) -> Bukkit.getPlayer(id) == null)
                    .thenComparing(
                            id -> DisplayNames.displayName(Bukkit.getOfflinePlayer(id)),
                            String.CASE_INSENSITIVE_ORDER
                    );
            case MONEY -> Comparator
                    .comparingDouble(
                            (UUID id) -> parsedBalance(Bukkit.getOfflinePlayer(id))
                    )
                    .reversed()
                    .thenComparing(
                            id -> DisplayNames.displayName(Bukkit.getOfflinePlayer(id)),
                            String.CASE_INSENSITIVE_ORDER
                    );
        });

        return members;
    }

    public static TeamSortMode currentSort(Player player) {
        return SORT_MODES.getOrDefault(
                player.getUniqueId(),
                TeamSortMode.JOIN_DATE
        );
    }

    public static void cycleSort(Player player) {
        SORT_MODES.put(
                player.getUniqueId(),
                currentSort(player).next()
        );
    }

    public static void clearPlayerState(UUID playerId) {
        if (playerId != null) {
            SORT_MODES.remove(playerId);
        }
    }

    private static double parsedBalance(OfflinePlayer player) {
        String formatted = VaultMoneyHook.formattedBalance(player);

        if (formatted.isBlank()) {
            return 0.0D;
        }

        String cleaned = formatted
                .replace("$", "")
                .replace(",", "")
                .replace(" ", "")
                .trim();

        try {
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException ignored) {
            return 0.0D;
        }
    }

    private static ItemStack teamHomeItem(boolean hasTeamHome) {
        if (hasTeamHome) {
            return item(
                    Material.PURPLE_BANNER,
                    PRIMARY + "Team Home",
                    List.of(
                            BODY + "Status: &aSet",
                            "",
                            BODY + "Click to teleport"
                    )
            );
        }

        return item(
                Material.WHITE_BANNER,
                PRIMARY + "Team Home",
                List.of(
                        BODY + "Status: &cNot Set",
                        "",
                        BODY + "Click to open Homes"
                )
        );
    }

    private static ItemStack teamChatItem(boolean enabled) {
        return item(
                enabled ? Material.LIME_DYE : Material.GRAY_DYE,
                PRIMARY + "Team Chat",
                List.of(
                        BODY + "Status: "
                                + (enabled ? "&aEnabled" : "&cDisabled"),
                        "",
                        BODY + "Click to toggle"
                )
        );
    }

    private static ItemStack sortItem(TeamSortMode current) {
        List<String> lore = new ArrayList<>();
        lore.add(BODY + "Current: " + SECONDARY + current.displayName());
        lore.add("");

        for (TeamSortMode mode : TeamSortMode.values()) {
            lore.add(
                    (mode == current ? PRIMARY : BODY)
                            + mode.displayName()
            );
        }

        lore.add("");
        lore.add(BODY + "Click to change sorting");

        return item(Material.HOPPER, PRIMARY + "Sort Members", lore);
    }

    private static ItemStack pvpItem(boolean friendlyFire) {
        return item(
                Material.DIAMOND_SWORD,
                PRIMARY + "Team PvP",
                List.of(
                        BODY + "Status: "
                                + (friendlyFire ? "&aEnabled" : "&cDisabled"),
                        "",
                        BODY + "Click to toggle"
                )
        );
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
        GuiText.apply(meta, name, lore);
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

        GuiText.apply(meta, name, lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

}
