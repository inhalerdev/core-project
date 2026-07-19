package net.mineacle.core.economy.vault;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.economy.service.EconomyService;
import org.bukkit.OfflinePlayer;

import java.math.BigDecimal;
import java.util.List;

public final class MineacleVaultEconomy implements Economy {

    private final EconomyService economyService;

    public MineacleVaultEconomy(
            EconomyService economyService
    ) {
        this.economyService = economyService;
    }

    @Override
    public boolean isEnabled() {
        return economyService != null
                && economyService.enabled();
    }

    @Override
    public String getName() {
        return "MineacleCore";
    }

    @Override
    public boolean hasBankSupport() {
        return false;
    }

    @Override
    public int fractionalDigits() {
        return 2;
    }

    @Override
    public String format(double amount) {
        long cents = toCents(amount);

        return cents < 0L
                ? economyService.format(0L)
                : economyService.format(cents);
    }

    @Override
    public String currencyNamePlural() {
        return "Dollars";
    }

    @Override
    public String currencyNameSingular() {
        return "Dollar";
    }

    @Override
    public boolean hasAccount(String playerName) {
        return resolve(playerName) != null;
    }

    @Override
    public boolean hasAccount(OfflinePlayer player) {
        return player != null;
    }

    @Override
    public boolean hasAccount(
            String playerName,
            String worldName
    ) {
        return hasAccount(playerName);
    }

    @Override
    public boolean hasAccount(
            OfflinePlayer player,
            String worldName
    ) {
        return hasAccount(player);
    }

    @Override
    public boolean createPlayerAccount(String playerName) {
        OfflinePlayer player = resolve(playerName);
        return createPlayerAccount(player);
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player) {
        return player != null
                && economyService.ensureAccount(
                player.getUniqueId()
        );
    }

    @Override
    public boolean createPlayerAccount(
            String playerName,
            String worldName
    ) {
        return createPlayerAccount(playerName);
    }

    @Override
    public boolean createPlayerAccount(
            OfflinePlayer player,
            String worldName
    ) {
        return createPlayerAccount(player);
    }

    @Override
    public double getBalance(String playerName) {
        return getBalance(resolve(playerName));
    }

    @Override
    public double getBalance(OfflinePlayer player) {
        if (player == null) {
            return 0.0D;
        }

        return dollars(
                economyService.getBalanceCents(
                        player.getUniqueId()
                )
        );
    }

    @Override
    public double getBalance(
            String playerName,
            String worldName
    ) {
        return getBalance(playerName);
    }

    @Override
    public double getBalance(
            OfflinePlayer player,
            String worldName
    ) {
        return getBalance(player);
    }

    @Override
    public boolean has(String playerName, double amount) {
        return has(resolve(playerName), amount);
    }

    @Override
    public boolean has(
            OfflinePlayer player,
            double amount
    ) {
        long cents = toCents(amount);

        return player != null
                && cents >= 0L
                && economyService.has(
                player.getUniqueId(),
                cents
        );
    }

    @Override
    public boolean has(
            String playerName,
            String worldName,
            double amount
    ) {
        return has(playerName, amount);
    }

