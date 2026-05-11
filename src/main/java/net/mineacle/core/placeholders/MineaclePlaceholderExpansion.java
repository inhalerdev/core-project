package net.mineacle.core.placeholders;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.mineacle.core.Core;
import net.mineacle.core.chat.ChatModule;
import net.mineacle.core.chat.service.NicknameService;
import net.mineacle.core.economy.service.EconomyService;
import net.mineacle.core.teams.model.TeamMemberRecord;
import net.mineacle.core.teams.model.TeamRecord;
import net.mineacle.core.teams.service.TeamService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class MineaclePlaceholderExpansion extends PlaceholderExpansion {

    private final Core core;
    private final EconomyService economyService;
    private final TeamService teamService;

    public MineaclePlaceholderExpansion(Core core, EconomyService economyService, TeamService teamService) {
        this.core = core;
        this.economyService = economyService;
        this.teamService = teamService;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "mineacle";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Mineacle";
    }

    @Override
    public @NotNull String getVersion() {
        return core.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public boolean canRegister() {
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) {
            return "";
        }

        String key = params.toLowerCase(Locale.ROOT);
        UUID uuid = player.getUniqueId();

        return switch (key) {
            case "name", "player_name", "username" -> username(player);

            case "displayname", "display_name", "player_displayname", "player_display_name" -> displayName(player);

            case "nickname", "nick", "player_nickname", "player_nick" -> nickname(player);

            case "chat_displayname", "chat_display_name" -> chatDisplayName(player);

            case "tab_displayname", "tab_display_name" -> tabDisplayName(player);

            case "balance", "balance_formatted", "money", "money_formatted" ->
                    economyService.format(economyService.getBalanceCents(uuid));

            case "balance_raw", "money_raw" ->
                    rawMoney(economyService.getBalanceCents(uuid));

            case "baltop_rank" ->
                    formattedBalTopRank(uuid);

            case "baltop_rank_raw" ->
                    String.valueOf(rawBalTopRank(uuid));

            case "team_name" ->
                    teamName(uuid);

            case "team_role", "team_rank" ->
                    teamRole(uuid);

            case "team_member_count", "team_members" ->
                    teamMemberCount(uuid);

            case "team_max_members" ->
                    String.valueOf(teamService.maxMembers());

            case "team_pvp", "team_friendlyfire", "team_friendly_fire" ->
                    teamPvp(uuid);

            case "team_chat" ->
                    teamChat(uuid);

            case "stats_kills", "stats_player_kills", "kills" ->
                    String.valueOf(statistic(uuid, Statistic.PLAYER_KILLS));

            case "stats_deaths", "deaths" ->
                    String.valueOf(statistic(uuid, Statistic.DEATHS));

            case "stats_playtime" ->
                    playtime(uuid);

            case "stats_playtime_seconds" ->
                    String.valueOf(playtimeSeconds(uuid));

            case "stats_blocks_placed", "blocks_placed" ->
                    String.valueOf(blocksPlaced(uuid));

            case "stats_blocks_broken", "blocks_broken" ->
                    String.valueOf(blocksBroken(uuid));

            case "stats_mobs_killed", "mobs_killed" ->
                    String.valueOf(statistic(uuid, Statistic.MOB_KILLS));

            case "date" ->
                    date();

            case "time" ->
                    time();

            case "datetime", "date_time" ->
                    dateTime();

            case "timezone", "time_zone" ->
                    timeZone();

            default -> null;
        };
    }

    private String username(OfflinePlayer player) {
        NicknameService service = ChatModule.nicknameService();

        if (service != null) {
            return service.username(player);
        }

        return player.getName() == null ? player.getUniqueId().toString() : player.getName();
    }

    private String displayName(OfflinePlayer player) {
        NicknameService service = ChatModule.nicknameService();

        if (service != null) {
            return service.displayName(player);
        }

        return username(player);
    }

    private String nickname(OfflinePlayer player) {
        NicknameService service = ChatModule.nicknameService();

        if (service != null) {
            return service.nickname(player);
        }

        return "";
    }

    private String chatDisplayName(OfflinePlayer player) {
        NicknameService service = ChatModule.nicknameService();

        if (service != null) {
            return service.rawChatDisplayName(player);
        }

        return "&#bbbbbb" + username(player);
    }

    private String tabDisplayName(OfflinePlayer player) {
        return chatDisplayName(player);
    }

    private String rawMoney(long cents) {
        BigDecimal value = BigDecimal.valueOf(cents)
                .divide(BigDecimal.valueOf(100L), 2, RoundingMode.HALF_UP)
                .stripTrailingZeros();

        return value.toPlainString();
    }

    private int rawBalTopRank(UUID playerId) {
        List<Map.Entry<UUID, Long>> entries = economyService.topBalances(Integer.MAX_VALUE);

        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).getKey().equals(playerId)) {
                return i + 1;
            }
        }

        return 0;
    }

    private String formattedBalTopRank(UUID playerId) {
        int rank = rawBalTopRank(playerId);
        return rank <= 0 ? "Unranked" : "#" + rank;
    }

    private String teamName(UUID playerId) {
        TeamRecord team = teamService.getTeamByPlayer(playerId);
        return team == null ? "" : team.name();
    }

    private String teamRole(UUID playerId) {
        TeamMemberRecord member = teamService.getMember(playerId);
        return member == null ? "" : member.role().displayName();
    }

    private String teamMemberCount(UUID playerId) {
        TeamRecord team = teamService.getTeamByPlayer(playerId);

        if (team == null) {
            return "0";
        }

        return String.valueOf(teamService.getTeamMembers(team.teamId()).size());
    }

    private String teamPvp(UUID playerId) {
        TeamRecord team = teamService.getTeamByPlayer(playerId);

        if (team == null) {
            return "Off";
        }

        return team.friendlyFire() ? "On" : "Off";
    }

    private String teamChat(UUID playerId) {
        return teamService.isTeamChatEnabled(playerId) ? "Enabled" : "Disabled";
    }

    private int statistic(UUID playerId, Statistic statistic) {
        Player player = Bukkit.getPlayer(playerId);

        if (player == null) {
            return 0;
        }

        try {
            return player.getStatistic(statistic);
        } catch (Exception ignored) {
            return 0;
        }
    }

    private long playtimeSeconds(UUID playerId) {
        return statistic(playerId, Statistic.PLAY_ONE_MINUTE) / 20L;
    }

    private String playtime(UUID playerId) {
        long totalSeconds = playtimeSeconds(playerId);

        long days = totalSeconds / 86400L;
        long hours = (totalSeconds % 86400L) / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;

        if (days > 0) {
            return days + "d " + hours + "h";
        }

        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }

        return minutes + "m";
    }

    private int blocksPlaced(UUID playerId) {
        Player player = Bukkit.getPlayer(playerId);

        if (player == null) {
            return 0;
        }

        int total = 0;

        for (Material material : Material.values()) {
            if (!material.isBlock() || !material.isItem()) {
                continue;
            }

            try {
                total += player.getStatistic(Statistic.USE_ITEM, material);
            } catch (Exception ignored) {
            }
        }

        return total;
    }

    private int blocksBroken(UUID playerId) {
        Player player = Bukkit.getPlayer(playerId);

        if (player == null) {
            return 0;
        }

        int total = 0;

        for (Material material : Material.values()) {
            if (!material.isBlock()) {
                continue;
            }

            try {
                total += player.getStatistic(Statistic.MINE_BLOCK, material);
            } catch (Exception ignored) {
            }
        }

        return total;
    }

    private String date() {
        return now().format(DateTimeFormatter.ofPattern(
                core.getConfig().getString("placeholders.datetime.date-format", "MM/dd/yy")
        ));
    }

    private String time() {
        return now().format(DateTimeFormatter.ofPattern(
                core.getConfig().getString("placeholders.datetime.time-format", "hh:mm")
        ));
    }

    private String dateTime() {
        return now().format(DateTimeFormatter.ofPattern(
                core.getConfig().getString("placeholders.datetime.datetime-format", "MM/dd/yy | hh:mm")
        ));
    }

    private String timeZone() {
        return zoneId().getId();
    }

    private LocalDateTime now() {
        return LocalDateTime.now(zoneId());
    }

    private ZoneId zoneId() {
        String raw = core.getConfig().getString("placeholders.datetime.timezone", "America/Chicago");

        try {
            return ZoneId.of(raw);
        } catch (Exception ignored) {
            return ZoneId.systemDefault();
        }
    }
}