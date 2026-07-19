package net.mineacle.core.common.format;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Map;

public final class MoneyFormatter {

    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100L);
    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1_000L);

    private static final String[] SUFFIXES = {
            "", "k", "M", "B", "T", "Q", "Qi", "Sx", "Sp", "Oc", "No", "Dc"
    };

    private static final Map<String, BigDecimal> INPUT_MULTIPLIERS = Map.of(
            "k", BigDecimal.valueOf(1_000L),
            "m", BigDecimal.valueOf(1_000_000L),
            "b", BigDecimal.valueOf(1_000_000_000L),
            "t", BigDecimal.valueOf(1_000_000_000_000L),
            "q", BigDecimal.valueOf(1_000_000_000_000_000L)
    );

    private static final DecimalFormatSymbols SYMBOLS =
            DecimalFormatSymbols.getInstance(Locale.US);

    private MoneyFormatter() {
    }

    public static String compact(double value) {
        if (!Double.isFinite(value)) {
            return "0";
        }

        return compact(BigDecimal.valueOf(value));
    }

    public static String compact(BigDecimal value) {
        if (value == null) {
            return "0";
        }

        boolean negative = value.signum() < 0;
        BigDecimal number = value.abs();
        int suffixIndex = 0;

        while (number.compareTo(THOUSAND) >= 0
                && suffixIndex < SUFFIXES.length - 1) {
            number = number.divide(THOUSAND, 12, RoundingMode.DOWN);
            suffixIndex++;
        }

        return (negative ? "-" : "")
                + formatUpToTwoDecimals(number)
                + SUFFIXES[suffixIndex];
    }

    public static String money(double value) {
        if (!Double.isFinite(value)) {
            return "$0";
        }

        return money(BigDecimal.valueOf(value));
    }

    public static String money(BigDecimal value) {
        if (value == null) {
            return "$0";
        }

        return value.signum() < 0
                ? "-$" + compact(value.abs())
                : "$" + compact(value);
    }

    public static String moneyFull(double value) {
        if (!Double.isFinite(value)) {
            return "$0";
        }

        return moneyFull(BigDecimal.valueOf(value));
    }

    public static String moneyFull(BigDecimal value) {
        if (value == null) {
            return "$0";
        }

        return value.signum() < 0
                ? "-$" + formatFull(value.abs())
                : "$" + formatFull(value);
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

    /**
     * Parses a full or compact dollar amount into cents.
     *
     * Supported examples:
     * 1550, 1,550, $1550, 1.55k, 100k, 1M, 1.55B
     *
     * @return cents, or -1 when the value is invalid or exceeds long range
     */
    public static long parseCents(String raw) {
        if (raw == null) {
            return -1L;
        }

        String input = raw
                .trim()
                .replace(",", "")
                .replace("_", "")
                .replace("$", "")
                .replace(" ", "");

        if (input.isBlank()) {
            return -1L;
        }

        BigDecimal multiplier = BigDecimal.ONE;
        String lower = input.toLowerCase(Locale.ROOT);

        for (Map.Entry<String, BigDecimal> entry : INPUT_MULTIPLIERS.entrySet()) {
            if (!lower.endsWith(entry.getKey())) {
                continue;
            }

            multiplier = entry.getValue();
            input = input.substring(0, input.length() - entry.getKey().length());
            break;
        }

        if (input.isBlank()) {
            return -1L;
        }

        try {
            return new BigDecimal(input)
                    .multiply(multiplier)
                    .multiply(ONE_HUNDRED)
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValueExact();
        } catch (NumberFormatException | ArithmeticException exception) {
            return -1L;
        }
    }

    public static long parsePositiveCents(String raw) {
        long cents = parseCents(raw);
        return cents > 0L ? cents : -1L;
    }

    private static BigDecimal centsToDollars(long cents) {
        return BigDecimal.valueOf(cents)
                .divide(ONE_HUNDRED, 2, RoundingMode.DOWN);
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
