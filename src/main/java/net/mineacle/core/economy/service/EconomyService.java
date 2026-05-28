package net.mineacle.core.economy.service;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.format.MoneyFormatter;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class EconomyService {

    private final Core core;
    private final Map<UUID, Long> balances = new HashMap<>();
    private final Map<UUID, OfflinePaymentNotice> offlinePayments = new HashMap<>();

    public EconomyService(Core core) {
        this.core = core;
        load();
    }

    public long startingBalanceCents() {
        return amountToCents(BigDecimal.valueOf(core.getConfig().getDouble("economy.starting-balance", 0.0)));
    }

    public long getBalanceCents(UUID playerId) {
        return balances.getOrDefault(playerId, startingBalanceCents());
    }

    public String format(long cents) {
        return MoneyFormatter.moneyFromCents(cents);
    }

    public boolean has(UUID playerId, long cents) {
        return getBalanceCents(playerId) >= cents;
    }

    public void setBalance(UUID playerId, long cents) {
        long safeCents = Math.max(0L, cents);

        if (safeCents <= 0L) {
            balances.remove(playerId);
        } else {
            balances.put(playerId, safeCents);
        }

        save();
    }

    public void give(UUID playerId, long cents) {
        if (cents <= 0L) {
            return;
        }

        setBalance(playerId, getBalanceCents(playerId) + cents);
    }

    public boolean take(UUID playerId, long cents) {
        if (cents <= 0L) {
            return false;
        }

        long current = getBalanceCents(playerId);

        if (current < cents) {
            return false;
        }

        setBalance(playerId, current - cents);
        return true;
    }

    public boolean pay(Player sender, OfflinePlayer target, long cents) {
        if (sender == null || target == null) {
            return false;
        }

        if (sender.getUniqueId().equals(target.getUniqueId())) {
            sender.sendMessage(message("economy.cannot-pay-self"));
            SoundService.guiError(sender, core);
            return false;
        }

        if (cents < 1L) {
            sender.sendMessage(message("economy.invalid-amount"));
            SoundService.guiError(sender, core);
            return false;
        }

        if (!has(sender.getUniqueId(), cents)) {
            sender.sendMessage(message("economy.not-enough-money"));
            SoundService.guiError(sender, core);
            return false;
        }

        take(sender.getUniqueId(), cents);
        give(target.getUniqueId(), cents);

        String amount = format(cents);
        String senderName = DisplayNames.displayName(sender);
        String targetName = DisplayNames.displayName(target);

        sendChatAndActionBar(sender,
                "&#bbbbbbYou paid &d" + targetName + " &a" + amount,
                "&c-" + amount + " &#bbbbbbto &d" + targetName);
        SoundService.economyPay(sender, core);

        Player onlineTarget = target.getPlayer();

        if (onlineTarget != null && onlineTarget.isOnline()) {
            sendOnlinePaidMessage(onlineTarget, cents, senderName);
            SoundService.economyReceive(onlineTarget, core);
        } else {
            addOfflinePayment(target.getUniqueId(), cents, "&d" + senderName);
        }

        save();
        return true;
    }

    public void sendOnlinePaidMessage(Player player, long cents) {
        sendOnlinePaidMessage(player, cents, null);
    }

    public void sendOnlinePaidMessage(Player player, long cents, String senderName) {
        String amount = format(cents);

        if (senderName == null || senderName.isBlank()) {
            sendChatAndActionBar(player,
                    "&#bbbbbbYou received &a" + amount,
                    "&a+" + amount);
            return;
        }

        sendChatAndActionBar(player,
                "&#bbbbbbYou received &a" + amount + " &#bbbbbbfrom &d" + senderName,
                "&a+" + amount + " &#bbbbbbfrom &d" + senderName);
    }

    public void addOfflinePayment(UUID targetId, long cents, String senderName) {
        if (cents <= 0L) {
            return;
        }

        OfflinePaymentNotice notice = offlinePayments.get(targetId);

        if (notice == null) {
            notice = new OfflinePaymentNotice(0L, new HashSet<>());
            offlinePayments.put(targetId, notice);
        }

        notice.add(cents, senderName);
        save();
    }

    public OfflinePaymentNotice consumeOfflinePayment(UUID playerId) {
        OfflinePaymentNotice notice = offlinePayments.remove(playerId);

        if (notice != null) {
            save();
        }

        return notice;
    }

    public List<Map.Entry<UUID, Long>> topBalances(int limit) {
        List<Map.Entry<UUID, Long>> entries = new ArrayList<>();

        for (Map.Entry<UUID, Long> entry : balances.entrySet()) {
            if (entry.getValue() == null || entry.getValue() < 1L) {
                continue;
            }

            entries.add(entry);
        }

        entries.sort(Map.Entry.comparingByValue(Comparator.reverseOrder()));

        if (limit <= 0 || entries.size() <= limit) {
            return entries;
        }

        return entries.subList(0, limit);
    }

    public long parseAmountToCents(String raw) {
        if (raw == null || raw.isBlank()) {
            return -1L;
        }

        String input = raw.trim().replace(",", "").replace("_", "").toLowerCase(Locale.ROOT);
        BigDecimal multiplier = BigDecimal.ONE;

        if (input.endsWith("k")) {
            multiplier = BigDecimal.valueOf(1_000L);
            input = input.substring(0, input.length() - 1);
        } else if (input.endsWith("m")) {
            multiplier = BigDecimal.valueOf(1_000_000L);
            input = input.substring(0, input.length() - 1);
        } else if (input.endsWith("b")) {
            multiplier = BigDecimal.valueOf(1_000_000_000L);
            input = input.substring(0, input.length() - 1);
        }

        try {
            return amountToCents(new BigDecimal(input).multiply(multiplier));
        } catch (NumberFormatException exception) {
            return -1L;
        }
    }

    public long amountToCents(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) < 0) {
            return -1L;
        }

        return amount
                .setScale(2, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100L))
                .longValue();
    }

    public void reset(UUID playerId) {
        setBalance(playerId, startingBalanceCents());
    }

    public void save() {
        FileConfiguration config = core.getEconomyConfig();
        config.set("balances", null);
        config.set("offline-payments", null);

        for (Map.Entry<UUID, Long> entry : balances.entrySet()) {
            if (entry.getValue() == null || entry.getValue() <= 0L) {
                continue;
            }

            config.set("balances." + entry.getKey(), entry.getValue());
        }

        for (Map.Entry<UUID, OfflinePaymentNotice> entry : offlinePayments.entrySet()) {
            String path = "offline-payments." + entry.getKey();
            config.set(path + ".total-cents", entry.getValue().totalCents());
            config.set(path + ".senders", new ArrayList<>(entry.getValue().senders()));
        }

        core.saveEconomyFile();
    }

    private void load() {
        balances.clear();
        offlinePayments.clear();

        FileConfiguration config = core.getEconomyConfig();
        ConfigurationSection balanceSection = config.getConfigurationSection("balances");

        if (balanceSection != null) {
            for (String key : balanceSection.getKeys(false)) {
                try {
                    long cents = config.getLong("balances." + key);

                    if (cents >= 1L) {
                        balances.put(UUID.fromString(key), cents);
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
        }

        ConfigurationSection offlineSection = config.getConfigurationSection("offline-payments");

        if (offlineSection != null) {
            for (String key : offlineSection.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(key);
                    long totalCents = config.getLong("offline-payments." + key + ".total-cents", 0L);
                    List<String> senders = config.getStringList("offline-payments." + key + ".senders");

                    if (totalCents > 0L) {
                        offlinePayments.put(uuid, new OfflinePaymentNotice(totalCents, new HashSet<>(senders)));
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
    }

    private void sendChatAndActionBar(Player player, String chat, String actionbar) {
        player.sendMessage(TextColor.color(chat));
        player.sendActionBar(component(actionbar));
    }

    private Component component(String message) {
        return LegacyComponentSerializer.legacySection().deserialize(TextColor.color(message));
    }

    private String message(String path) {
        return core.getMessage(path);
    }
}
