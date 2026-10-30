package com.waka.finance;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * R14 ledger: every movement is one line debiting one account and crediting another of the same amount,
 * so money is never created or lost. A balance is what an account received minus what it gave. Lines are
 * grouped in a journal whose name makes a repeat impossible (unique journal and line). Called inside the
 * caller's transaction, under the caller's locks.
 *
 * Accounts: {@code acct:<account>} the WAKA credit of a person (client refunds, driver earnings, top-ups);
 * {@code hold:<kind>:<id>} money paid for a service not yet delivered; {@code payout:<id>} a withdrawal
 * on its way; {@code provider:<name>} money held at the payment provider; {@code waka:revenue}.
 */
public final class Ledger {
    public static final String REVENUE = "waka:revenue";
    public record Line(String debit, String credit, long amountXaf) { }

    private Ledger() { }

    public static String account(String accountId) { return "acct:" + accountId; }
    public static String hold(String kind, String id) { return "hold:" + kind + ":" + id; }

    /** Posts the lines once; zero amounts are skipped. A journal already posted is left as it is. */
    public static boolean post(JdbcTemplate jdbc, String gate, String journal, Instant now, List<Line> lines) {
        Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM ledger_entry WHERE gate_hash=? AND journal=?", Integer.class, gate, journal);
        if (existing != null && existing > 0) return false;
        int lineNo = 0;
        for (Line line : lines) {
            if (line.amountXaf() < 0) throw new IllegalArgumentException("LEDGER_NEGATIVE");
            if (line.amountXaf() == 0) continue;
            jdbc.update("INSERT INTO ledger_entry (gate_hash,journal,line_no,debit_account,credit_account,amount_xaf,created_at) VALUES (?,?,?,?,?,?,?)",
                    gate, journal, ++lineNo, line.debit(), line.credit(), line.amountXaf(), LocalDateTime.ofInstant(now, ZoneOffset.UTC));
        }
        return true;
    }

    public static long balance(JdbcTemplate jdbc, String gate, String account) {
        Long in = jdbc.queryForObject("SELECT COALESCE(SUM(amount_xaf),0) FROM ledger_entry WHERE gate_hash=? AND credit_account=?", Long.class, gate, account);
        Long out = jdbc.queryForObject("SELECT COALESCE(SUM(amount_xaf),0) FROM ledger_entry WHERE gate_hash=? AND debit_account=?", Long.class, gate, account);
        return in - out;
    }

    /**
     * Ends the hold of a service: its whole balance goes to the given accounts (refund, driver, WAKA).
     * Without a hold (never paid) nothing moves. The parts must add up to the hold exactly; anything else
     * is a programming error and aborts the caller's transaction.
     */
    public static void release(JdbcTemplate jdbc, String gate, String hold, Instant now, Map<String, Long> parts) {
        long held = balance(jdbc, gate, hold);
        if (held == 0) return;
        long total = parts.values().stream().mapToLong(Long::longValue).sum();
        if (total != held) throw new IllegalStateException("LEDGER_RELEASE_MISMATCH");
        post(jdbc, gate, "release:" + hold, now, parts.entrySet().stream().map(e -> new Line(hold, e.getKey(), e.getValue())).toList());
    }

    /** Ordered parts, for {@link #release}. */
    public static Map<String, Long> parts(Object... accountAndAmount) {
        Map<String, Long> parts = new LinkedHashMap<>();
        for (int i = 0; i < accountAndAmount.length; i += 2) {
            parts.merge((String) accountAndAmount[i], ((Number) accountAndAmount[i + 1]).longValue(), Long::sum);
        }
        return parts;
    }
}
