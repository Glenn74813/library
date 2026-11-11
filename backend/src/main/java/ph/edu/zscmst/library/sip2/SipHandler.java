package ph.edu.zscmst.library.sip2;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import ph.edu.zscmst.library.catalog.Item;
import ph.edu.zscmst.library.catalog.ItemRepository;
import ph.edu.zscmst.library.catalog.ItemStatus;
import ph.edu.zscmst.library.circulation.CirculationDtos.CheckinResult;
import ph.edu.zscmst.library.circulation.CirculationDtos.CheckoutResult;
import ph.edu.zscmst.library.circulation.CirculationDtos.LoanDto;
import ph.edu.zscmst.library.circulation.CirculationDtos.PatronSummary;
import ph.edu.zscmst.library.circulation.CirculationService;
import ph.edu.zscmst.library.circulation.HoldRequestRepository;
import ph.edu.zscmst.library.circulation.HoldStatus;
import ph.edu.zscmst.library.circulation.Loan;
import ph.edu.zscmst.library.circulation.LoanRepository;
import ph.edu.zscmst.library.circulation.RenewalRequest;
import ph.edu.zscmst.library.circulation.RenewalService;
import ph.edu.zscmst.library.common.ApiException;
import ph.edu.zscmst.library.common.AuditService;
import ph.edu.zscmst.library.common.LibraryClock;
import ph.edu.zscmst.library.common.SettingsService;
import ph.edu.zscmst.library.common.SystemSettings;
import ph.edu.zscmst.library.patron.Patron;
import ph.edu.zscmst.library.patron.PatronRepository;
import ph.edu.zscmst.library.sip2.SipMessage.Reply;
import ph.edu.zscmst.library.user.AppUser;
import ph.edu.zscmst.library.user.AppUserRepository;
import ph.edu.zscmst.library.user.Role;

/**
 * Answers SIP2 requests using the same circulation rules as the desk: login (93), status (99),
 * patron status/information (23, 63), item information (17), checkout (11), checkin (09),
 * renew (29), end session (35) and resend (97).
 */
@Component
public class SipHandler {

    private static final Logger log = LoggerFactory.getLogger(SipHandler.class);
    private static final DateTimeFormatter SIP_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'    'HHmmss");
    private static final List<Role> SIP_ROLES = List.of(Role.ADMIN, Role.LIBRARIAN, Role.STAFF, Role.KIOSK);

    /** Per-connection state. */
    public static final class Session {
        final String peer;
        String user;
        String lastReply;
        boolean closed;

        Session(String peer) {
            this.peer = peer;
        }

        boolean closed() {
            return closed;
        }
    }

    private final AppUserRepository users;
    private final PasswordEncoder passwords;
    private final PatronRepository patrons;
    private final ItemRepository items;
    private final LoanRepository loans;
    private final HoldRequestRepository holds;
    private final CirculationService circulation;
    private final SettingsService settings;
    private final AuditService audit;
    private final LibraryClock clock;
    private final TransactionTemplate tx;
    private final SystemSettings system;
    private final RenewalService renewals;

    public SipHandler(AppUserRepository users, PasswordEncoder passwords, PatronRepository patrons, ItemRepository items,
                      LoanRepository loans, HoldRequestRepository holds, CirculationService circulation,
                      SettingsService settings, AuditService audit, LibraryClock clock, TransactionTemplate tx,
                      SystemSettings system, RenewalService renewals) {
        this.system = system;
        this.renewals = renewals;
        this.users = users;
        this.passwords = passwords;
        this.patrons = patrons;
        this.items = items;
        this.loans = loans;
        this.holds = holds;
        this.circulation = circulation;
        this.settings = settings;
        this.audit = audit;
        this.clock = clock;
        this.tx = tx;
    }

