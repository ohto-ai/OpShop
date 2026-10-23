package com.ohtoai.opshop.shop;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Price math helpers.
 * <p>
 * Prices are kept as {@code double} in memory (the Vault economy API is
 * double-based) but are persisted as integer cents (long) in the database —
 * never as float/double — so values like 0.01 survive storage exactly.
 * Custom-item currencies stay integer-only and are validated at input time.
 */
public final class PriceUtil {

    /** Minimum non-zero price (0 = currency disabled). */
    public static final double MIN_PRICE = 0.01;

    /** Largest price representable in cents (avoids long overflow in toCents). */
    public static final double MAX_PRICE = Long.MAX_VALUE / 100.0;

    /** Cents per unit — all stored prices are multiples of this. */
    public static final long CENTS_PER_UNIT = 100;

    private PriceUtil() {
        // utility class
    }

    /**
     * Round a price to 2 decimal places (half-up), avoiding binary float drift.
     */
    public static double round2(double price) {
        return BigDecimal.valueOf(price).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    /**
     * Convert a price to integer cents for database storage.
     * Clamps to the long range instead of wrapping on absurd inputs.
     */
    public static long toCents(double price) {
        BigDecimal cents = BigDecimal.valueOf(price).setScale(2, RoundingMode.HALF_UP)
            .movePointRight(2);
        if (cents.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0) return Long.MAX_VALUE;
        if (cents.compareTo(BigDecimal.valueOf(Long.MIN_VALUE)) < 0) return Long.MIN_VALUE;
        return cents.longValue();
    }

    /**
     * Convert integer cents read from the database back to a double price.
     */
    public static double fromCents(long cents) {
        return BigDecimal.valueOf(cents).movePointLeft(2).doubleValue();
    }

    /**
     * Format a price for display: whole numbers without decimals,
     * otherwise exactly 2 decimal places (e.g. "100", "1.50").
     */
    public static String format(double price) {
        long cents = toCents(price);
        if (cents % CENTS_PER_UNIT == 0) return String.valueOf(cents / CENTS_PER_UNIT);
        return BigDecimal.valueOf(cents).movePointLeft(2).toPlainString();
    }
}
