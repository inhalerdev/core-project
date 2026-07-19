package net.mineacle.core.economy.service;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.format.MoneyFormatter;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.economy.storage.YamlEconomyRepository;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

public final class EconomyService {

    public enum PaymentStatus {
        SUCCESS,
        DISABLED,
        PAY_DISABLED,
        INVALID_TARGET,
        SELF_PAYMENT,
        INVALID_AMOUNT,
        BELOW_MINIMUM,
        INSUFFICIENT_FUNDS,
        RECIPIENT_BALANCE_LIMIT
    }

    public enum AdjustmentAction {
        GIVE,
        TAKE,
        SET,
        RESET
    }

    public record BulkResult(
            int matched,
            int changed,
            int skipped,
            boolean persisted,
            Set<UUID> changedPlayerIds
    ) {
    }

    private static final long SAVE_WARNING_INTERVAL_NANOS =
            30_000_000_000L;

    private final Core core;
    private final YamlEconomyRepository repository;
    private final Map<UUID, Long> balances = new HashMap<>();
    private final Map<UUID, OfflinePaymentNotice> offlinePayments =
            new HashMap<>();

    private boolean active = true;
    private boolean dirty;
    private long lastSaveWarningNanos;

    public EconomyService(
            Core core,
            YamlEconomyRepository repository
    ) {
        this.core = core;
        this.repository = repository;

        YamlEconomyRepository.Snapshot snapshot =
                repository.load();

        balances.putAll(snapshot.balances());

        for (Map.Entry<UUID, OfflinePaymentNotice> entry
                : snapshot.offlinePayments().entrySet()) {
            offlinePayments.put(
                    entry.getKey(),
                    entry.getValue().copy()
            );
        }
    }

    public boolean enabled() {
        return active && core.getConfig().getBoolean(
                "economy.enabled",
                true
        );
    }

    public boolean payEnabled() {
        return enabled()
                && core.getConfig().getBoolean(
                "economy.pay.enabled",
                true
        );
    }

    public long startingBalanceCents() {
        Object configured = core.getConfig().get(
                "economy.starting-balance",
                0
        );
        long parsed = MoneyFormatter.parseNonNegativeCents(
                String.valueOf(configured)
        );

        return parsed >= 0L ? parsed : 0L;
    }

    public long minimumPaymentCents() {
        Object configured = core.getConfig().get(
                "economy.pay.minimum",
                "0.01"
        );
        long parsed = MoneyFormatter.parsePositiveCents(
                String.valueOf(configured)
        );

        return parsed > 0L ? parsed : 1L;
    }

    public synchronized long getBalanceCents(UUID playerId) {
        if (playerId == null) {
            return 0L;
        }

        return balances.getOrDefault(
                playerId,
                startingBalanceCents()
        );
    }

    public String format(long cents) {
        return MoneyFormatter.moneyFromCents(cents);
    }

    public boolean has(UUID playerId, long cents) {
        return cents >= 0L
                && getBalanceCents(playerId) >= cents;
    }

    public synchronized boolean hasAccount(UUID playerId) {
        return playerId != null && balances.containsKey(playerId);
    }

    public synchronized Set<UUID> accountIds() {
        return Set.copyOf(balances.keySet());
    }

    public synchronized boolean ensureAccount(UUID playerId) {
        if (playerId == null) {
            return false;
        }

        if (balances.containsKey(playerId)) {
            return true;
        }

        balances.put(playerId, startingBalanceCents());
        changed();
        return true;
    }

    public void setBalance(UUID playerId, long cents) {
        trySetBalance(playerId, cents);
    }

    public synchronized boolean trySetBalance(
            UUID playerId,
            long cents
    ) {
        if (playerId == null || cents < 0L) {
            return false;
        }

        balances.put(playerId, cents);
        changed();
        return true;
    }

    public void give(UUID playerId, long cents) {
        tryGive(playerId, cents);
    }

