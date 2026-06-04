package net.mineacle.core.common.format;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

public final class MoneyFormatter {

    private static final String[] SUFFIXES = {
            "", "k", "M", "B", "T", "Q", "Qi", "Sx", "Sp", "Oc", "No", "Dc"
    };

    private static final DecimalFormatSymbols SYMBOLS = DecimalFormatSymbols.getInstance(Locale.US);

    private MoneyFormatter() {
    }

    public static String compact(double value) {
        return compact(BigDecimal.valueOf(value));
    }

    public static String compact(BigDecimal value) {
        if (value == null) {
            return "0";
        }

        boolean negative = value.signum() < 0;
        BigDecimal number = value.abs();
        int suffixIndex = 0;

        while (number.compareTo(BigDecimal.valueOf(1000L)) >= 0 && suffixIndex < SUFFIXES.length - 1) {
            number = number.divide(BigDecimal.valueOf(1000L), 8, RoundingMode.HALF_UP);
            suffixIndex++;
        }

        String formatted = suffixIndex == 0
                ? plain(number)
                : shortDecimal(number);

        return (negative ? "-" : "") + formatted + SUFFIXES[suffixIndex];
    }

    public static String money(double value) {
        return money(BigDecimal.valueOf(value));
    }

    public static String money(BigDecimal value) {
        if (value == null) {
            return "$0";
        }

        return "$" + compact(value);
    }

    public static String moneyFull(double value) {
        return moneyFull(BigDecimal.valueOf(value));
    }

    public static String moneyFull(BigDecimal value) {
        if (value == null) {
            return "$0";
        }

        return "$" + full(value);
    }

    public static String moneyFromCents(long cents) {
        return money(centsToDollars(cents));
    }

    public static String moneyFullFromCents(long cents) {
        return moneyFull(centsToDollars(cents));
    }

    public static String rawFromCents(long cents) {
        return plain(centsToDollars(cents));
    }

    private static BigDecimal centsToDollars(long cents) {
        return BigDecimal.valueOf(cents).divide(BigDecimal.valueOf(100L), 2, RoundingMode.HALF_UP);
    }

    private static String shortDecimal(BigDecimal value) {
        BigDecimal rounded = value.setScale(2, RoundingMode.DOWN);
        DecimalFormat format = new DecimalFormat("#,##0.##", SYMBOLS);
        format.setRoundingMode(RoundingMode.DOWN);
        return format.format(rounded);
    }

    private static String plain(BigDecimal value) {
        BigDecimal rounded = value.setScale(2, RoundingMode.DOWN);
        DecimalFormat format = new DecimalFormat("#,##0.##", SYMBOLS);
        format.setRoundingMode(RoundingMode.DOWN);
        return format.format(rounded);
    }

    private static String full(BigDecimal value) {
        BigDecimal rounded = value.setScale(2, RoundingMode.DOWN);
        DecimalFormat format = new DecimalFormat("#,##0.##", SYMBOLS);
        format.setRoundingMode(RoundingMode.DOWN);
        return format.format(rounded);
    }
}
