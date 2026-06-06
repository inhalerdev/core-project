package net.mineacle.core.placeholders;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.mineacle.core.Core;
import net.mineacle.core.chat.ChatModule;
import net.mineacle.core.chat.service.NicknameService;
import net.mineacle.core.common.format.MoneyFormatter;
import net.mineacle.core.economy.service.EconomyService;
import net.mineacle.core.stats.service.StatsService;
import net.mineacle.core.stats.service.StatsStorageService;
import net.mineacle.core.teams.model.TeamMemberRecord;
import net.mineacle.core.teams.model.TeamRecord;
import net.mineacle.core.teams.service.TeamService;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class MineaclePlaceholderExpansion extends PlaceholderExpansion {

    private static final String EMPTY_SLOT = "---";

    private final Core core;
    private final EconomyService economyService;
    private final TeamService teamService;
    private final StatsService statsService;

    public MineaclePlaceholderExpansion(Core core, EconomyService economyService, TeamService teamService, StatsService statsService) {
        this.core = core;
        this.economyService = economyService;
        this.teamService = teamService;
        this.statsService = statsService;
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
        String key = params.toLowerCase(Locale.ROOT);

        String globalBalTop = globalBalTopPlaceholder(key);
        if (globalBalTop != null) {
            return globalBalTop;
        }

        String globalKills = globalStatsPlaceholder(key, "kills");
        if (globalKills != null) {
            return globalKills;
        }

        String globalDeaths = globalStatsPlaceholder(key, "deaths");
        if (globalDeaths != null) {
            return globalDeaths;
        }

        String globalPlaytime = globalStatsPlaceholder(key, "playtime");
        if (globalPlaytime != null) {
            return globalPlaytime;
        }

        if (player == null) {
            return "";
        }

        UUID uuid = player.getUniqueId();

        return switch (key) {
            case "name", "player_name", "username" -> username(player);
            case "displayname", "display_name", "player_displayname", "player_display_name" -> displayName(player);
            case "nickname", "nick", "player_nickname", "player_nick" -> nickname(player);
            case "chat_displayname", "chat_display_name" -> chatDisplayName(player);
            case "tab_displayname", "tab_display_name" -> tabDisplayName(player);

            case "balance", "balance_formatted", "money", "money_formatted" -> compactMoney(economyService.getBalanceCents(uuid));
            case "balance_full", "money_full" -> MoneyFormatter.rawFromCents(economyService.getBalanceCents(uuid));
            case "balance_raw", "money_raw" -> MoneyFormatter.rawFromCents(economyService.getBalanceCents(uuid));

            case "baltop_rank", "baltop_position" -> formattedBalTopRank(uuid);
            case "baltop_rank_raw", "baltop_position_raw" -> String.valueOf(rawBalTopRank(uuid));
            case "baltop_value", "baltop_value_formatted", "baltop_balance", "baltop_balance_formatted" -> compactMoney(economyService.getBalanceCents(uuid));
            case "baltop_value_raw", "baltop_balance_raw" -> MoneyFormatter.rawFromCents(economyService.getBalanceCents(uuid));

            case "kills_rank" -> formattedRank(statsService.rankKills(uuid));
            case "kills_rank_raw" -> String.valueOf(statsService.rankKills(uuid));
            case "deaths_rank" -> formattedRank(statsService.rankDeaths(uuid));
            case "deaths_rank_raw" -> String.valueOf(statsService.rankDeaths(uuid));
            case "playtime_rank" -> formattedRank(statsService.rankPlaytime(uuid));
            case "playtime_rank_raw" -> String.valueOf(statsService.rankPlaytime(uuid));

            case "team_name" -> teamName(uuid);
            case "team_role", "team_rank" -> teamRole(uuid);
            case "team_member_count", "team_members" -> teamMemberCount(uuid);
            case "team_max_members" -> String.valueOf(teamService.maxMembers());
            case "team_pvp", "team_friendlyfire", "team_friendly_fire" -> teamPvp(uuid);
            case "team_chat" -> teamChat(uuid);

            case "stats_kills", "stats_player_kills", "kills" -> String.valueOf(statsService.kills(uuid));
            case "stats_deaths", "deaths" -> String.valueOf(statsService.deaths(uuid));
            case "stats_playtime", "playtime", "playtime_time" -> statsService.playtime(uuid);
            case "stats_playtime_seconds", "playtime_seconds" -> String.valueOf(statsService.playtimeSeconds(uuid));
            case "stats_blocks_placed", "blocks_placed" -> String.valueOf(statsService.blocksPlaced(uuid));
            case "stats_blocks_broken", "blocks_broken" -> String.valueOf(statsService.blocksBroken(uuid));
            case "stats_mobs_killed", "mobs_killed" -> String.valueOf(statsService.mobsKilled(uuid));

            case "date" -> date();
            case "time" -> time();
            case "datetime", "date_time" -> dateTime();
            case "timezone", "time_zone" -> timeZone();
            default -> null;
        };
    }

    private String globalStatsPlaceholder(String key, String type) {
        if (!key.startsWith(type + "_")) {
            return null;
        }

        String rest = key.substring((type + "_").length());

        if (rest.equals("rank") || rest.equals("rank_raw")) {
            return null;
        }

        int split = rest.indexOf('_');

        if (split <= 0 || split >= rest.length() - 1) {
            return null;
        }

        int position;

        try {
            position = Integer.parseInt(rest.substring(0, split));
        } catch (NumberFormatException ignored) {
            return null;
        }

        String field = rest.substring(split + 1);

        if (position < 1 || position > 100) {
            return emptyStatValue(field);
        }

        StatsStorageService.StatProfile profile = switch (type) {
            case "kills" -> statEntry(statsService.topKills(position), position);
            case "deaths" -> statEntry(statsService.topDeaths(position), position);
            case "playtime" -> statEntry(statsService.topPlaytime(position), position);
            default -> null;
        };

        if (profile == null) {
            return emptyStatValue(field);
        }

        OfflinePlayer statPlayer = statsService.offline(profile.uuid());

        return switch (field) {
            case "name", "username", "player", "player_name" -> username(statPlayer);
            case "displayname", "display_name", "player_displayname", "player_display_name" -> displayName(statPlayer);
            case "nickname", "nick" -> emptyIfBlank(nickname(statPlayer));
            case "value" -> type.equals("playtime") ? statsService.formatPlaytime(profile.playtimeSeconds()) : rawStatValue(type, profile);
            case "kills" -> type.equals("kills") ? String.valueOf(profile.kills()) : null;
            case "deaths" -> type.equals("deaths") ? String.valueOf(profile.deaths()) : null;
            case "time", "playtime" -> type.equals("playtime") ? statsService.formatPlaytime(profile.playtimeSeconds()) : null;
            case "seconds", "playtime_seconds", "value_raw" -> type.equals("playtime") ? String.valueOf(profile.playtimeSeconds()) : rawStatValue(type, profile);
            case "rank", "position" -> "#" + position;
            case "rank_raw", "position_raw" -> String.valueOf(position);
            default -> null;
        };
    }

    private StatsStorageService.StatProfile statEntry(List<StatsStorageService.StatProfile> profiles, int position) {
        if (profiles.size() < position) {
            return null;
        }

        return profiles.get(position - 1);
    }

    private String rawStatValue(String type, StatsStorageService.StatProfile profile) {
        return switch (type) {
            case "kills" -> String.valueOf(profile.kills());
            case "deaths" -> String.valueOf(profile.deaths());
            case "playtime" -> String.valueOf(profile.playtimeSeconds());
            default -> "0";
        };
    }

    private String emptyStatValue(String field) {
        return switch (field) {
            case "name", "username", "player", "player_name",
                 "displayname", "display_name", "player_displayname", "player_display_name",
                 "nickname", "nick",
                 "value", "kills", "deaths", "time", "playtime",
                 "rank", "position", "rank_raw", "position_raw" -> EMPTY_SLOT;
            case "seconds", "playtime_seconds", "value_raw" -> "0";
            default -> null;
        };
    }

    private String globalBalTopPlaceholder(String key) {
        if (!key.startsWith("baltop_")) {
            return null;
        }

        String rest = key.substring("baltop_".length());
        int split = rest.indexOf('_');

        if (split <= 0 || split >= rest.length() - 1) {
            return null;
        }

        int position;

        try {
            position = Integer.parseInt(rest.substring(0, split));
        } catch (NumberFormatException ignored) {
            return null;
        }

        String field = rest.substring(split + 1);

        if (position < 1 || position > 100) {
            return emptyBalTopValue(field);
        }

        BalTopEntry entry = balTopEntry(position);

        if (entry == null) {
            return emptyBalTopValue(field);
        }

        return switch (field) {
            case "name", "username", "player", "player_name" -> username(entry.player());
            case "displayname", "display_name", "player_displayname", "player_display_name" -> displayName(entry.player());
            case "nickname", "nick" -> emptyIfBlank(nickname(entry.player()));
            case "balance", "balance_formatted", "money", "money_formatted", "value", "value_formatted" -> compactMoney(entry.cents());
            case "balance_full", "money_full", "value_full" -> MoneyFormatter.rawFromCents(entry.cents());
            case "balance_raw", "money_raw", "value_raw" -> MoneyFormatter.rawFromCents(entry.cents());
            case "rank", "position" -> "#" + position;
            case "rank_raw", "position_raw" -> String.valueOf(position);
            default -> null;
        };
    }

    private String emptyBalTopValue(String field) {
        return switch (field) {
            case "name", "username", "player", "player_name",
                 "displayname", "display_name", "player_displayname", "player_display_name",
                 "nickname", "nick",
                 "balance", "balance_formatted", "money", "money_formatted",
                 "value", "value_formatted", "balance_full", "money_full", "value_full",
                 "rank", "position", "rank_raw", "position_raw" -> EMPTY_SLOT;
            case "balance_raw", "money_raw", "value_raw" -> "0";
            default -> null;
        };
    }

    private BalTopEntry balTopEntry(int position) {
        List<Map.Entry<UUID, Long>> entries = economyService.topBalances(position);

        if (entries.size() < position) {
            return null;
        }

        Map.Entry<UUID, Long> entry = entries.get(position - 1);
        OfflinePlayer player = Bukkit.getOfflinePlayer(entry.getKey());
        return new BalTopEntry(player, entry.getValue());
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
        return formattedRank(rawBalTopRank(playerId));
    }

    private String formattedRank(int rank) {
        return rank <= 0 ? "Unranked" : "#" + rank;
    }

    private String compactMoney(long cents) {
        BigDecimal dollars = BigDecimal.valueOf(cents).divide(BigDecimal.valueOf(100L), 2, RoundingMode.DOWN);
        return MoneyFormatter.compact(dollars);
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

    private String date() {
        return now().format(DateTimeFormatter.ofPattern(core.getConfig().getString("placeholders.datetime.date-format", "MM/dd/yy")));
    }

    private String time() {
        return now().format(DateTimeFormatter.ofPattern(core.getConfig().getString("placeholders.datetime.time-format", "hh:mm")));
    }

    private String dateTime() {
        return now().format(DateTimeFormatter.ofPattern(core.getConfig().getString("placeholders.datetime.datetime-format", "MM/dd/yy | hh:mm")));
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

    private String emptyIfBlank(String value) {
        return value == null || value.isBlank() ? EMPTY_SLOT : value;
    }

    private record BalTopEntry(OfflinePlayer player, long cents) {
    }
}
