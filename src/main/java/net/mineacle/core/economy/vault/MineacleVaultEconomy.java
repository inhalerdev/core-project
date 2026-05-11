package net.mineacle.core.economy.vault;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import net.mineacle.core.economy.service.EconomyService;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public final class MineacleVaultEconomy implements Economy {

    private final EconomyService economyService;

    public MineacleVaultEconomy(EconomyService economyService) {
        this.economyService = economyService;
    }

    @Override
    public boolean isEnabled() {
        return economyService != null;
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
        return economyService.format(toCents(amount));
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
        return playerName != null && !playerName.isBlank();
    }

    @Override
    public boolean hasAccount(OfflinePlayer player) {
        return player != null;
    }

    @Override
    public boolean hasAccount(String playerName, String worldName) {
        return hasAccount(playerName);
    }

    @Override
    public boolean hasAccount(OfflinePlayer player, String worldName) {
        return hasAccount(player);
    }

    @Override
    public boolean createPlayerAccount(String playerName) {
        return hasAccount(playerName);
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player) {
        return hasAccount(player);
    }

    @Override
    public boolean createPlayerAccount(String playerName, String worldName) {
        return createPlayerAccount(playerName);
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player, String worldName) {
        return createPlayerAccount(player);
    }

    @Override
    public double getBalance(String playerName) {
        return getBalance(resolve(playerName));
    }

    @Override
    public double getBalance(OfflinePlayer player) {
        if (player == null) {
            return 0.0;
        }

        return economyService.getBalanceCents(player.getUniqueId()) / 100.0;
    }

    @Override
    public double getBalance(String playerName, String worldName) {
        return getBalance(playerName);
    }

    @Override
    public double getBalance(OfflinePlayer player, String worldName) {
        return getBalance(player);
    }

    @Override
    public boolean has(String playerName, double amount) {
        return has(resolve(playerName), amount);
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        if (player == null) {
            return false;
        }

        return economyService.has(player.getUniqueId(), toCents(amount));
    }

    @Override
    public boolean has(String playerName, String worldName, double amount) {
        return has(playerName, amount);
    }

    @Override
    public boolean has(OfflinePlayer player, String worldName, double amount) {
        return has(player, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, double amount) {
        return withdrawPlayer(resolve(playerName), amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
        if (player == null) {
            return failure(amount, 0.0, "Player could not be found.");
        }

        long cents = toCents(amount);

        if (cents < 0L) {
            return failure(amount, getBalance(player), "Amount cannot be negative.");
        }

        if (!economyService.take(player.getUniqueId(), cents)) {
            return failure(amount, getBalance(player), "Insufficient funds.");
        }

        return success(amount, getBalance(player));
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) {
        return withdrawPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, String worldName, double amount) {
        return withdrawPlayer(player, amount);
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, double amount) {
        return depositPlayer(resolve(playerName), amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
        if (player == null) {
            return failure(amount, 0.0, "Player could not be found.");
        }

        long cents = toCents(amount);

        if (cents < 0L) {
            return failure(amount, getBalance(player), "Amount cannot be negative.");
        }

        economyService.give(player.getUniqueId(), cents);
        return success(amount, getBalance(player));
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, String worldName, double amount) {
        return depositPlayer(playerName, amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, String worldName, double amount) {
        return depositPlayer(player, amount);
    }

    @Override
    public EconomyResponse createBank(String name, String player) {
        return bankUnsupported();
    }

    @Override
    public EconomyResponse createBank(String name, OfflinePlayer player) {
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
    public EconomyResponse bankHas(String name, double amount) {
        return bankUnsupported();
    }

    @Override
    public EconomyResponse bankWithdraw(String name, double amount) {
        return bankUnsupported();
    }

    @Override
    public EconomyResponse bankDeposit(String name, double amount) {
        return bankUnsupported();
    }

    @Override
    public EconomyResponse isBankOwner(String name, String playerName) {
        return bankUnsupported();
    }

    @Override
    public EconomyResponse isBankOwner(String name, OfflinePlayer player) {
        return bankUnsupported();
    }

    @Override
    public EconomyResponse isBankMember(String name, String playerName) {
        return bankUnsupported();
    }

    @Override
    public EconomyResponse isBankMember(String name, OfflinePlayer player) {
        return bankUnsupported();
    }

    @Override
    public List<String> getBanks() {
        return new ArrayList<>();
    }

    private OfflinePlayer resolve(String playerName) {
        if (playerName == null || playerName.isBlank()) {
            return null;
        }

        return Bukkit.getOfflinePlayer(playerName);
    }

    private long toCents(double amount) {
        return economyService.amountToCents(BigDecimal.valueOf(amount));
    }

    private EconomyResponse success(double amount, double balance) {
        return new EconomyResponse(amount, balance, EconomyResponse.ResponseType.SUCCESS, null);
    }

    private EconomyResponse failure(double amount, double balance, String error) {
        return new EconomyResponse(amount, balance, EconomyResponse.ResponseType.FAILURE, error);
    }

    private EconomyResponse bankUnsupported() {
        return new EconomyResponse(0.0, 0.0, EconomyResponse.ResponseType.NOT_IMPLEMENTED, "Bank support is not implemented.");
    }
}