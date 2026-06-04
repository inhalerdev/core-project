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

        String formatted;
        if (suffixIndex == 0) {
            formatted = decimal(number, true);
        } else if (number.compareTo(BigDecimal.valueOf(100L)) >= 0) {
            formatted = whole(number);
        } else if (number.compareTo(BigDecimal.TEN) >= 0) {
            formatted = oneDecimal(number);
        } else {
            formatted = twoDecimals(number);
        }

        return (negative ? "-" : "") + formatted + SUFFIXES[suffixIndex];
    }

    public static String money(double value) {
        return money(BigDecimal.valueOf(value));
    }

    public static String money(BigDecimal value) {
        if (value == null) {
            return "$0";
        }

        BigDecimal rounded = value.setScale(2, RoundingMode.HALF_UP);
        BigDecimal absolute = rounded.abs();

        if (absolute.compareTo(BigDecimal.valueOf(1000L)) >= 0) {
            return "$" + compact(rounded);
        }

        return "$" + decimal(rounded, true);
    }

    public static String moneyFull(double value) {
        return moneyFull(BigDecimal.valueOf(value));
    }

    public static String moneyFull(BigDecimal value) {
        if (value == null) {
            return "$0";
        }

        return "$" + decimal(value.setScale(2, RoundingMode.HALF_UP), true);
    }

    public static String moneyFromCents(long cents) {
        return money(centsToDollars(cents));
    }

    public static String moneyFullFromCents(long cents) {
        return moneyFull(centsToDollars(cents));
    }

    public static String rawFromCents(long cents) {
        return decimal(centsToDollars(cents), true);
    }

    private static BigDecimal centsToDollars(long cents) {
        return BigDecimal.valueOf(cents).divide(BigDecimal.valueOf(100L), 2, RoundingMode.HALF_UP);
    }

    private static String decimal(BigDecimal value, boolean stripZeroCents) {
        DecimalFormat format = new DecimalFormat(stripZeroCents ? "#,##0.##" : "#,##0.00", SYMBOLS);
        format.setRoundingMode(RoundingMode.HALF_UP);
        return format.format(value);
    }

    private static String whole(BigDecimal value) {
        DecimalFormat format = new DecimalFormat("#,##0", SYMBOLS);
        format.setRoundingMode(RoundingMode.HALF_UP);
        return format.format(value.setScale(0, RoundingMode.HALF_UP));
    }

    private static String oneDecimal(BigDecimal value) {
        DecimalFormat format = new DecimalFormat("#,##0.#", SYMBOLS);
        format.setRoundingMode(RoundingMode.HALF_UP);
        return format.format(value.setScale(1, RoundingMode.HALF_UP));
    }

    private static String twoDecimals(BigDecimal value) {
        DecimalFormat format = new DecimalFormat("#,##0.##", SYMBOLS);
        format.setRoundingMode(RoundingMode.HALF_UP);
        return format.format(value.setScale(2, RoundingMode.HALF_UP));
    }
}
