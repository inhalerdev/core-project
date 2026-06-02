package net.mineacle.core.common.format;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class MoneyFormatter {

    private static final String[] SUFFIXES = {
            "",
            "K",
            "M",
            "B",
            "T",
            "Q",
            "Qi",
            "Sx",
            "Sp",
            "Oc",
            "No",
            "Dc"
    };

    private MoneyFormatter() {
    }

    public static String compact(double value) {
        boolean negative = value < 0;
        double number = Math.abs(value);
        int suffixIndex = 0;

        while (number >= 1000.0 && suffixIndex < SUFFIXES.length - 1) {
            number /= 1000.0;
            suffixIndex++;
        }

        String formatted;

        if (suffixIndex == 0) {
            formatted = wholeOrTwoDecimals(number);
        } else if (number >= 100) {
            formatted = String.format("%.0f", number);
        } else if (number >= 10) {
            formatted = stripZeros(BigDecimal.valueOf(number).setScale(1, RoundingMode.HALF_UP));
        } else {
            formatted = stripZeros(BigDecimal.valueOf(number).setScale(2, RoundingMode.HALF_UP));
        }

        return (negative ? "-" : "") + formatted + SUFFIXES[suffixIndex];
    }

    public static String money(double value) {
        double absolute = Math.abs(value);

        if (absolute >= 1000.0) {
            return "$" + compact(value);
        }

        return "$" + wholeOrTwoDecimals(value);
    }

    public static String moneyFromCents(long cents) {
        boolean negative = cents < 0;
        long absolute = Math.abs(cents);
        long dollars = absolute / 100L;
        long remainingCents = absolute % 100L;

        if (remainingCents == 0L) {
            return (negative ? "-$" : "$") + dollars;
        }

        return (negative ? "-$" : "$") + dollars + "." + String.format("%02d", remainingCents);
    }

    private static String wholeOrTwoDecimals(double value) {
        BigDecimal decimal = BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);

        if (decimal.remainder(BigDecimal.ONE).compareTo(BigDecimal.ZERO) == 0) {
            return decimal.setScale(0, RoundingMode.HALF_UP).toPlainString();
        }

        return decimal.toPlainString();
    }

    private static String stripZeros(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
