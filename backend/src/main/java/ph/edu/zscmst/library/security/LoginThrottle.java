package ph.edu.zscmst.library.security;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Counts sign-in attempts per account in the database, claiming each attempt <em>before</em> the password
 * or PIN is checked. The row update is atomic, so parallel guesses can't slip past the limit: once
 * {@code max} attempts are claimed without a success, the account is locked for {@code lockMinutes}.
 */
@Service
public class LoginThrottle {

    /** Staff accounts (password) and patrons (PIN): table and counter columns. */
    public enum Account {
        STAFF("app_user", "failed_logins", "locked_until"),
        PATRON("patron", "pin_failures", "pin_locked_until");

        final String table;
        final String failures;
        final String lockedUntil;

        Account(String table, String failures, String lockedUntil) {
            this.table = table;
            this.failures = failures;
            this.lockedUntil = lockedUntil;
        }
    }

    /**
     * Result of claiming an attempt. {@code locked} non-null: refused, locked until then. Otherwise the
     * attempt may go ahead; {@code locksOnFailure} non-null means this was the last allowed attempt.
     */
    public record Claim(Instant locked, Instant locksOnFailure) {
    }

    private final JdbcTemplate jdbc;

    public LoginThrottle(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Claim claim(Account a, long id, int max, int lockMinutes, Instant now) {
        Timestamp ts = Timestamp.from(now);
        Timestamp lockEnd = Timestamp.from(now.plusSeconds(lockMinutes * 60L));
        // An expired lock starts a fresh count; the attempt that reaches the limit sets the lock.
        String sql = """
                update %1$s set %2$s = case when %3$s is not null and %3$s <= ? then 1 else %2$s + 1 end,
                       %3$s = case when (case when %3$s is not null and %3$s <= ? then 1 else %2$s + 1 end) >= ? then cast(? as timestamptz) else null end
                where id = ? and (%3$s is null or %3$s <= ?) returning %3$s"""
                .formatted(a.table, a.failures, a.lockedUntil);
        List<Timestamp> rows = jdbc.queryForList(sql, Timestamp.class, ts, ts, Math.max(1, max), lockEnd, id, ts);
        if (rows.isEmpty()) {
            Timestamp until = jdbc.queryForObject("select %s from %s where id = ?".formatted(a.lockedUntil, a.table),
                    Timestamp.class, id);
            return new Claim(until == null ? lockEnd.toInstant() : until.toInstant(), null);
        }
        Timestamp last = rows.getFirst();
        return new Claim(null, last == null ? null : last.toInstant());
    }

    /** A correct password or PIN: clears the count (and the lock the last allowed attempt set). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void succeeded(Account a, long id) {
        jdbc.update("update %s set %s = 0, %s = null where id = ?".formatted(a.table, a.failures, a.lockedUntil), id);
    }
}
