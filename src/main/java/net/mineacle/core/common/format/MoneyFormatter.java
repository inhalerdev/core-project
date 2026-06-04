package net.mineacle.core.common.format;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

public final class MoneyFormatter {

    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000L);

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

        while (number.compareTo(THOUSAND) >= 0 && suffixIndex < SUFFIXES.length - 1) {
            number = number.divide(THOUSAND, 12, RoundingMode.DOWN);
            suffixIndex++;
        }

        String formatted = formatUpToTwoDecimals(number);
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

        return "$" + formatFull(value);
    }

    public static String moneyFromCents(long cents) {
        return money(centsToDollars(cents));
    }

    public static String moneyFullFromCents(long cents) {
        return moneyFull(centsToDollars(cents));
    }

    public static String rawFromCents(long cents) {
        return formatUpToTwoDecimals(centsToDollars(cents));
    }

    private static BigDecimal centsToDollars(long cents) {
        return BigDecimal.valueOf(cents).divide(BigDecimal.valueOf(100L), 2, RoundingMode.DOWN);
    }

    private static String formatUpToTwoDecimals(BigDecimal value) {
        DecimalFormat format = new DecimalFormat("#,##0.##", SYMBOLS);
        format.setRoundingMode(RoundingMode.DOWN);
        return format.format(value.setScale(2, RoundingMode.DOWN));
    }

    private static String formatFull(BigDecimal value) {
        DecimalFormat format = new DecimalFormat("#,##0.##", SYMBOLS);
        format.setRoundingMode(RoundingMode.DOWN);
        return format.format(value.setScale(2, RoundingMode.DOWN));
    }
}