    public synchronized boolean tryGive(
            UUID playerId,
            long cents
    ) {
        if (playerId == null || cents <= 0L) {
            return false;
        }

        long updated;

        try {
            updated = Math.addExact(
                    getBalanceCents(playerId),
                    cents
            );
        } catch (ArithmeticException exception) {
            return false;
        }

        balances.put(playerId, updated);
        changed();
        return true;
    }

    public synchronized boolean take(
            UUID playerId,
            long cents
    ) {
        if (playerId == null || cents <= 0L) {
            return false;
        }

        long current = getBalanceCents(playerId);

        if (current < cents) {
            return false;
        }

        balances.put(playerId, current - cents);
        changed();
        return true;
    }

    /**
     * Atomically moves money between two Mineacle accounts with one
     * in-memory mutation and one economy snapshot write.
     */
    public synchronized boolean transferBalance(
            UUID senderId,
            UUID targetId,
            long cents
    ) {
        if (!enabled()
                || senderId == null
                || targetId == null
                || senderId.equals(targetId)
                || cents <= 0L) {
            return false;
        }

        long senderBalance = getBalanceCents(senderId);

        if (senderBalance < cents) {
            return false;
        }

        long recipientBalance;

        try {
            recipientBalance = Math.addExact(
                    getBalanceCents(targetId),
                    cents
            );
        } catch (ArithmeticException exception) {
            return false;
        }

        balances.put(senderId, senderBalance - cents);
        balances.put(targetId, recipientBalance);
        changed();
        return true;
    }

    public boolean pay(
            Player sender,
            OfflinePlayer target,
            long cents
    ) {
        PaymentStatus status = transfer(
                sender,
                target,
                cents
        );

        if (status != PaymentStatus.SUCCESS) {
            sendPaymentError(sender, status);
            return false;
        }

        String amount = format(cents);
        String senderName = DisplayNames.displayName(sender);
        String targetName = DisplayNames.displayName(target);

        sendChatAndActionBar(
                sender,
                "&#bbbbbbYou paid &#bbbbbb"
                        + targetName
                        + " &c-"
                        + amount,
                "&c-" + amount
                        + " &#bbbbbbto &#bbbbbb"
                        + targetName
        );
        SoundService.economyPay(sender, core);

        Player onlineTarget = target.getPlayer();

        if (onlineTarget != null && onlineTarget.isOnline()) {
            sendOnlinePaidMessage(
                    onlineTarget,
                    cents,
                    senderName
            );
            SoundService.economyReceive(onlineTarget, core);
        }

        return true;
    }

    public synchronized PaymentStatus transfer(
            Player sender,
            OfflinePlayer target,
            long cents
    ) {
        if (!enabled()) {
            return PaymentStatus.DISABLED;
        }

        if (!payEnabled()) {
            return PaymentStatus.PAY_DISABLED;
        }

        if (sender == null || target == null) {
            return PaymentStatus.INVALID_TARGET;
        }

        UUID senderId = sender.getUniqueId();
        UUID targetId = target.getUniqueId();

        if (senderId.equals(targetId)) {
            return PaymentStatus.SELF_PAYMENT;
        }

        if (cents <= 0L) {
            return PaymentStatus.INVALID_AMOUNT;
        }

        if (cents < minimumPaymentCents()) {
            return PaymentStatus.BELOW_MINIMUM;
        }

        long senderBalance = getBalanceCents(senderId);

        if (senderBalance < cents) {
            return PaymentStatus.INSUFFICIENT_FUNDS;
        }

        long recipientBalance;

        try {
            recipientBalance = Math.addExact(
                    getBalanceCents(targetId),
                    cents
            );
        } catch (ArithmeticException exception) {
            return PaymentStatus.RECIPIENT_BALANCE_LIMIT;
        }

        balances.put(senderId, senderBalance - cents);
        balances.put(targetId, recipientBalance);

        Player onlineTarget = target.getPlayer();

        if (onlineTarget == null || !onlineTarget.isOnline()) {
            OfflinePaymentNotice notice =
                    offlinePayments.computeIfAbsent(
                            targetId,
                            ignored -> new OfflinePaymentNotice(
                                    0L,
                                    Set.of()
                            )
                    );

            notice.tryAdd(
                    cents,
                    DisplayNames.displayName(sender)
            );
        }

        changed();
        return PaymentStatus.SUCCESS;
    }

