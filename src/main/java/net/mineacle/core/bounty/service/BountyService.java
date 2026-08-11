package net.mineacle.core.bounty.service;

import net.mineacle.core.Core;
import net.mineacle.core.bounty.BountyDatabaseMirror;
import net.mineacle.core.bounty.BountyRecord;
import net.mineacle.core.bounty.BountyRepository;
import net.mineacle.core.bounty.BountySortMode;
import net.mineacle.core.common.format.MoneyFormatter;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.economy.EconomyModule;
import net.mineacle.core.economy.service.EconomyService;
import net.mineacle.core.teams.TeamsModule;
import net.mineacle.core.teams.model.TeamRecord;
import net.mineacle.core.teams.service.TeamService;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public final class BountyService {

    private static final List<String> DEFAULT_CLAIM_WORLDS =
            List.of(
                    "overworld",
                    "overworld_nether",
                    "overworld_the_end"
            );

    public enum PlaceStatus {
        SUCCESS,
        DISABLED,
        INVALID_TARGET,
        SELF_TARGET,
        INVALID_AMOUNT,
        BELOW_MINIMUM,
        ABOVE_MAXIMUM,
        ECONOMY_UNAVAILABLE,
        NOT_ENOUGH_MONEY,
        STORAGE_ERROR
    }

    public enum ClaimStatus {
        SUCCESS,
        NO_BOUNTY,
        BLOCKED_WORLD,
        SAME_TEAM,
        ECONOMY_UNAVAILABLE,
        BALANCE_LIMIT,
        PAYOUT_FAILED,
        STORAGE_ERROR
    }

    public enum RemoveStatus {
        SUCCESS,
        NOT_FOUND,
        STORAGE_ERROR
    }

    public record PlaceResult(
            PlaceStatus status,
            long contributionCents,
            long totalBountyCents
    ) {
    }

    public record ClaimResult(
            ClaimStatus status,
            long grossCents,
            long payoutCents
    ) {
    }

    public record RemoveResult(
            RemoveStatus status,
            long removedCents
    ) {
    }

    private final Core core;
    private final BountyRepository repository;

    private boolean initialized;
    private BountyDatabaseMirror databaseMirror;
    private BukkitTask databaseMirrorTask;

    public BountyService(
            Core core,
            BountyRepository repository
    ) {
        this.core = core;
        this.repository = repository;
    }

    public synchronized void load()
            throws IOException {
        stopDatabaseMirror();
        repository.initialize();
        initialized = true;
        startDatabaseMirror();
    }

    public synchronized void reload()
            throws IOException {
        load();
    }

    public synchronized void shutdown() {
        stopDatabaseMirror();

        if (!initialized) {
            return;
        }

        initialized = false;

        try {
            repository.flush();
        } catch (IOException exception) {
            core.getLogger().log(
                    Level.SEVERE,
                    "Could not save bounties.yml during shutdown",
                    exception
            );
        }
    }

    public boolean enabled() {
        return core.getConfig()
                .getBoolean(
                        "bounty.enabled",
                        true
                );
    }

    public List<BountyRecord> list(
            BountySortMode sortMode
    ) {
        BountySortMode safeSort =
                sortMode == null
                        ? BountySortMode.AMOUNT
                        : sortMode;
        List<BountyRecord> records =
                new ArrayList<>(
                        repository.listAll()
                );

        Comparator<BountyRecord> comparator =
                switch (safeSort) {
                    case AMOUNT ->
                            Comparator
                                    .comparingLong(
                                            BountyRecord
                                                    ::amountCents
                                    )
                                    .reversed()
                                    .thenComparing(
                                            this::displayName,
                                            String.CASE_INSENSITIVE_ORDER
                                    );
                    case ONLINE ->
                            Comparator
                                    .comparing(
                                            this::isOnline
                                    )
                                    .reversed()
                                    .thenComparing(
                                            Comparator
                                                    .comparingLong(
                                                            BountyRecord
                                                                    ::amountCents
                                                    )
                                                    .reversed()
                                    )
                                    .thenComparing(
                                            this::displayName,
                                            String.CASE_INSENSITIVE_ORDER
                                    );
                    case RECENT ->
                            Comparator
                                    .comparingLong(
                                            BountyRecord
                                                    ::lastUpdated
                                    )
                                    .reversed()
                                    .thenComparing(
                                            Comparator
                                                    .comparingLong(
                                                            BountyRecord
                                                                    ::amountCents
                                                    )
                                                    .reversed()
                                    );
                    case NAME ->
                            Comparator.comparing(
                                    this::displayName,
                                    String.CASE_INSENSITIVE_ORDER
                            );
                };

        records.sort(comparator);
        return List.copyOf(records);
    }

    public BountyRecord get(
            UUID targetId
    ) {
        if (targetId == null) {
            return null;
        }

        return repository.find(targetId)
                .orElse(null);
    }

    public long getAmount(
            UUID targetId
    ) {
        BountyRecord record = get(targetId);

        return record == null
                ? 0L
                : record.amountCents();
    }

    public synchronized PlaceResult placeDetailed(
            Player setter,
            OfflinePlayer target,
            long amountCents
    ) {
        if (!enabled()) {
            return placeResult(
                    PlaceStatus.DISABLED,
                    amountCents,
                    target
            );
        }

        if (setter == null
                || target == null) {
            return new PlaceResult(
                    PlaceStatus.INVALID_TARGET,
                    amountCents,
                    0L
            );
        }

        UUID setterId =
                setter.getUniqueId();
        UUID targetId =
                target.getUniqueId();

        if (setterId.equals(targetId)) {
            return placeResult(
                    PlaceStatus.SELF_TARGET,
                    amountCents,
                    target
            );
        }

        if (amountCents <= 0L) {
            return placeResult(
                    PlaceStatus.INVALID_AMOUNT,
                    amountCents,
                    target
            );
        }

        long minimum =
                minimumCents();

        if (amountCents < minimum) {
            return placeResult(
                    PlaceStatus.BELOW_MINIMUM,
                    amountCents,
                    target
            );
        }

        long existing =
                getAmount(targetId);
        long combined;

        try {
            combined =
                    Math.addExact(
                            existing,
                            amountCents
                    );
        } catch (
                ArithmeticException exception
        ) {
            return new PlaceResult(
                    PlaceStatus.ABOVE_MAXIMUM,
                    amountCents,
                    existing
            );
        }

        long maximum =
                maximumCents();

        if (maximum > 0L
                && combined > maximum) {
            return new PlaceResult(
                    PlaceStatus.ABOVE_MAXIMUM,
                    amountCents,
                    existing
            );
        }

        EconomyService economy =
                EconomyModule.economyService();

        if (economy == null) {
            return new PlaceResult(
                    PlaceStatus.ECONOMY_UNAVAILABLE,
                    amountCents,
                    existing
            );
        }

        if (!economy.take(
                setterId,
                amountCents
        )) {
            return new PlaceResult(
                    PlaceStatus.NOT_ENOUGH_MONEY,
                    amountCents,
                    existing
            );
        }

        BountyRecord updated =
                new BountyRecord(
                        targetId,
                        stableUsername(
                                target
                        ),
                        combined,
                        System.currentTimeMillis()
                );

        try {
            repository.save(updated);
        } catch (IOException exception) {
            if (!economy.tryGive(
                    setterId,
                    amountCents
            )) {
                core.getLogger().severe(
                        "[Bounty] CRITICAL: could not refund "
                                + amountCents
                                + " cents to "
                                + setterId
                                + " after bounty storage failure"
                );
            }

            core.getLogger().log(
                    Level.SEVERE,
                    "Could not persist bounty for "
                            + targetId,
                    exception
            );

            return new PlaceResult(
                    PlaceStatus.STORAGE_ERROR,
                    amountCents,
                    existing
            );
        }

        mirrorUpsert(updated);

        core.getLogger().info(
                "[Bounty] PLACE setter="
                        + setterId
                        + " target="
                        + targetId
                        + " contribution-cents="
                        + amountCents
                        + " total-cents="
                        + combined
        );

        return new PlaceResult(
                PlaceStatus.SUCCESS,
                amountCents,
                combined
        );
    }

    public synchronized ClaimResult claimDetailed(
            Player killer,
            Player target
    ) {
        if (killer == null
                || target == null
                || killer.getUniqueId()
                .equals(
                        target.getUniqueId()
                )) {
            return claimResult(
                    ClaimStatus.NO_BOUNTY
            );
        }

        if (!claimAllowed(
                target.getWorld()
        )) {
            return claimResult(
                    ClaimStatus.BLOCKED_WORLD
            );
        }

        BountyRecord record =
                get(
                        target.getUniqueId()
                );

        if (record == null
                || record.amountCents() <= 0L) {
            return claimResult(
                    ClaimStatus.NO_BOUNTY
            );
        }

        if (sameTeamClaimBlocked(
                killer,
                target
        )) {
            return new ClaimResult(
                    ClaimStatus.SAME_TEAM,
                    record.amountCents(),
                    0L
            );
        }

        EconomyService economy =
                EconomyModule.economyService();

        if (economy == null) {
            return new ClaimResult(
                    ClaimStatus.ECONOMY_UNAVAILABLE,
                    record.amountCents(),
                    0L
            );
        }

        long payout =
                taxedPayout(
                        record.amountCents()
                );

        if (payout > 0L
                && !canReceive(
                economy,
                killer.getUniqueId(),
                payout
        )) {
            return new ClaimResult(
                    ClaimStatus.BALANCE_LIMIT,
                    record.amountCents(),
                    payout
            );
        }

        try {
            if (!repository.delete(
                    target.getUniqueId()
            )) {
                return claimResult(
                        ClaimStatus.NO_BOUNTY
                );
            }
        } catch (IOException exception) {
            core.getLogger().log(
                    Level.SEVERE,
                    "Could not remove claimed bounty for "
                            + target.getUniqueId(),
                    exception
            );

            return new ClaimResult(
                    ClaimStatus.STORAGE_ERROR,
                    record.amountCents(),
                    0L
            );
        }

        if (payout > 0L
                && !economy.tryGive(
                killer.getUniqueId(),
                payout
        )) {
            try {
                repository.save(record);
            } catch (IOException restoreException) {
                core.getLogger().log(
                        Level.SEVERE,
                        "[Bounty] CRITICAL: payout failed and bounty "
                                + "could not be restored for "
                                + target.getUniqueId(),
                        restoreException
                );

                return new ClaimResult(
                        ClaimStatus.STORAGE_ERROR,
                        record.amountCents(),
                        0L
                );
            }

            mirrorUpsert(record);

            return new ClaimResult(
                    ClaimStatus.PAYOUT_FAILED,
                    record.amountCents(),
                    0L
            );
        }

        mirrorDelete(
                record.targetId()
        );

        core.getLogger().info(
                "[Bounty] CLAIM killer="
                        + killer.getUniqueId()
                        + " target="
                        + target.getUniqueId()
                        + " gross-cents="
                        + record.amountCents()
                        + " payout-cents="
                        + payout
        );

        return new ClaimResult(
                ClaimStatus.SUCCESS,
                record.amountCents(),
                payout
        );
    }

    public synchronized RemoveResult removeDetailed(
            UUID targetId
    ) {
        if (targetId == null) {
            return new RemoveResult(
                    RemoveStatus.NOT_FOUND,
                    0L
            );
        }

        BountyRecord record =
                get(targetId);

        if (record == null) {
            return new RemoveResult(
                    RemoveStatus.NOT_FOUND,
                    0L
            );
        }

        try {
            if (!repository.delete(targetId)) {
                return new RemoveResult(
                        RemoveStatus.NOT_FOUND,
                        0L
                );
            }
        } catch (IOException exception) {
            core.getLogger().log(
                    Level.SEVERE,
                    "Could not remove bounty for "
                            + targetId,
                    exception
            );

            return new RemoveResult(
                    RemoveStatus.STORAGE_ERROR,
                    0L
            );
        }

        mirrorDelete(targetId);

        core.getLogger().info(
                "[Bounty] REMOVE target="
                        + targetId
                        + " amount-cents="
                        + record.amountCents()
        );

        return new RemoveResult(
                RemoveStatus.SUCCESS,
                record.amountCents()
        );
    }

    @SuppressWarnings("unused")
    public boolean place(
            Player setter,
            OfflinePlayer target,
            long amountCents
    ) {
        return placeDetailed(
                setter,
                target,
                amountCents
        ).status()
                == PlaceStatus.SUCCESS;
    }

    @SuppressWarnings("unused")
    public long claim(
            Player killer,
            Player target
    ) {
        ClaimResult result =
                claimDetailed(
                        killer,
                        target
                );

        return result.status()
                == ClaimStatus.SUCCESS
                ? result.payoutCents()
                : 0L;
    }

    public long remove(
            UUID targetId
    ) {
        RemoveResult result =
                removeDetailed(targetId);

        return result.status()
                == RemoveStatus.SUCCESS
                ? result.removedCents()
                : 0L;
    }

    public synchronized void add(
            OfflinePlayer target,
            long amountCents
    ) {
        if (target == null
                || amountCents <= 0L) {
            return;
        }

        long current =
                getAmount(
                        target.getUniqueId()
                );
        long combined;

        try {
            combined =
                    Math.addExact(
                            current,
                            amountCents
                    );
        } catch (
                ArithmeticException exception
        ) {
            return;
        }

        long maximum =
                maximumCents();

        if (maximum > 0L
                && combined > maximum) {
            return;
        }

        BountyRecord updated =
                new BountyRecord(
                        target.getUniqueId(),
                        stableUsername(target),
                        combined,
                        System.currentTimeMillis()
                );

        try {
            repository.save(updated);
            mirrorUpsert(updated);
        } catch (IOException exception) {
            core.getLogger().log(
                    Level.SEVERE,
                    "Could not directly add bounty for "
                            + target.getUniqueId(),
                    exception
            );
        }
    }

    public void save() {
        try {
            repository.flush();
        } catch (IOException exception) {
            core.getLogger().log(
                    Level.SEVERE,
                    "Could not save bounties.yml",
                    exception
            );
        }
    }

    public long taxedPayout(
            long amountCents
    ) {
        BigDecimal configured =
                BigDecimal.valueOf(
                        core.getConfig()
                                .getDouble(
                                        "bounty.tax-percent",
                                        -1.0D
                                )
                );

        if (configured.signum() <= 0) {
            return Math.max(
                    0L,
                    amountCents
            );
        }

        BigDecimal tax =
                configured.min(
                        BigDecimal.valueOf(
                                100L
                        )
                );
        BigDecimal kept =
                BigDecimal.ONE.subtract(
                        tax.divide(
                                BigDecimal.valueOf(
                                        100L
                                ),
                                8,
                                RoundingMode.HALF_UP
                        )
                );

        return BigDecimal
                .valueOf(
                        Math.max(
                                0L,
                                amountCents
                        )
                )
                .multiply(kept)
                .setScale(
                        0,
                        RoundingMode.HALF_UP
                )
                .longValue();
    }

    public long minimumCents() {
        Object configured =
                core.getConfig()
                        .get(
                                "bounty.minimum",
                                "1"
                        );
        long parsed =
                MoneyFormatter
                        .parsePositiveCents(
                                String.valueOf(
                                        configured
                                )
                        );

        return parsed > 0L
                ? parsed
                : 100L;
    }

    public long maximumCents() {
        Object configured =
                core.getConfig()
                        .get(
                                "bounty.maximum",
                                "999B"
                        );
        String raw =
                String.valueOf(
                        configured
                )
                        .trim();

        if (raw.equalsIgnoreCase("-1")
                || raw.equalsIgnoreCase("none")
                || raw.equalsIgnoreCase("unlimited")) {
            return -1L;
        }

        long parsed =
                MoneyFormatter
                        .parsePositiveCents(raw);

        return parsed > 0L
                ? parsed
                : MoneyFormatter
                .parsePositiveCents(
                        "999B"
                );
    }

    public String format(
            long cents
    ) {
        return MoneyFormatter
                .moneyFromCents(cents);
    }

    public long parseAmount(
            String raw
    ) {
        return MoneyFormatter
                .parsePositiveCents(raw);
    }

    public boolean wouldExceedMaximum(
            UUID targetId,
            long contributionCents
    ) {
        if (targetId == null
                || contributionCents <= 0L) {
            return false;
        }

        long maximum =
                maximumCents();

        if (maximum <= 0L) {
            return false;
        }

        try {
            return Math.addExact(
                    getAmount(targetId),
                    contributionCents
            ) > maximum;
        } catch (
                ArithmeticException exception
        ) {
            return true;
        }
    }

    public OfflinePlayer resolveTarget(
            String input
    ) {
        Player online =
                DisplayNames.resolveOnline(
                        input
                );

        if (online != null) {
            return online;
        }

        OfflinePlayer offline =
                DisplayNames.resolveOffline(
                        input
                );

        if (offline == null
                || (
                offline.getName() == null
                        && !offline
                        .hasPlayedBefore()
        )) {
            return null;
        }

        return offline;
    }

    public String displayName(
            BountyRecord record
    ) {
        if (record == null) {
            return "Unknown";
        }

        OfflinePlayer target =
                Bukkit.getOfflinePlayer(
                        record.targetId()
                );
        String display =
                cleanDisplayName(
                        DisplayNames
                                .displayName(
                                        target
                                )
                );

        return display.isBlank()
                ? cleanDisplayName(
                record.targetUsername()
        )
                : display;
    }

    public String displayName(
            OfflinePlayer target
    ) {
        if (target == null) {
            return "Unknown";
        }

        String display =
                cleanDisplayName(
                        DisplayNames
                                .displayName(
                                        target
                                )
                );

        if (!display.isBlank()) {
            return display;
        }

        return Optional.ofNullable(
                        target.getName()
                )
                .map(
                        this::cleanDisplayName
                )
                .filter(
                        value ->
                                !value.isBlank()
                )
                .orElse(
                        target.getUniqueId()
                                .toString()
                );
    }

    public boolean isOnline(
            BountyRecord record
    ) {
        if (record == null) {
            return false;
        }

        Player online =
                Bukkit.getPlayer(
                        record.targetId()
                );

        return online != null
                && online.isOnline();
    }

    public boolean matches(
            BountyRecord record,
            String rawQuery
    ) {
        if (record == null
                || rawQuery == null
                || rawQuery.isBlank()) {
            return true;
        }

        String query =
                rawQuery
                        .trim()
                        .toLowerCase(
                                Locale.ROOT
                        );
        String username =
                record.targetUsername()
                        .toLowerCase(
                                Locale.ROOT
                        );
        String display =
                displayName(record)
                        .toLowerCase(
                                Locale.ROOT
                        );

        return username.contains(query)
                || display.contains(query);
    }

    public String displaySearchLabel(
            String query
    ) {
        if (query == null
                || query.isBlank()) {
            return "";
        }

        for (BountyRecord record
                : repository.listAll()) {
            if (record.targetUsername()
                    .equalsIgnoreCase(query)
                    || displayName(record)
                    .equalsIgnoreCase(query)) {
                return displayName(record);
            }
        }

        return cleanDisplayName(query);
    }

    public List<String> targetSuggestions() {
        Set<String> suggestions =
                new LinkedHashSet<>();

        for (BountyRecord record
                : repository.listAll()) {
            suggestions.add(
                    displayName(record)
            );
        }

        List<String> result =
                new ArrayList<>(
                        suggestions
                );
        result.sort(
                String.CASE_INSENSITIVE_ORDER
        );

        return List.copyOf(result);
    }

    public boolean hasMatches(
            BountySortMode sortMode,
            String query
    ) {
        for (BountyRecord record
                : list(sortMode)) {
            if (matches(
                    record,
                    query
            )) {
                return true;
            }
        }

        return false;
    }

    public String ageText(
            long timestamp
    ) {
        long elapsed =
                Math.max(
                        0L,
                        System.currentTimeMillis()
                                - timestamp
                );
        long days =
                TimeUnit.MILLISECONDS
                        .toDays(elapsed);

        if (days > 0L) {
            return days + "d ago";
        }

        long hours =
                TimeUnit.MILLISECONDS
                        .toHours(elapsed);

        if (hours > 0L) {
            return hours + "h ago";
        }

        long minutes =
                TimeUnit.MILLISECONDS
                        .toMinutes(elapsed);

        if (minutes > 0L) {
            return minutes + "m ago";
        }

        return "Just now";
    }

    private PlaceResult placeResult(
            PlaceStatus status,
            long amountCents,
            OfflinePlayer target
    ) {
        return new PlaceResult(
                status,
                amountCents,
                target == null
                        ? 0L
                        : getAmount(
                        target.getUniqueId()
                )
        );
    }

    private ClaimResult claimResult(
            ClaimStatus status
    ) {
        return new ClaimResult(
                status,
                0L,
                0L
        );
    }

    private boolean canReceive(
            EconomyService economy,
            UUID playerId,
            long cents
    ) {
        try {
            long updatedBalance =
                    Math.addExact(
                            economy.getBalanceCents(
                                    playerId
                            ),
                            cents
                    );
            return updatedBalance >= 0L;
        } catch (
                ArithmeticException exception
        ) {
            return false;
        }
    }

    private boolean sameTeamClaimBlocked(
            Player killer,
            Player target
    ) {
        if (!core.getConfig()
                .getBoolean(
                        "bounty.claim.block-same-team",
                        true
                )) {
            return false;
        }

        TeamService teamService =
                TeamsModule.teamService();

        if (teamService == null) {
            return false;
        }

        TeamRecord killerTeam =
                teamService.getTeamByPlayer(
                        killer.getUniqueId()
                );
        TeamRecord targetTeam =
                teamService.getTeamByPlayer(
                        target.getUniqueId()
                );

        return killerTeam != null
                && targetTeam != null
                && killerTeam.teamId()
                .equals(
                        targetTeam.teamId()
                );
    }

    private boolean claimAllowed(
            World world
    ) {
        if (world == null) {
            return false;
        }

        List<String> configured =
                core.getConfig()
                        .getStringList(
                                "bounty.claim.allowed-worlds"
                        );
        List<String> source =
                configured.isEmpty()
                        ? DEFAULT_CLAIM_WORLDS
                        : configured;
        String current =
                world.getName()
                        .toLowerCase(
                                Locale.ROOT
                        );

        for (String worldName : source) {
            if (worldName != null
                    && current.equals(
                    worldName.trim()
                            .toLowerCase(
                                    Locale.ROOT
                            )
            )) {
                return true;
            }
        }

        return false;
    }

    private String stableUsername(
            OfflinePlayer target
    ) {
        String username =
                target.getName();

        if (username != null
                && !username.isBlank()) {
            return username;
        }

        BountyRecord existing =
                get(
                        target.getUniqueId()
                );

        return existing == null
                ? target.getUniqueId()
                .toString()
                : existing
                .targetUsername();
    }

    private String cleanDisplayName(
            String value
    ) {
        if (value == null) {
            return "";
        }

        return TextColor.strip(value)
                .trim();
    }

    private BountyDatabaseMirror.Snapshot
    snapshot(
            BountyRecord record
    ) {
        return new BountyDatabaseMirror
                .Snapshot(
                record.targetId(),
                record.targetUsername(),
                displayName(record),
                record.amountCents(),
                taxedPayout(
                        record.amountCents()
                ),
                isOnline(record),
                record.lastUpdated()
        );
    }

    private List<BountyDatabaseMirror.Snapshot>
    snapshots() {
        List<BountyDatabaseMirror.Snapshot>
                result =
                new ArrayList<>();

        for (BountyRecord record
                : repository.listAll()) {
            result.add(
                    snapshot(record)
            );
        }

        return List.copyOf(result);
    }

    private void mirrorUpsert(
            BountyRecord record
    ) {
        BountyDatabaseMirror mirror =
                databaseMirror;

        if (mirror != null) {
            mirror.upsert(
                    snapshot(record)
            );
        }
    }

    private void mirrorDelete(
            UUID targetId
    ) {
        BountyDatabaseMirror mirror =
                databaseMirror;

        if (mirror != null) {
            mirror.delete(targetId);
        }
    }

    private void startDatabaseMirror() {
        BountyDatabaseMirror mirror =
                new BountyDatabaseMirror(core);

        databaseMirror = mirror;
        mirror.start();

        if (!mirror.enabled()) {
            return;
        }

        mirror.reconcile(
                snapshots()
        );

        long syncTicks =
                mirror.syncSeconds()
                        * 20L;

        databaseMirrorTask =
                core.getServer()
                        .getScheduler()
                        .runTaskTimer(
                                core,
                                this::reconcileDatabaseMirror,
                                syncTicks,
                                syncTicks
                        );
    }

    private synchronized void reconcileDatabaseMirror() {
        BountyDatabaseMirror mirror =
                databaseMirror;

        if (mirror == null
                || !mirror.enabled()) {
            return;
        }

        mirror.reconcile(
                snapshots()
        );
    }

    private void stopDatabaseMirror() {
        BukkitTask task =
                databaseMirrorTask;

        if (task != null) {
            task.cancel();
            databaseMirrorTask = null;
        }

        BountyDatabaseMirror mirror =
                databaseMirror;

        if (mirror != null) {
            databaseMirror = null;
            mirror.shutdown(
                    snapshots()
            );
        }
    }
}