    @Override
    public boolean has(
            OfflinePlayer player,
            String worldName,
            double amount
    ) {
        return has(player, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(
            String playerName,
            double amount
    ) {
        return withdrawPlayer(resolve(playerName), amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(
            OfflinePlayer player,
            double amount
    ) {
        if (!isEnabled()) {
            return failure(
                    amount,
                    getBalance(player),
                    "Economy is disabled"
            );
        }

        if (player == null) {
            return failure(
                    amount,
                    0.0D,
                    "Player could not be found"
            );
        }

        long cents = toCents(amount);

        if (cents < 0L) {
            return failure(
                    amount,
                    getBalance(player),
                    "Amount must be finite and nonnegative"
            );
        }

        if (cents == 0L) {
            return success(0L, getBalance(player));
        }

        if (!economyService.take(
                player.getUniqueId(),
                cents
        )) {
            return failure(
                    cents,
                    getBalance(player),
                    "Insufficient funds"
            );
        }

        return success(cents, getBalance(player));
    }

    @Override
    public EconomyResponse withdrawPlayer(
            String playerName,
            String worldName,
            double amount
    ) {
        return withdrawPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(
            OfflinePlayer player,
            String worldName,
            double amount
    ) {
        return withdrawPlayer(player, amount);
    }

    @Override
    public EconomyResponse depositPlayer(
            String playerName,
            double amount
    ) {
        return depositPlayer(resolve(playerName), amount);
    }

    @Override
    public EconomyResponse depositPlayer(
            OfflinePlayer player,
            double amount
    ) {
        if (!isEnabled()) {
            return failure(
                    amount,
                    getBalance(player),
                    "Economy is disabled"
            );
        }

        if (player == null) {
            return failure(
                    amount,
                    0.0D,
                    "Player could not be found"
            );
        }

        long cents = toCents(amount);

        if (cents < 0L) {
            return failure(
                    amount,
                    getBalance(player),
                    "Amount must be finite and nonnegative"
            );
        }

        if (cents == 0L) {
            return success(0L, getBalance(player));
        }

        if (!economyService.tryGive(
                player.getUniqueId(),
                cents
        )) {
            return failure(
                    cents,
                    getBalance(player),
                    "Balance limit exceeded"
            );
        }

        return success(cents, getBalance(player));
    }

    @Override
    public EconomyResponse depositPlayer(
            String playerName,
            String worldName,
            double amount
    ) {
        return depositPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse depositPlayer(
            OfflinePlayer player,
            String worldName,
            double amount
    ) {
        return depositPlayer(player, amount);
    }

    @Override
    public EconomyResponse createBank(
            String name,
            String player
    ) {
        return bankUnsupported();
    }

    @Override
    public EconomyResponse createBank(
            String name,
            OfflinePlayer player
    ) {
        return bankUnsupported();
    }

    @Override
    public EconomyResponse deleteBank(String name) {
        return bankUnsupported();
    }

    @Override
    public EconomyResponse bankBalance(String name) {
        return bankUnsupported();
    }

    @Override
    public EconomyResponse bankHas(
            String name,
            double amount
    ) {
        return bankUnsupported();
    }

    @Override
    public EconomyResponse bankWithdraw(
            String name,
            double amount
    ) {
        return bankUnsupported();
    }

    @Override
    public EconomyResponse bankDeposit(
            String name,
            double amount
    ) {
        return bankUnsupported();
    }

    @Override
    public EconomyResponse isBankOwner(
            String name,
            String playerName
    ) {
        return bankUnsupported();
    }

    @Override
    public EconomyResponse isBankOwner(
            String name,
            OfflinePlayer player
    ) {
        return bankUnsupported();
    }

    @Override
    public EconomyResponse isBankMember(
            String name,
            String playerName
    ) {
        return bankUnsupported();
    }

    @Override
    public EconomyResponse isBankMember(
            String name,
            OfflinePlayer player
    ) {
        return bankUnsupported();
    }

    @Override
    public List<String> getBanks() {
        return List.of();
    }

    private OfflinePlayer resolve(String playerName) {
        if (playerName == null || playerName.isBlank()) {
            return null;
        }

        return DisplayNames.resolveOffline(playerName);
    }

    private long toCents(double amount) {
        if (!Double.isFinite(amount) || amount < 0.0D) {
            return -1L;
        }

        return economyService.amountToCents(
                BigDecimal.valueOf(amount)
        );
    }

    private double dollars(long cents) {
        return cents / 100.0D;
    }

    private EconomyResponse success(
            long cents,
            double balance
    ) {
        return new EconomyResponse(
                dollars(cents),
                balance,
                EconomyResponse.ResponseType.SUCCESS,
                null
        );
    }

    private EconomyResponse failure(
            double requestedAmount,
            double balance,
            String error
    ) {
        return new EconomyResponse(
                requestedAmount,
                balance,
                EconomyResponse.ResponseType.FAILURE,
                error
        );
    }

    private EconomyResponse failure(
            long requestedCents,
            double balance,
            String error
    ) {
        return failure(
                dollars(requestedCents),
                balance,
                error
        );
    }

    private EconomyResponse bankUnsupported() {
        return new EconomyResponse(
                0.0D,
                0.0D,
                EconomyResponse.ResponseType.NOT_IMPLEMENTED,
                "Bank support is not implemented"
        );
    }
}
