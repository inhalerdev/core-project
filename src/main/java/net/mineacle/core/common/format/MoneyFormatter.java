package net.mineacle.core.common.format;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class MoneyFormatter {

    private static final String[] SUFFIXES = {
            "",
            "k",
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
        boolean negative = value < 0.0D;
        double number = Math.abs(value);
        int suffixIndex = 0;

        while (number >= 1000.0D && suffixIndex < SUFFIXES.length - 1) {
            number /= 1000.0D;
            suffixIndex++;
        }

        String formatted;

        if (suffixIndex == 0) {
            formatted = stripZeros(BigDecimal.valueOf(number).setScale(2, RoundingMode.HALF_UP));
        } else if (number >= 100.0D) {
            formatted = String.format("%.0f", number);
        } else if (number >= 10.0D) {
            formatted = stripZeros(BigDecimal.valueOf(number).setScale(1, RoundingMode.HALF_UP));
        } else {
            formatted = stripZeros(BigDecimal.valueOf(number).setScale(2, RoundingMode.HALF_UP));
        }

        return (negative ? "-" : "") + formatted + SUFFIXES[suffixIndex];
    }

    public static String money(double value) {
        double absolute = Math.abs(value);

        if (absolute >= 1000.0D) {
            return "$" + compact(value);
        }

        return "$" + stripZeros(BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP));
    }

    public static String moneyFromCents(long cents) {
        return money(cents / 100.0D);
    }

    private static String stripZeros(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
