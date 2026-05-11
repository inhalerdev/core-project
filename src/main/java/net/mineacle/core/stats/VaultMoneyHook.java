package net.mineacle.core.stats;

import net.mineacle.core.common.format.MoneyFormatter;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.lang.reflect.Method;

public final class VaultMoneyHook {

    private VaultMoneyHook() {
    }

    public static String formattedBalance(OfflinePlayer player) {
        if (player == null) {
            return "$0";
        }

        try {
            Class<?> economyClass = Class.forName("net.milkbowl.vault.economy.Economy");
            Object economy = Bukkit.getServicesManager().load(economyClass);

            if (economy == null) {
                return "$0";
            }

            Method getBalance = economyClass.getMethod("getBalance", OfflinePlayer.class);
            Object result = getBalance.invoke(economy, player);

            if (!(result instanceof Number number)) {
                return "$0";
            }

            return MoneyFormatter.money(number.doubleValue());
        } catch (Throwable ignored) {
            return "$0";
        }
    }
}