    public void sendOnlinePaidMessage(
            Player player,
            long cents
    ) {
        sendOnlinePaidMessage(player, cents, null);
    }

    public void sendOnlinePaidMessage(
            Player player,
            long cents,
            String senderName
    ) {
        if (player == null || !player.isOnline() || cents <= 0L) {
            return;
        }

        String amount = format(cents);

        if (senderName == null || senderName.isBlank()) {
            sendChatAndActionBar(
                    player,
                    "&#bbbbbbYou received &a+" + amount,
                    "&a+" + amount
            );
            return;
        }

        String cleanSender = TextColor.strip(senderName);

        sendChatAndActionBar(
                player,
                "&#bbbbbbYou received &a+"
                        + amount
                        + " &#bbbbbbfrom &#bbbbbb"
                        + cleanSender,
                "&a+" + amount
                        + " &#bbbbbbfrom &#bbbbbb"
                        + cleanSender
        );
    }

    public synchronized void addOfflinePayment(
            UUID targetId,
            long cents,
            String senderName
    ) {
        if (targetId == null || cents <= 0L) {
            return;
        }

        OfflinePaymentNotice notice =
                offlinePayments.computeIfAbsent(
                        targetId,
                        ignored -> new OfflinePaymentNotice(
                                0L,
                                Set.of()
                        )
                );

        if (notice.tryAdd(
                cents,
                senderName
        )) {
            changed();
        }
    }

    public synchronized OfflinePaymentNotice consumeOfflinePayment(
            UUID playerId
    ) {
        if (playerId == null) {
            return null;
        }

        OfflinePaymentNotice notice =
                offlinePayments.remove(playerId);

        if (notice != null) {
            changed();
            return notice.copy();
        }

        return null;
    }

    public synchronized List<Map.Entry<UUID, Long>> topBalances(
            int limit
    ) {
        List<Map.Entry<UUID, Long>> entries = new ArrayList<>();

        for (Map.Entry<UUID, Long> entry : balances.entrySet()) {
            Long cents = entry.getValue();

            if (cents == null || cents <= 0L) {
                continue;
            }

            entries.add(Map.entry(entry.getKey(), cents));
        }

        entries.sort(
                Map.Entry
                        .<UUID, Long>comparingByValue()
                        .reversed()
                        .thenComparing(
                                entry -> entry.getKey().toString()
                        )
        );

        if (limit > 0 && entries.size() > limit) {
            return List.copyOf(entries.subList(0, limit));
        }

        return List.copyOf(entries);
    }

    public long parseAmountToCents(String raw) {
        return MoneyFormatter.parsePositiveCents(raw);
    }

    public long parseNonNegativeAmountToCents(String raw) {
        return MoneyFormatter.parseNonNegativeCents(raw);
    }

    public long amountToCents(BigDecimal amount) {
        if (amount == null || amount.signum() < 0) {
            return -1L;
        }

        try {
            return amount
                    .multiply(BigDecimal.valueOf(100L))
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValueExact();
        } catch (ArithmeticException exception) {
            return -1L;
        }
    }

    public void reset(UUID playerId) {
        trySetBalance(playerId, startingBalanceCents());
    }