    /** Returns the reply line (without the trailing CR), or null for none. */
    public String handle(String raw, Session session) {
        SipMessage msg;
        try {
            msg = SipMessage.parse(raw);
        } catch (IllegalArgumentException e) {
            log.debug("SIP2 {} bad message ({}): {}", session.peer, e.getMessage(), raw);
            return "96"; // request SC resend
        }
        if (msg.command().equals("97")) return session.lastReply == null ? "96" : session.lastReply;
        if (session.user == null && !msg.command().equals("93") && !msg.command().equals("99")) {
            log.info("SIP2 {} sent {} before logging in", session.peer, msg.command());
            return "96";
        }
        String reply;
        try {
            reply = asUser(session, () -> dispatch(msg, session)).build(msg.sequence());
        } catch (RuntimeException e) {
            log.warn("SIP2 {} failed on {}: {}", session.peer, msg.command(), e.toString());
            return "96";
        }
        session.lastReply = reply;
        return reply;
    }

    /** Runs as the logged-in SIP account so the audit trail shows which station did what. */
    private <T> T asUser(Session session, Supplier<T> work) {
        if (session.user == null) return work.get();
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("sip2:" + session.user, null, List.of()));
        try {
            return work.get();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private Reply dispatch(SipMessage m, Session s) {
        return switch (m.command()) {
            case "93" -> login(m, s);
            case "99" -> status();
            case "23" -> patronStatus(m);
            case "63" -> patronInformation(m);
            case "17" -> itemInformation(m);
            case "11" -> checkout(m);
            case "09" -> checkin(m);
            case "29" -> renew(m);
            case "35" -> endSession(m);
            default -> throw new IllegalArgumentException("Unsupported message " + m.command());
        };
    }

    // ------------------------------------------------------------------ helpers

    private String now() {
        return LocalDateTime.now(clock.zone()).format(SIP_DATE);
    }

    private String date(Instant instant) {
        return instant == null ? "" : LocalDateTime.ofInstant(instant, clock.zone()).format(SIP_DATE);
    }

    /** SIP transaction date ("YYYYMMDDZZZZHHMMSS", local time); null if blank or invalid. */
    private Instant parseDate(String value) {
        if (value == null || value.isBlank() || value.length() < 18) return null;
        try {
            String v = value.substring(0, 8) + "    " + value.substring(12, 18);
            return LocalDateTime.parse(v, SIP_DATE).atZone(clock.zone()).toInstant();
        } catch (DateTimeParseException | StringIndexOutOfBoundsException e) {
            return null;
        }
    }

    private String institution(SipMessage m) {
        String ao = m.field("AO");
        return ao.isEmpty() ? settings.get("sip2.institutionId", system.profile().shortName()) : ao;
    }

    /** Runs a circulation action in one transaction; a rule violation rolls it back and is returned as text. */
    private <T> Result<T> attempt(Supplier<T> action) {
        return tx.execute(status -> {
            try {
                return new Result<>(action.get(), null);
            } catch (ApiException e) {
                status.setRollbackOnly();
                return new Result<>(null, e.getMessage());
            }
        });
    }

    private record Result<T>(T value, String error) {
        boolean ok() {
            return error == null;
        }
    }

    private Optional<Patron> patron(String code) {
        return code.isBlank() ? Optional.empty() : patrons.findByIdentifier(code.trim())
                .filter(p -> p.getAnonymizedAt() == null);
    }

    /** True when no PIN was sent, or the PIN matches. Patrons without a PIN can't use a PIN. */
    private boolean pinOk(Patron p, String pin) {
        if (pin == null || pin.isEmpty()) return true;
        return p.getPinHash() != null && passwords.matches(pin, p.getPinHash());
    }

    /** The 14-character patron status flags (Y = privilege denied / limit reached). */
    private String patronFlags(PatronSummary s, Patron p) {
        char[] f = "              ".toCharArray();
        boolean denied = !s.blocks().isEmpty();
        if (denied) f[0] = 'Y';
        if (p.isBlocked() || p.isExpired(clock.today())) {
            f[1] = 'Y';
            f[3] = 'Y';
        }
        if (s.loans().size() >= s.maxLoans()) f[5] = 'Y';
        if (s.loans().stream().anyMatch(LoanDto::overdue)) f[6] = 'Y';
        if (s.unpaidTotal().compareTo(p.getCategory().getMaxUnpaidFines()) > 0) f[10] = 'Y';
        return new String(f);
    }

    private static String count(long n) {
        return String.format("%04d", Math.min(n, 9999));
    }

    private static String money(BigDecimal amount) {
        return amount == null ? "0.00" : amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }

    private static String circulationStatus(Item item) {
        return switch (item.getStatus()) {
            case AVAILABLE -> "03";
            case ON_LOAN -> "04";
            case ON_HOLD -> "08";
            case LOST -> "12";
            case MISSING -> "13";
            default -> "01";
        };
    }

    private Optional<Item> item(String code) {
        return code.isBlank() ? Optional.empty() : items.findByIdentifier(code.trim());
    }

    // ------------------------------------------------------------------ messages

    private Reply login(SipMessage m, Session s) {
        String username = m.field("CN");
        Optional<AppUser> user = users.findByUsernameIgnoreCase(username)
                .filter(u -> u.isEnabled() && !u.isLocked(Instant.now()) && SIP_ROLES.contains(u.getRole())
                        && passwords.matches(m.field("CO"), u.getPasswordHash()));
        s.user = user.map(AppUser::getUsername).orElse(null);
        audit.record("sip2:" + username, user.isPresent() ? "SIP2_LOGIN" : "SIP2_LOGIN_FAILED",
                s.peer + (m.field("CP").isEmpty() ? "" : " location " + m.field("CP")));
        return new Reply("94").fixed(user.isPresent() ? '1' : '0');
    }

    private Reply status() {
        // Supported messages, in the order the standard lists them (patron status ... renew all).
        String supported = "YYYNYNYYYYYNNYNN";
        return new Reply("98").yn(true).yn(true).yn(true).yn(true).yn(false).yn(false)
                .fixed("030").fixed("003").fixed(now()).fixed("2.00")
                .field("AO", settings.get("sip2.institutionId", system.profile().shortName()))
                .field("AM", system.profile().name())
                .field("BX", supported)
                .optional("AF", settings.get("sip2.screenMessage", ""));
    }

    private Reply patronStatus(SipMessage m) {
        Optional<Patron> p = patron(m.field("AA"));
        Reply r = new Reply("24");
        if (p.isEmpty()) {
            return r.fixed("YYYY          ").fixed("001").fixed(now()).field("AO", institution(m)).field("AA", m.field("AA"))
                    .field("AE", "").field("BL", "N").field("AF", "Patron not found");
        }
        PatronSummary s = circulation.summary(p.get().getId());
        boolean pin = pinOk(p.get(), m.field("AD"));
        return r.fixed(patronFlags(s, p.get())).fixed("001").fixed(now()).field("AO", institution(m))
                .field("AA", p.get().getCardNumber()).field("AE", p.get().getFullName()).field("BL", "Y")
                .optional("CQ", m.field("AD").isEmpty() ? null : pin ? "Y" : "N")
                .field("BH", "PHP").field("BV", money(s.unpaidTotal()))
                .optional("AF", s.blocks().isEmpty() ? null : String.join("; ", s.blocks()));
    }

    private Reply patronInformation(SipMessage m) {
        Optional<Patron> p = patron(m.field("AA"));
        Reply r = new Reply("64");
        if (p.isEmpty()) {
            return r.fixed("YYYY          ").fixed("001").fixed(now()).fixed("0000".repeat(6))
                    .field("AO", institution(m)).field("AA", m.field("AA")).field("AE", "").field("BL", "N")
                    .field("AF", "Patron not found");
        }
        Patron patron = p.get();
        PatronSummary s = circulation.summary(patron.getId());
        List<LoanDto> overdue = s.loans().stream().filter(LoanDto::overdue).toList();
        long ready = s.holds().stream().filter(h -> h.status() == HoldStatus.READY).count();
        long waiting = s.holds().stream().filter(h -> h.status() == HoldStatus.PENDING || h.status() == HoldStatus.SCHEDULED).count();
        String summary = m.fixed().length() >= 31 ? m.fixed().substring(21, 31) : "";
        r.fixed(patronFlags(s, patron)).fixed("001").fixed(now())
                .fixed(count(ready)).fixed(count(overdue.size())).fixed(count(s.loans().size()))
                .fixed(count(s.unpaidFines().size())).fixed("0000").fixed(count(waiting))
                .field("AO", institution(m)).field("AA", patron.getCardNumber()).field("AE", patron.getFullName())
                .field("BZ", count(s.maxLoans())).field("CB", count(s.maxLoans()))
                .field("BL", "Y");
        if (!m.field("AD").isEmpty()) r.field("CQ", pinOk(patron, m.field("AD")) ? "Y" : "N");
        r.field("BH", "PHP").field("BV", money(s.unpaidTotal()));
        // Item lists, when the summary field asks for them: 0 = hold items, 1 = overdue, 2 = charged.
        if (summary.length() > 0 && summary.charAt(0) == 'Y') {
            s.holds().stream().filter(h -> h.status() == HoldStatus.READY && h.itemBarcode() != null)
                    .forEach(h -> r.field("AS", h.itemBarcode()));
        }
        if (summary.length() > 1 && summary.charAt(1) == 'Y') overdue.forEach(l -> r.field("AT", l.barcode()));
        if (summary.length() > 2 && summary.charAt(2) == 'Y') s.loans().forEach(l -> r.field("AU", l.barcode()));
        return r.optional("BE", patron.getEmail()).optional("BF", patron.getPhone())
                .optional("AF", s.blocks().isEmpty() ? null : String.join("; ", s.blocks()));
    }

    private Reply itemInformation(SipMessage m) {
        Reply r = new Reply("18");
        return tx.execute(status -> {
            Optional<Item> found = item(m.field("AB"));
            if (found.isEmpty()) {
                return r.fixed("01").fixed("00").fixed("01").fixed(now()).field("AB", m.field("AB")).field("AJ", "")
                        .field("AF", "Item not found");
            }
            Item item = found.get();
            Optional<Loan> loan = loans.findByItemIdAndReturnedAtIsNull(item.getId());
            r.fixed(circulationStatus(item)).fixed("00").fixed("01").fixed(now())
                    .optional("CF", item.getStatus() == ItemStatus.ON_HOLD ? "1" : null)
                    .optional("AH", loan.map(l -> date(l.getDueAt())).orElse(null))
                    .optional("CM", holds.findByItemIdAndStatus(item.getId(), HoldStatus.READY)
                            .map(h -> date(h.getExpiresAt())).orElse(null))
                    .field("AB", item.getBarcode()).field("AJ", item.getRecord().getTitle())
                    .optional("AQ", item.getShelfLocation());
            return r;
        });
    }

    private Reply checkout(SipMessage m) {
        boolean renewalPolicy = m.flag(0, 'N') == 'Y';
        boolean noBlock = m.flag(1, 'N') == 'Y';
        String card = m.field("AA");
        String barcode = m.field("AB");
        Reply r = new Reply("12");
        Optional<Patron> p = patron(card);
        String error = p.isEmpty() ? "Patron not found" : !pinOk(p.get(), m.field("AD")) ? "Wrong PIN" : null;
        if (error == null) {
            // Scanning an item you already have renews it, when the station allows that.
            Optional<Loan> existing = item(barcode).flatMap(i -> loans.findByItemIdAndReturnedAtIsNull(i.getId()));
            if (existing.isPresent() && existing.get().getPatron().getId().equals(p.get().getId())) {
                if (!renewalPolicy) {
                    error = "You already have this item";
                } else {
                    Result<RenewalService.Outcome> renewed = attempt(() ->
                            renewals.request(existing.get().getId(), p.get().getId(), RenewalRequest.Channel.SIP2));
                    if (renewed.ok() && !renewed.value().pending()) {
                        LoanDto l = renewed.value().loan();
                        return r.fixed('1').fixed('Y').fixed('N').fixed('Y').fixed(now()).field("AO", institution(m))
                                .field("AA", card).field("AB", barcode).field("AJ", l.title())
                                .field("AH", date(l.dueAt())).field("AF", "Renewed");
                    }
                    error = renewed.ok() ? "Renewal request sent to the library for approval" : renewed.error();
                }
            }
        }
        if (error == null) {
            // "No block" means the station already lent the item while offline: record it at that time.
            Instant at = noBlock ? Optional.ofNullable(parseDate(m.fixed().substring(2, 20))).orElse(Instant.now()) : null;
            Result<CheckoutResult> result = attempt(() -> circulation.checkout(card, barcode, noBlock, at));
            if (result.ok()) {
                LoanDto loan = result.value().loan();
                return r.fixed('1').fixed('N').fixed('N').fixed('Y').fixed(now()).field("AO", institution(m))
                        .field("AA", card).field("AB", barcode).field("AJ", loan.title()).field("AH", date(loan.dueAt()))
                        .optional("AF", result.value().notice());
            }
            error = result.error();
        }
        return r.fixed('0').fixed('N').fixed('U').fixed('N').fixed(now()).field("AO", institution(m))
                .field("AA", card).field("AB", barcode).field("AJ", "").field("AH", "").field("AF", error);
    }

    private Reply checkin(SipMessage m) {
        boolean noBlock = m.flag(0, 'N') == 'Y';
        String barcode = m.field("AB");
        Instant returned = noBlock ? parseDate(m.fixed().substring(19, 37)) : null;
        Result<CheckinResult> result = attempt(() -> circulation.checkin(barcode, returned));
        Reply r = new Reply("10");
        if (!result.ok()) {
            return r.fixed('0').fixed('Y').fixed('U').fixed('N').fixed(now()).field("AO", institution(m))
                    .field("AB", barcode).field("AQ", "").field("AF", result.error());
        }
        CheckinResult c = result.value();
        boolean hold = c.holdForPatron() != null;
        String location = item(barcode).map(Item::getShelfLocation).orElse("");
        String fine = c.fineAmount() != null && c.fineAmount().signum() > 0 ? " Overdue fine PHP " + money(c.fineAmount()) + "." : "";
        return r.fixed('1').fixed('Y').fixed('N').fixed(hold ? 'Y' : 'N').fixed(now()).field("AO", institution(m))
                .field("AB", barcode).field("AQ", location == null ? "" : location)
                .optional("AJ", c.loan() == null ? null : c.loan().title())
                .optional("AA", c.loan() == null ? null : c.loan().cardNumber())
                .optional("CV", hold ? "01" : null)
                .field("AF", c.message() + "." + fine);
    }

    private Reply renew(SipMessage m) {
        String card = m.field("AA");
        String barcode = m.field("AB");
        Reply r = new Reply("30");
        Optional<Patron> p = patron(card);
        Optional<Loan> loan = item(barcode).flatMap(i -> loans.findByItemIdAndReturnedAtIsNull(i.getId()));
        String error = p.isEmpty() ? "Patron not found" : !pinOk(p.get(), m.field("AD")) ? "Wrong PIN"
                : loan.isEmpty() || !loan.get().getPatron().getId().equals(p.get().getId()) ? "This item is not on loan to you" : null;
        if (error == null) {
            Result<RenewalService.Outcome> renewed = attempt(() ->
                    renewals.request(loan.get().getId(), p.get().getId(), RenewalRequest.Channel.SIP2));
            if (renewed.ok() && !renewed.value().pending()) {
                LoanDto l = renewed.value().loan();
                return r.fixed('1').fixed('Y').fixed('N').fixed('Y').fixed(now()).field("AO", institution(m))
                        .field("AA", card).field("AB", barcode).field("AJ", l.title()).field("AH", date(l.dueAt()));
            }
            // Waiting for staff approval counts as "not renewed yet" for the station.
            error = renewed.ok() ? "Renewal request sent to the library for approval" : renewed.error();
        }
        return r.fixed('0').fixed('N').fixed('U').fixed('N').fixed(now()).field("AO", institution(m))
                .field("AA", card).field("AB", barcode).field("AJ", "").field("AH", "").field("AF", error);
    }

    private Reply endSession(SipMessage m) {
        return new Reply("36").yn(true).fixed(now()).field("AO", institution(m)).field("AA", m.field("AA"));
    }
}
