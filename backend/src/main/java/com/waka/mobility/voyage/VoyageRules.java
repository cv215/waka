package com.waka.mobility.voyage;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * R11, intercity voyages: prices set by the driver per pair of stops (1 to 10 000 F per seat), 10 %
 * WAKA commission, money paid to the driver only at boarding, and cancellation fees. Integer francs;
 * each amount is rounded once, half up, and the parts always add up to the price paid.
 */
public final class VoyageRules {
    public static final int MAX_SEAT_PRICE_XAF = 10_000;
    public static final int MIN_STOPS = 2, MAX_STOPS = 6, MAX_SEATS = 5;
    public static final Duration FREE_CANCELLATION = Duration.ofHours(12), REDUCED_FEE = Duration.ofHours(3);

    /** Who receives what. {@code feePercent} is 0, 15 or 30 for a client cancellation, 0 otherwise. */
    public record Settlement(long priceXaf, long refundXaf, long driverXaf, long wakaXaf, int feePercent) {
        public Settlement {
            if (priceXaf < 1 || refundXaf < 0 || driverXaf < 0 || wakaXaf < 0 || refundXaf + driverXaf + wakaXaf != priceXaf) {
                throw new IllegalArgumentException("INVALID_SETTLEMENT");
            }
        }
    }

    private VoyageRules() { }

    /** At boarding: the driver receives the price minus the 10 % commission, rounded half up. */
    public static Settlement boarding(long priceXaf) {
        requirePrice(priceXaf);
        long commission = halfUp(priceXaf, 10);
        return new Settlement(priceXaf, 0, priceXaf - commission, commission, 0);
    }

    /** The driver cancels before boarding: full refund, nothing kept. */
    public static Settlement cancelledByDriver(long priceXaf) {
        requirePrice(priceXaf);
        return new Settlement(priceXaf, priceXaf, 0, 0, 0);
    }

    /**
     * The client cancels before boarding: free at 12 h or more before departure, 15 % from 12 h to 3 h,
     * 30 % under 3 h (an exact limit falls in the cheaper tier). The fee is split: the driver gets half
     * rounded down, WAKA the rest.
     */
    public static Settlement cancelledByClient(long priceXaf, Instant departure, Instant cancelledAt) {
        requirePrice(priceXaf);
        Duration before = Duration.between(Objects.requireNonNull(cancelledAt), Objects.requireNonNull(departure));
        int percent = before.compareTo(FREE_CANCELLATION) >= 0 ? 0 : before.compareTo(REDUCED_FEE) >= 0 ? 15 : 30;
        long fee = halfUp(priceXaf * percent, 100);
        long driver = fee / 2;
        return new Settlement(priceXaf, priceXaf - fee, driver, fee - driver, percent);
    }

    /**
     * A driver's price list is valid when it names exactly every pair (from, to) with from &lt; to over
     * the stops, each at 1 to 10 000 F per seat. Keys are "from-to" stop indexes, starting at 0.
     */
    public static boolean validPrices(int stops, Map<String, Integer> prices) {
        if (stops < MIN_STOPS || stops > MAX_STOPS || prices == null || prices.size() != stops * (stops - 1) / 2) return false;
        for (int from = 0; from < stops; from++) {
            for (int to = from + 1; to < stops; to++) {
                Integer price = prices.get(from + "-" + to);
                if (price == null || price < 1 || price > MAX_SEAT_PRICE_XAF) return false;
            }
        }
        return true;
    }

    /** Every pair of stops, in a stable order, for screens and price forms. */
    public static List<String> pairs(int stops) {
        var pairs = new java.util.ArrayList<String>();
        for (int from = 0; from < stops; from++) for (int to = from + 1; to < stops; to++) pairs.add(from + "-" + to);
        return List.copyOf(pairs);
    }

    private static void requirePrice(long priceXaf) {
        if (priceXaf < 1 || priceXaf > (long) MAX_SEAT_PRICE_XAF * MAX_SEATS) throw new IllegalArgumentException("INVALID_PRICE");
    }
    private static long halfUp(long numerator, long denominator) {
        return numerator / denominator + (2 * (numerator % denominator) >= denominator ? 1 : 0);
    }
}
