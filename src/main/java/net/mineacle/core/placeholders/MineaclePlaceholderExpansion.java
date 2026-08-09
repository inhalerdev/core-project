package net.mineacle.core.placeholders;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import net.mineacle.core.Core;
import net.mineacle.core.common.format.MoneyFormatter;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.economy.service.EconomyService;
import net.mineacle.core.placeholders.PlaceholderSnapshotService.BalanceEntry;
import net.mineacle.core.placeholders.PlaceholderSnapshotService.PlayerIdentity;
import net.mineacle.core.placeholders.PlaceholderSnapshotService.Snapshot;
import net.mineacle.core.placeholders.PlaceholderSnapshotService.StatEntry;
import net.mineacle.core.stats.service.StatsService;
import net.mineacle.core.stats.service.StatsStorageService;
import net.mineacle.core.teams.model.TeamMemberRecord;
import net.mineacle.core.teams.model.TeamRecord;
import net.mineacle.core.teams.service.TeamService;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

public final class MineaclePlaceholderExpansion
        extends PlaceholderExpansion {

    private final Core core;
    private final EconomyService economyService;
    private final TeamService teamService;
    private final StatsService statsService;
    private final PlaceholderSnapshotService snapshots;

    private volatile DateTimeSettings dateTimeSettings;

    public MineaclePlaceholderExpansion(
            Core core,
            EconomyService economyService,
            TeamService teamService,
            StatsService statsService,
            PlaceholderSnapshotService snapshots
    ) {
        this.core = core;
        this.economyService = economyService;
        this.teamService = teamService;
        this.statsService = statsService;
        this.snapshots = snapshots;
        reload();
    }

    public void reload() {
        dateTimeSettings = DateTimeSettings.load(core);
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
    public @Nullable String onRequest(
            OfflinePlayer player,
            @NotNull String params
    ) {
        String key = normalize(params);

        if (key.isEmpty()) {
            return null;
        }

        String globalBalance = globalBalTop(key);

        if (globalBalance != null) {
            return globalBalance;
        }

        String globalKills = globalStats(
                key,
                "kills"
        );

        if (globalKills != null) {
            return globalKills;
        }

        String globalDeaths = globalStats(
                key,
                "deaths"
        );

        if (globalDeaths != null) {
            return globalDeaths;
        }

        String globalPlaytime = globalStats(
                key,
                "playtime"
        );

        if (globalPlaytime != null) {
            return globalPlaytime;
        }

        if (player == null) {
            return "";
        }

        UUID uuid = player.getUniqueId();

        return switch (key) {
            case "name", "player_name", "username" ->
                    DisplayNames.username(player);

            case "displayname", "display_name",
                 "player_displayname", "player_display_name" ->
                    DisplayNames.displayName(player);

            case "nickname", "nick",
                 "player_nickname", "player_nick" ->
                    DisplayNames.nickname(player);

            case "chat_displayname", "chat_display_name",
                 "tab_displayname", "tab_display_name" ->
                    DisplayNames.coloredDisplayName(player);

            case "chat_prefixed_displayname",
                 "chat_prefixed_display_name",
                 "prefixed_displayname",
                 "prefixed_display_name" ->
                    DisplayNames.prefixedDisplayName(player);

            case "balance", "balance_formatted",
                 "money", "money_formatted" ->
                    compactBalance(uuid);

            case "balance_full", "money_full" ->
                    fullBalance(uuid);

            case "balance_raw", "money_raw" ->
                    rawBalance(uuid);

            case "baltop_rank", "baltop_position" ->
                    formattedRank(
                            snapshots.snapshot()
                                    .balanceRank(uuid)
                    );

            case "baltop_rank_raw",
                 "baltop_position_raw" ->
                    String.valueOf(
                            snapshots.snapshot()
                                    .balanceRank(uuid)
                    );

            case "baltop_value",
                 "baltop_value_formatted",
                 "baltop_balance",
                 "baltop_balance_formatted" ->
                    compactBalance(uuid);

            case "baltop_value_full",
                 "baltop_balance_full" ->
                    fullBalance(uuid);

            case "baltop_value_raw",
                 "baltop_balance_raw" ->
                    rawBalance(uuid);

            case "kills_rank" ->
                    statValue(uuid, StatKind.KILLS) <= 0L
                            ? unranked()
                            : formattedRank(
                            statsService == null
                                    ? 0
                                    : statsService.rankKills(uuid)
                    );

            case "kills_rank_raw" ->
                    statValue(uuid, StatKind.KILLS) <= 0L
                            ? "0"
                            : String.valueOf(
                            statsService == null
                                    ? 0
                                    : statsService.rankKills(uuid)
                    );

            case "deaths_rank" ->
                    statValue(uuid, StatKind.DEATHS) <= 0L
                            ? unranked()
                            : formattedRank(
                            statsService == null
                                    ? 0
                                    : statsService.rankDeaths(uuid)
                    );

            case "deaths_rank_raw" ->
                    statValue(uuid, StatKind.DEATHS) <= 0L
                            ? "0"
                            : String.valueOf(
                            statsService == null
                                    ? 0
                                    : statsService.rankDeaths(uuid)
                    );

            case "playtime_rank" ->
                    formattedRank(
                            statsService == null
                                    ? 0
                                    : statsService.rankPlaytime(uuid)
                    );

            case "playtime_rank_raw" ->
                    String.valueOf(
                            statsService == null
                                    ? 0
                                    : statsService.rankPlaytime(uuid)
                    );

            case "team_name" -> teamName(uuid);
            case "team_role", "team_rank" -> teamRole(uuid);
            case "team_member_count", "team_members" ->
                    teamMemberCount(uuid);
            case "team_max_members" ->
                    teamService == null
                            ? "0"
                            : String.valueOf(
                            teamService.maxMembers()
                    );
            case "team_pvp",
                 "team_friendlyfire",
                 "team_friendly_fire" ->
                    teamPvp(uuid);
            case "team_chat" -> teamChat(uuid);

            case "stats_kills",
                 "stats_player_kills",
                 "kills" ->
                    String.valueOf(
                            statValue(uuid, StatKind.KILLS)
                    );

            case "stats_deaths", "deaths" ->
                    String.valueOf(
                            statValue(uuid, StatKind.DEATHS)
                    );

            case "stats_playtime",
                 "playtime",
                 "playtime_time" ->
                    statsService == null
                            ? "0m"
                            : statsService.playtime(uuid);

            case "stats_playtime_seconds",
                 "playtime_seconds" ->
                    String.valueOf(
                            statValue(
                                    uuid,
                                    StatKind.PLAYTIME
                            )
                    );

            case "stats_blocks_placed",
                 "blocks_placed" ->
                    String.valueOf(
                            statsService == null
                                    ? 0L
                                    : statsService.blocksPlaced(uuid)
                    );

            case "stats_blocks_broken",
                 "blocks_broken" ->
                    String.valueOf(
                            statsService == null
                                    ? 0L
                                    : statsService.blocksBroken(uuid)
                    );

            case "stats_mobs_killed",
                 "mobs_killed" ->
                    String.valueOf(
                            statsService == null
                                    ? 0L
                                    : statsService.mobsKilled(uuid)
                    );

            case "date" -> dateTimeSettings.date();
            case "time" -> dateTimeSettings.time();
            case "datetime", "date_time" ->
                    dateTimeSettings.dateTime();
            case "timezone", "time_zone" ->
                    dateTimeSettings.zone().getId();

            default -> null;
        };
    }

    private String globalBalTop(String key) {
        ParsedGlobal parsed = ParsedGlobal.parse(
                key,
                "baltop"
        );

        if (parsed == null) {
            return null;
        }

        BalanceEntry entry = snapshots.snapshot()
                .balanceAt(parsed.position());

        if (entry == null) {
            return emptyBalance(parsed.field());
        }

        PlayerIdentity player = entry.player();

        return switch (parsed.field()) {
            case "name", "username",
                 "player", "player_name" ->
                    player.username();

            case "displayname", "display_name",
                 "player_displayname",
                 "player_display_name" ->
                    player.displayName();

            case "nickname", "nick" ->
                    emptyIfBlank(player.nickname());

            case "balance", "balance_formatted",
                 "money", "money_formatted",
                 "value", "value_formatted" ->
                    MoneyFormatter.moneyFromCents(
                            entry.cents()
                    );

            case "balance_compact",
                 "money_compact",
                 "value_compact" ->
                    MoneyFormatter.compact(
                            java.math.BigDecimal
                                    .valueOf(entry.cents())
                                    .movePointLeft(2)
                    );

            case "balance_full",
                 "money_full",
                 "value_full" ->
                    MoneyFormatter.moneyFullFromCents(
                            entry.cents()
                    );

            case "balance_raw",
                 "money_raw",
                 "value_raw" ->
                    MoneyFormatter.rawFromCents(
                            entry.cents()
                    );

            case "rank", "position" ->
                    "#" + parsed.position();

            case "rank_raw", "position_raw" ->
                    String.valueOf(parsed.position());

            default -> null;
        };
    }

    private String globalStats(
            String key,
            String type
    ) {
        ParsedGlobal parsed = ParsedGlobal.parse(
                key,
                type
        );

        if (parsed == null) {
            return null;
        }

        StatEntry entry = snapshots.snapshot()
                .statAt(type, parsed.position());

        if (entry == null) {
            return emptyStat(parsed.field());
        }

        PlayerIdentity player = entry.player();
        StatsStorageService.StatProfile profile =
                entry.profile();

        return switch (parsed.field()) {
            case "name", "username",
                 "player", "player_name" ->
                    player.username();

            case "displayname", "display_name",
                 "player_displayname",
                 "player_display_name" ->
                    player.displayName();

            case "nickname", "nick" ->
                    emptyIfBlank(player.nickname());

            case "value" -> switch (type) {
                case "kills" ->
                        String.valueOf(profile.kills());
                case "deaths" ->
                        String.valueOf(profile.deaths());
                case "playtime" ->
                        formatPlaytime(
                                profile.playtimeSeconds()
                        );
                default -> "0";
            };

            case "kills" ->
                    type.equals("kills")
                            ? String.valueOf(profile.kills())
                            : null;

            case "deaths" ->
                    type.equals("deaths")
                            ? String.valueOf(profile.deaths())
                            : null;

            case "time", "playtime" ->
                    type.equals("playtime")
                            ? formatPlaytime(
                            profile.playtimeSeconds()
                    )
                            : null;

            case "seconds",
                 "playtime_seconds",
                 "value_raw" ->
                    type.equals("playtime")
                            ? String.valueOf(
                            profile.playtimeSeconds()
                    )
                            : type.equals("kills")
                            ? String.valueOf(profile.kills())
                            : String.valueOf(profile.deaths());

            case "rank", "position" ->
                    "#" + parsed.position();

            case "rank_raw", "position_raw" ->
                    String.valueOf(parsed.position());

            default -> null;
        };
    }

    private String compactBalance(UUID uuid) {
        long cents = balance(uuid);

        return MoneyFormatter.moneyFromCents(cents);
    }

    private String fullBalance(UUID uuid) {
        return MoneyFormatter.moneyFullFromCents(
                balance(uuid)
        );
    }

    private String rawBalance(UUID uuid) {
        return MoneyFormatter.rawFromCents(
                balance(uuid)
        );
    }

    private long balance(UUID uuid) {
        return economyService == null
                ? 0L
                : economyService.getBalanceCents(uuid);
    }

    private long statValue(
            UUID uuid,
            StatKind kind
    ) {
        if (statsService == null) {
            return 0L;
        }

        return switch (kind) {
            case KILLS -> statsService.kills(uuid);
            case DEATHS -> statsService.deaths(uuid);
            case PLAYTIME ->
                    statsService.playtimeSeconds(uuid);
        };
    }

    private String teamName(UUID uuid) {
        TeamRecord team = team(uuid);

        return team == null ? "" : team.name();
    }

    private String teamRole(UUID uuid) {
        if (teamService == null) {
            return "";
        }

        TeamMemberRecord member =
                teamService.getMember(uuid);

        return member == null
                ? ""
                : member.role().displayName();
    }

    private String teamMemberCount(UUID uuid) {
        TeamRecord team = team(uuid);

        if (team == null || teamService == null) {
            return "0";
        }

        return String.valueOf(
                teamService.getTeamMembers(
                        team.teamId()
                ).size()
        );
    }

    private String teamPvp(UUID uuid) {
        TeamRecord team = team(uuid);

        if (team == null) {
            return disabledLabel();
        }

        return team.friendlyFire()
                ? enabledLabel()
                : disabledLabel();
    }

    private String teamChat(UUID uuid) {
        if (teamService == null) {
            return disabledLabel();
        }

        return teamService.isTeamChatEnabled(uuid)
                ? enabledLabel()
                : disabledLabel();
    }

    private TeamRecord team(UUID uuid) {
        return teamService == null
                ? null
                : teamService.getTeamByPlayer(uuid);
    }

    private String formatPlaytime(long seconds) {
        return statsService == null
                ? String.valueOf(seconds)
                : statsService.formatPlaytime(seconds);
    }

    private String emptyBalance(String field) {
        return switch (field) {
            case "name", "username",
                 "player", "player_name",
                 "displayname", "display_name",
                 "player_displayname",
                 "player_display_name",
                 "nickname", "nick",
                 "balance", "balance_formatted",
                 "money", "money_formatted",
                 "value", "value_formatted",
                 "balance_compact",
                 "money_compact",
                 "value_compact",
                 "balance_full", "money_full",
                 "value_full",
                 "rank", "position",
                 "rank_raw", "position_raw" ->
                    emptySlot();

            case "balance_raw",
                 "money_raw",
                 "value_raw" -> "0";

            default -> null;
        };
    }

    private String emptyStat(String field) {
        return switch (field) {
            case "name", "username",
                 "player", "player_name",
                 "displayname", "display_name",
                 "player_displayname",
                 "player_display_name",
                 "nickname", "nick",
                 "value", "kills", "deaths",
                 "time", "playtime",
                 "rank", "position",
                 "rank_raw", "position_raw" ->
                    emptySlot();

            case "seconds",
                 "playtime_seconds",
                 "value_raw" -> "0";

            default -> null;
        };
    }

    private String emptySlot() {
        return core.getConfig().getString(
                "placeholders.empty-slot",
                "---"
        );
    }

    private String unranked() {
        return core.getConfig().getString(
                "placeholders.labels.unranked",
                "Unranked"
        );
    }

    private String enabledLabel() {
        return core.getConfig().getString(
                "placeholders.labels.enabled",
                "Enabled"
        );
    }

    private String disabledLabel() {
        return core.getConfig().getString(
                "placeholders.labels.disabled",
                "Disabled"
        );
    }

    private String formattedRank(int rank) {
        return rank <= 0
                ? unranked()
                : "#" + rank;
    }

    private String emptyIfBlank(String value) {
        return value == null || value.isBlank()
                ? emptySlot()
                : value;
    }

    private String normalize(String input) {
        return input == null
                ? ""
                : input.trim()
                .toLowerCase(Locale.ROOT);
    }

    private enum StatKind {
        KILLS,
        DEATHS,
        PLAYTIME
    }

    private record ParsedGlobal(
            int position,
            String field
    ) {

        private static ParsedGlobal parse(
                String key,
                String prefix
        ) {
            String fullPrefix = prefix + "_";

            if (!key.startsWith(fullPrefix)) {
                return null;
            }

            String rest = key.substring(
                    fullPrefix.length()
            );

            if (rest.equals("rank")
                    || rest.equals("rank_raw")) {
                return null;
            }

            int separator = rest.indexOf('_');

            if (separator <= 0
                    || separator >= rest.length() - 1) {
                return null;
            }

            int position;

            try {
                position = Integer.parseInt(
                        rest.substring(0, separator)
                );
            } catch (NumberFormatException exception) {
                return null;
            }

            if (position < 1) {
                return null;
            }

            return new ParsedGlobal(
                    position,
                    rest.substring(separator + 1)
            );
        }
    }

    private record DateTimeSettings(
            ZoneId zone,
            DateTimeFormatter dateFormatter,
            DateTimeFormatter timeFormatter,
            DateTimeFormatter dateTimeFormatter
    ) {

        private static DateTimeSettings load(Core core) {
            ZoneId zone = parseZone(
                    core.getConfig().getString(
                            "placeholders.datetime.timezone",
                            "America/Chicago"
                    )
            );

            return new DateTimeSettings(
                    zone,
                    formatter(
                            core.getConfig().getString(
                                    "placeholders.datetime.date-format",
                                    "MM/dd/yy"
                            ),
                            "MM/dd/yy"
                    ),
                    formatter(
                            core.getConfig().getString(
                                    "placeholders.datetime.time-format",
                                    "hh:mm"
                            ),
                            "hh:mm"
                    ),
                    formatter(
                            core.getConfig().getString(
                                    "placeholders.datetime.datetime-format",
                                    "MM/dd/yy | hh:mm"
                            ),
                            "MM/dd/yy | hh:mm"
                    )
            );
        }

        private static ZoneId parseZone(String raw) {
            try {
                return ZoneId.of(
                        raw == null || raw.isBlank()
                                ? "America/Chicago"
                                : raw.trim()
                );
            } catch (DateTimeException exception) {
                return ZoneId.of("America/Chicago");
            }
        }

        private static DateTimeFormatter formatter(
                String raw,
                String fallback
        ) {
            try {
                return DateTimeFormatter.ofPattern(
                        raw == null || raw.isBlank()
                                ? fallback
                                : raw
                );
            } catch (IllegalArgumentException exception) {
                return DateTimeFormatter.ofPattern(
                        fallback
                );
            }
        }

        private LocalDateTime now() {
            return LocalDateTime.now(zone);
        }

        private String date() {
            return now().format(dateFormatter);
        }

        private String time() {
            return now().format(timeFormatter);
        }

        private String dateTime() {
            return now().format(dateTimeFormatter);
        }
    }
}