    public synchronized BulkResult applyBulk(
            Collection<OfflinePlayer> rawTargets,
            AdjustmentAction action,
            long cents,
            String offlineNoticeSender
    ) {
        if (rawTargets == null || action == null) {
            return new BulkResult(
                    0,
                    0,
                    0,
                    !dirty,
                    Set.of()
            );
        }

        Map<UUID, OfflinePlayer> targets = new LinkedHashMap<>();

        for (OfflinePlayer target : rawTargets) {
            if (target != null) {
                targets.putIfAbsent(
                        target.getUniqueId(),
                        target
                );
            }
        }

        int changedCount = 0;
        int skipped = 0;
        Set<UUID> changedIds = new LinkedHashSet<>();

        for (OfflinePlayer target : targets.values()) {
            UUID playerId = target.getUniqueId();
            long current = getBalanceCents(playerId);
            long updated;

            switch (action) {
                case GIVE -> {
                    if (cents <= 0L) {
                        skipped++;
                        continue;
                    }

                    try {
                        updated = Math.addExact(current, cents);
                    } catch (ArithmeticException exception) {
                        skipped++;
                        continue;
                    }
                }
                case TAKE -> {
                    if (cents <= 0L || current < cents) {
                        skipped++;
                        continue;
                    }

                    updated = current - cents;
                }
                case SET -> {
                    if (cents < 0L) {
                        skipped++;
                        continue;
                    }

                    updated = cents;
                }
                case RESET -> updated = startingBalanceCents();
                default -> throw new IllegalStateException(
                        "Unexpected adjustment action"
                );
            }

            if (balances.containsKey(playerId)
                    && current == updated) {
                continue;
            }

            balances.put(playerId, updated);
            changedCount++;
            changedIds.add(playerId);

            if (action == AdjustmentAction.GIVE) {
                Player online = target.getPlayer();

                if (online == null || !online.isOnline()) {
                    OfflinePaymentNotice notice =
                            offlinePayments.computeIfAbsent(
                                    playerId,
                                    ignored ->
                                            new OfflinePaymentNotice(
                                                    0L,
                                                    Set.of()
                                            )
                            );

                    notice.tryAdd(
                            cents,
                            offlineNoticeSender
                    );
                }
            }
        }

        if (changedCount > 0) {
            changed();
        } else if (dirty) {
            persistBestEffort();
        }

        return new BulkResult(
                targets.size(),
                changedCount,
                skipped,
                !dirty,
                Set.copyOf(changedIds)
        );
    }

    public synchronized void flushIfDirty() {
        if (dirty) {
            persistBestEffort();
        }
    }

    public synchronized void save() {
        dirty = true;
        persistBestEffort();
    }

    public synchronized void shutdown() {
        flushIfDirty();
        active = false;
    }

    private void changed() {
        dirty = true;
        persistBestEffort();
    }

    private void persistBestEffort() {
        if (!dirty) {
            return;
        }

        try {
            repository.save(balances, offlinePayments);
            dirty = false;
        } catch (IOException exception) {
            long now = System.nanoTime();

            if (now - lastSaveWarningNanos
                    >= SAVE_WARNING_INTERVAL_NANOS) {
                lastSaveWarningNanos = now;
                core.getLogger().log(
                        Level.SEVERE,
                        "Could not save economy.yml — the complete "
                                + "transaction remains in memory and "
                                + "will be retried",
                        exception
                );
            }
        }
    }

    private void sendPaymentError(
            Player sender,
            PaymentStatus status
    ) {
        if (sender == null) {
            return;
        }

        String message = switch (status) {
            case DISABLED ->
                    "&cEconomy is currently disabled";
            case PAY_DISABLED ->
                    "&cPlayer payments are currently disabled";
            case INVALID_TARGET ->
                    "&cThat player could not be found";
            case SELF_PAYMENT ->
                    "&cYou cannot pay yourself";
            case INVALID_AMOUNT ->
                    "&cEnter a valid amount";
            case BELOW_MINIMUM ->
                    "&cMinimum payment is &a"
                            + format(minimumPaymentCents());
            case INSUFFICIENT_FUNDS ->
                    "&cYou do not have enough money";
            case RECIPIENT_BALANCE_LIMIT ->
                    "&cThat player's balance is too high "
                            + "to receive this payment";
            case SUCCESS -> "";
        };

        sender.sendMessage(TextColor.color(message));
        SoundService.guiError(sender, core);
    }

    private void sendChatAndActionBar(
            Player player,
            String chat,
            String actionBar
    ) {
        if (player == null || !player.isOnline()) {
            return;
        }

        player.sendMessage(TextColor.color(chat));
        player.sendActionBar(component(actionBar));
    }

    private Component component(String message) {
        return LegacyComponentSerializer.legacySection()
                .deserialize(TextColor.color(message));
    }
}
