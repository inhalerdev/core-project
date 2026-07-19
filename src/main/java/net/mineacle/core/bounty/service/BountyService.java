package net.mineacle.core.bounty;

import net.mineacle.core.Core;
import net.mineacle.core.common.format.MoneyFormatter;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.economy.EconomyModule;
import net.mineacle.core.economy.service.EconomyService;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;

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
import java.util.logging.Level;

public final class BountyService {

    private static final List<String> DEFAULT_CLAIM_WORLDS = List.of(
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
        ECONOMY_UNAVAILABLE,
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

    public BountyService(
            Core core,
            BountyRepository repository
    ) {
        this.core = core;
        this.repository = repository;
    }

    public void load() throws IOException {
        repository.initialize();
        initialized = true;
    }

    public void reload() throws IOException {
        repository.initialize();
        initialized = true;
    }

    public void shutdown() {
        if (!initialized) {
            return;
        }

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
        return core.getConfig().getBoolean(
                "bounty.enabled",
                true
        );
    }

    public List<BountyRecord> list(BountySortMode sortMode) {
        BountySortMode safeSort = sortMode == null
                ? BountySortMode.AMOUNT
                : sortMode;

        List<BountyRecord> records =
                new ArrayList<>(repository.listAll());

        Comparator<BountyRecord> comparator = switch (safeSort) {
            case AMOUNT -> Comparator
                    .comparingLong(BountyRecord::amountCents)
                    .reversed()
                    .thenComparing(
                            this::displayName,
                            String.CASE_INSENSITIVE_ORDER
                    );
            case RECENT -> Comparator
                    .comparingLong(BountyRecord::lastUpdated)
                    .reversed()
                    .thenComparing(
                            this::displayName,
                            String.CASE_INSENSITIVE_ORDER
                    );
            case NAME -> Comparator.comparing(
                    this::displayName,
                    String.CASE_INSENSITIVE_ORDER
            );
        };

        records.sort(comparator);
        return List.copyOf(records);
    }

    public BountyRecord get(UUID targetId) {
        if (targetId == null) {
            return null;
        }

        return repository.find(targetId).orElse(null);
    }

    public long getAmount(UUID targetId) {
        BountyRecord record = get(targetId);
        return record == null ? 0L : record.amountCents();
    }

    public synchronized PlaceResult placeDetailed(
            Player setter,
            OfflinePlayer target,
            long amountCents
    ) {
        if (!enabled()) {
            return new PlaceResult(
                    PlaceStatus.DISABLED,
                    amountCents,
                    getAmount(target == null
                            ? null
                            : target.getUniqueId())
            );
        }

        if (setter == null || target == null) {
            return new PlaceResult(
                    PlaceStatus.INVALID_TARGET,
                    amountCents,
                    0L
            );
        }

        if (setter.getUniqueId().equals(target.getUniqueId())) {
            return new PlaceResult(
                    PlaceStatus.SELF_TARGET,
                    amountCents,
                    getAmount(target.getUniqueId())
            );
        }

        if (amountCents <= 0L) {
            return new PlaceResult(
                    PlaceStatus.INVALID_AMOUNT,
                    amountCents,
                    getAmount(target.getUniqueId())
            );
        }

        long minimum = minimumCents();

        if (amountCents < minimum) {
            return new PlaceResult(
                    PlaceStatus.BELOW_MINIMUM,
                    amountCents,
                    getAmount(target.getUniqueId())
            );
        }

        long existing = getAmount(target.getUniqueId());
        long combined;

        try {
            combined = Math.addExact(existing, amountCents);
        } catch (ArithmeticException exception) {
            return new PlaceResult(
                    PlaceStatus.ABOVE_MAXIMUM,
                    amountCents,
                    existing
            );
        }

        long maximum = maximumCents();

        if (maximum > 0L && combined > maximum) {
            return new PlaceResult(
                    PlaceStatus.ABOVE_MAXIMUM,
                    amountCents,
                    existing
            );
        }

        EconomyService economy = EconomyModule.economyService();

        if (economy == null) {
            return new PlaceResult(
                    PlaceStatus.ECONOMY_UNAVAILABLE,
                    amountCents,
                    existing
            );
        }

        if (!economy.take(setter.getUniqueId(), amountCents)) {
            return new PlaceResult(
                    PlaceStatus.NOT_ENOUGH_MONEY,
                    amountCents,
                    existing
            );
        }

        String username = target.getName();

        if (username == null || username.isBlank()) {
            BountyRecord current = get(target.getUniqueId());
            username = current == null
                    ? target.getUniqueId().toString()
                    : current.targetUsername();
        }

        BountyRecord updated = new BountyRecord(
                target.getUniqueId(),
                username,
                combined,
                System.currentTimeMillis()
        );

        try {
            repository.save(updated);
        } catch (IOException exception) {
            economy.give(setter.getUniqueId(), amountCents);
            core.getLogger().log(
                    Level.SEVERE,
                    "Could not persist bounty for "
                            + target.getUniqueId(),
                    exception
            );

            return new PlaceResult(
                    PlaceStatus.STORAGE_ERROR,
                    amountCents,
                    existing
            );
        }

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
                || killer.getUniqueId().equals(target.getUniqueId())) {
            return new ClaimResult(
                    ClaimStatus.NO_BOUNTY,
                    0L,
                    0L
            );
        }

        if (!claimAllowed(target.getWorld())) {
            return new ClaimResult(
                    ClaimStatus.BLOCKED_WORLD,
                    0L,
                    0L
            );
        }

        BountyRecord record = get(target.getUniqueId());

        if (record == null || record.amountCents() <= 0L) {
            return new ClaimResult(
                    ClaimStatus.NO_BOUNTY,
                    0L,
                    0L
            );
        }

        EconomyService economy = EconomyModule.economyService();

        if (economy == null) {
            return new ClaimResult(
                    ClaimStatus.ECONOMY_UNAVAILABLE,
                    record.amountCents(),
                    0L
            );
        }

        try {
            if (!repository.delete(target.getUniqueId())) {
                return new ClaimResult(
                        ClaimStatus.NO_BOUNTY,
                        0L,
                        0L
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

        long payout = taxedPayout(record.amountCents());

        if (payout > 0L) {
            economy.give(killer.getUniqueId(), payout);
        }

        return new ClaimResult(
                ClaimStatus.SUCCESS,
                record.amountCents(),
                payout
        );
    }

    public synchronized RemoveResult removeDetailed(UUID targetId) {
        if (targetId == null) {
            return new RemoveResult(
                    RemoveStatus.NOT_FOUND,
                    0L
            );
        }

        BountyRecord record = get(targetId);

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
                    "Could not remove bounty for " + targetId,
                    exception
            );

            return new RemoveResult(
                    RemoveStatus.STORAGE_ERROR,
                    0L
            );
        }

        return new RemoveResult(
                RemoveStatus.SUCCESS,
                record.amountCents()
        );
    }


    /**
     * Compatibility method retained for callers that only need success/fail.
     */
    public boolean place(
            Player setter,
            OfflinePlayer target,
            long amountCents
    ) {
        return placeDetailed(
                setter,
                target,
                amountCents
        ).status() == PlaceStatus.SUCCESS;
    }

    /**
     * Compatibility method retained for older claim listeners.
     */
    public long claim(Player killer, Player target) {
        ClaimResult result = claimDetailed(killer, target);
        return result.status() == ClaimStatus.SUCCESS
                ? result.payoutCents()
                : 0L;
    }

    /**
     * Compatibility method retained for older admin integrations.
     */
    public long remove(UUID targetId) {
        RemoveResult result = removeDetailed(targetId);
        return result.status() == RemoveStatus.SUCCESS
                ? result.removedCents()
                : 0L;
    }

    /**
     * Directly adds bounty value without charging a setter.
     * Intended for trusted internal/admin integrations.
     */
    public synchronized void add(
            OfflinePlayer target,
            long amountCents
    ) {
        if (target == null || amountCents <= 0L) {
            return;
        }

        long current = getAmount(target.getUniqueId());
        long combined;

        try {
            combined = Math.addExact(current, amountCents);
        } catch (ArithmeticException exception) {
            return;
        }

        long maximum = maximumCents();

        if (maximum > 0L && combined > maximum) {
            return;
        }

        String username = target.getName();

        if (username == null || username.isBlank()) {
            BountyRecord existing = get(target.getUniqueId());
            username = existing == null
                    ? target.getUniqueId().toString()
                    : existing.targetUsername();
        }

        try {
            repository.save(new BountyRecord(
                    target.getUniqueId(),
                    username,
                    combined,
                    System.currentTimeMillis()
            ));
        } catch (IOException exception) {
            core.getLogger().log(
                    Level.SEVERE,
                    "Could not directly add bounty for "
                            + target.getUniqueId(),
                    exception
            );
        }
    }

    /**
     * Compatibility flush method retained for module integrations.
     */
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

    public long taxedPayout(long amountCents) {
        BigDecimal configured = BigDecimal.valueOf(
                core.getConfig().getDouble(
                        "bounty.tax-percent",
                        -1.0D
                )
        );

        if (configured.signum() <= 0) {
            return Math.max(0L, amountCents);
        }

        BigDecimal tax = configured.min(
                BigDecimal.valueOf(100L)
        );
        BigDecimal kept = BigDecimal.ONE.subtract(
                tax.divide(
                        BigDecimal.valueOf(100L),
                        8,
                        RoundingMode.HALF_UP
                )
        );

        return BigDecimal.valueOf(Math.max(0L, amountCents))
                .multiply(kept)
                .setScale(0, RoundingMode.HALF_UP)
                .longValue();
    }

    public long minimumCents() {
        String raw = core.getConfig().getString(
                "bounty.minimum",
                "1"
        );
        long parsed = MoneyFormatter.parsePositiveCents(raw);
        return parsed > 0L ? parsed : 100L;
    }

    public long maximumCents() {
        String raw = core.getConfig().getString(
                "bounty.maximum",
                "999B"
        );

        if (raw == null
                || raw.equalsIgnoreCase("-1")
                || raw.equalsIgnoreCase("none")
                || raw.equalsIgnoreCase("unlimited")) {
            return -1L;
        }

        long parsed = MoneyFormatter.parsePositiveCents(raw);

        if (parsed > 0L) {
            return parsed;
        }

        return MoneyFormatter.parsePositiveCents("999B");
    }

    public String format(long cents) {
        return MoneyFormatter.moneyFromCents(cents);
    }

    public long parseAmount(String raw) {
        return MoneyFormatter.parsePositiveCents(raw);
    }

    public boolean wouldExceedMaximum(
            UUID targetId,
            long contributionCents
    ) {
        if (targetId == null || contributionCents <= 0L) {
            return false;
        }

        long maximum = maximumCents();

        if (maximum <= 0L) {
            return false;
        }

        try {
            return Math.addExact(
                    getAmount(targetId),
                    contributionCents
            ) > maximum;
        } catch (ArithmeticException exception) {
            return true;
        }
    }

    public OfflinePlayer resolveTarget(String input) {
        Player online = DisplayNames.resolveOnline(input);

        if (online != null) {
            return online;
        }

        OfflinePlayer offline = DisplayNames.resolveOffline(input);

        if (offline == null
                || (offline.getName() == null
                && !offline.hasPlayedBefore())) {
            return null;
        }

        return offline;
    }

    public String displayName(BountyRecord record) {
        if (record == null) {
            return "Unknown";
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(
                record.targetId()
        );
        String displayName = DisplayNames.displayName(target);

        if (displayName == null || displayName.isBlank()) {
            return record.targetUsername();
        }

        return displayName;
    }

    public String displayName(OfflinePlayer target) {
        if (target == null) {
            return "Unknown";
        }

        String displayName = DisplayNames.displayName(target);

        if (displayName == null || displayName.isBlank()) {
            return Optional.ofNullable(target.getName())
                    .orElse(target.getUniqueId().toString());
        }

        return displayName;
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

        String query = rawQuery.toLowerCase(Locale.ROOT);
        OfflinePlayer target = Bukkit.getOfflinePlayer(
                record.targetId()
        );
        String username = target.getName() == null
                ? record.targetUsername()
                : target.getName();
        String displayName = displayName(record);

        return username.toLowerCase(Locale.ROOT).contains(query)
                || displayName.toLowerCase(Locale.ROOT)
                .contains(query)
                || record.targetUsername()
                .toLowerCase(Locale.ROOT)
                .contains(query);
    }

    public String displaySearchLabel(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }

        for (BountyRecord record : repository.listAll()) {
            OfflinePlayer target = Bukkit.getOfflinePlayer(
                    record.targetId()
            );
            String username = target.getName() == null
                    ? record.targetUsername()
                    : target.getName();
            String displayName = displayName(record);

            if (username.equalsIgnoreCase(query)
                    || displayName.equalsIgnoreCase(query)) {
                return displayName;
            }
        }

        return query;
    }

    public List<String> targetSuggestions() {
        Set<String> suggestions = new LinkedHashSet<>();

        for (BountyRecord record : repository.listAll()) {
            suggestions.add(displayName(record));
        }

        List<String> result = new ArrayList<>(suggestions);
        result.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(result);
    }

    public boolean hasMatches(
            BountySortMode sortMode,
            String query
    ) {
        for (BountyRecord record : list(sortMode)) {
            if (matches(record, query)) {
                return true;
            }
        }

        return false;
    }

    private boolean claimAllowed(World world) {
        if (world == null) {
            return false;
        }

        List<String> configured = core.getConfig().getStringList(
                "bounty.claim.allowed-worlds"
        );
        List<String> source = configured.isEmpty()
                ? DEFAULT_CLAIM_WORLDS
                : configured;
        String current = world.getName().toLowerCase(Locale.ROOT);

        for (String worldName : source) {
            if (worldName != null
                    && current.equals(
                    worldName.trim().toLowerCase(Locale.ROOT)
            )) {
                return true;
            }
        }

        return false;
    }
}
