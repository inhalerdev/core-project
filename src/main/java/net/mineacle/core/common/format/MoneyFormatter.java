package net.mineacle.core.common.format;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MoneyFormatter {

    private static final BigDecimal ONE_HUNDRED =
            BigDecimal.valueOf(100L);
    private static final BigDecimal THOUSAND =
            BigDecimal.valueOf(1_000L);

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

    private static final Map<String, BigDecimal> INPUT_MULTIPLIERS =
            Map.ofEntries(
                    Map.entry("", BigDecimal.ONE),
                    Map.entry("k", BigDecimal.valueOf(1_000L)),
                    Map.entry("m", BigDecimal.valueOf(1_000_000L)),
                    Map.entry(
                            "b",
                            BigDecimal.valueOf(1_000_000_000L)
                    ),
                    Map.entry(
                            "t",
                            BigDecimal.valueOf(1_000_000_000_000L)
                    ),
                    Map.entry(
                            "q",
                            BigDecimal.valueOf(
                                    1_000_000_000_000_000L
                            )
                    ),
                    Map.entry(
                            "qi",
                            new BigDecimal("1000000000000000000")
                    ),
                    Map.entry(
                            "sx",
                            new BigDecimal("1000000000000000000000")
                    ),
                    Map.entry(
                            "sp",
                            new BigDecimal("1000000000000000000000000")
                    ),
                    Map.entry(
                            "oc",
                            new BigDecimal(
                                    "1000000000000000000000000000"
                            )
                    ),
                    Map.entry(
                            "no",
                            new BigDecimal(
                                    "1000000000000000000000000000000"
                            )
                    ),
                    Map.entry(
                            "dc",
                            new BigDecimal(
                                    "1000000000000000000000000000000000"
                            )
                    )
            );

    private static final Pattern INPUT_PATTERN = Pattern.compile(
            "^([+-]?)\\$?"
                    + "((?:\\d[\\d,_]*)(?:\\.\\d+)?|\\.\\d+)"
                    + "([a-zA-Z]{0,2})$"
    );

    private static final DecimalFormatSymbols SYMBOLS =
            DecimalFormatSymbols.getInstance(Locale.US);

    private static final ThreadLocal<DecimalFormat> COMPACT_FORMAT =
            ThreadLocal.withInitial(
                    () -> decimalFormat("0.##")
            );

    private static final ThreadLocal<DecimalFormat> FULL_FORMAT =
            ThreadLocal.withInitial(
                    () -> decimalFormat("#,##0.##")
            );

    private MoneyFormatter() {
    }

    public static String compact(double value) {
        if (!Double.isFinite(value)) {
            return "0";
        }

        return compact(BigDecimal.valueOf(value));
    }

    public static String compact(BigDecimal value) {
        if (value == null || value.signum() == 0) {
            return "0";
        }

        boolean negative = value.signum() < 0;
        BigDecimal number = value.abs();
        int suffixIndex = 0;

        while (number.compareTo(THOUSAND) >= 0
                && suffixIndex < SUFFIXES.length - 1) {
            number = number.divide(
                    THOUSAND,
                    12,
                    RoundingMode.DOWN
            );
            suffixIndex++;
        }

        String formatted = formatCompact(number);

        return (negative ? "-" : "")
                + formatted
                + SUFFIXES[suffixIndex];
    }

    public static String money(double value) {
        if (!Double.isFinite(value)) {
            return "$0";
        }

        return money(BigDecimal.valueOf(value));
    }

    public static String money(BigDecimal value) {
        if (value == null || value.signum() == 0) {
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
        if (value == null || value.signum() == 0) {
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
        return formatFull(centsToDollars(cents));
    }

    /**
     * Parses full or compact dollar input into integer cents.
     *
     * Supported examples:
     * 1550, 1,550, $1,550, 1.55k, 100k, 1M, 1.55B
     *
     * @return cents, or -1 when the input is invalid or outside long range
     */
    public static long parseCents(String raw) {
        if (raw == null) {
            return -1L;
        }

        String input = raw.trim();

        if (input.isEmpty() || containsWhitespace(input)) {
            return -1L;
        }

        Matcher matcher = INPUT_PATTERN.matcher(input);

        if (!matcher.matches()) {
            return -1L;
        }

        String sign = matcher.group(1);
        String numeric = matcher.group(2)
                .replace(",", "")
                .replace("_", "");
        String suffix = matcher.group(3)
                .toLowerCase(Locale.ROOT);

        BigDecimal multiplier = INPUT_MULTIPLIERS.get(suffix);

        if (multiplier == null) {
            return -1L;
        }

        try {
            BigDecimal value = new BigDecimal(numeric)
                    .multiply(multiplier)
                    .multiply(ONE_HUNDRED);

            if ("-".equals(sign)) {
                value = value.negate();
            }

            return value
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

    public static long parseNonNegativeCents(String raw) {
        long cents = parseCents(raw);
        return cents >= 0L ? cents : -1L;
    }

    private static BigDecimal centsToDollars(long cents) {
        return BigDecimal.valueOf(cents)
                .divide(ONE_HUNDRED, 2, RoundingMode.UNNECESSARY);
    }

    private static String formatCompact(BigDecimal value) {
        return COMPACT_FORMAT.get().format(
                value.setScale(2, RoundingMode.DOWN)
        );
    }

    private static String formatFull(BigDecimal value) {
        return FULL_FORMAT.get().format(
                value.setScale(2, RoundingMode.DOWN)
        );
    }

    private static DecimalFormat decimalFormat(String pattern) {
        DecimalFormat format = new DecimalFormat(pattern, SYMBOLS);
        format.setRoundingMode(RoundingMode.DOWN);
        format.setParseBigDecimal(true);
        return format;
    }

    private static boolean containsWhitespace(String input) {
        for (int index = 0; index < input.length(); index++) {
            if (Character.isWhitespace(input.charAt(index))) {
                return true;
            }
        }

        return false;
    }
}
