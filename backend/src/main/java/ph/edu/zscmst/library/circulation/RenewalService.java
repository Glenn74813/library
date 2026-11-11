package ph.edu.zscmst.library.circulation;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ph.edu.zscmst.library.catalog.LoanPolicy;
import ph.edu.zscmst.library.circulation.CirculationDtos.LoanDto;
import ph.edu.zscmst.library.common.ApiException;
import ph.edu.zscmst.library.common.AuditService;
import ph.edu.zscmst.library.notice.NoticeService;
import ph.edu.zscmst.library.notice.NoticeType;

/**
 * Patron renewals with staff approval: the patron asks, staff see what the renewal would do to
 * reservations and bookings, then approve (the loan is renewed) or reject with a reason. The
 * patron is told either way.
 */
@Service
public class RenewalService {

    /** What happened to a patron's renewal: renewed straight away, or waiting for staff. */
    public record Outcome(boolean pending, LoanDto loan, RenewalDto request, String message) {
    }

    public record RenewalDto(Long id, Long loanId, String title, String barcode, Long patronId, String patronName,
                             String cardNumber, RenewalRequest.Status status, RenewalRequest.Channel channel,
                             Instant requestedAt, Instant dueAt, Instant proposedDueAt, Instant newDueAt,
                             Instant decidedAt, String decidedBy, String reason, int renewals, int maxRenewals,
                             String problem) {
    }

    private final RenewalRequestRepository requests;
    private final LoanRepository loans;
    private final CirculationService circulation;
    private final LoanPolicy policy;
    private final NoticeService notices;
    private final AuditService audit;

    public RenewalService(RenewalRequestRepository requests, LoanRepository loans, CirculationService circulation,
                          LoanPolicy policy, NoticeService notices, AuditService audit) {
        this.requests = requests;
        this.loans = loans;
        this.circulation = circulation;
        this.policy = policy;
        this.notices = notices;
        this.audit = audit;
    }

    /**
     * A patron asks to renew. With approval switched off the loan is renewed at once; otherwise the
     * request is checked against the loan rules (so hopeless ones are refused straight away) and queued.
     */
    @Transactional
    public Outcome request(Long loanId, Long patronId, RenewalRequest.Channel channel) {
        if (!policy.renewalApproval()) {
            LoanDto loan = circulation.renew(loanId, patronId);
            return new Outcome(false, loan, null, "Renewed. Now due " + loan.dueAt());
        }
        circulation.previewRenewal(loanId, patronId, false);
        if (requests.existsByLoanIdAndStatus(loanId, RenewalRequest.Status.PENDING)) {
            throw ApiException.conflict("A renewal for this item is already waiting for approval");
        }
        Loan loan = loans.findWithDetailsById(loanId).orElseThrow(() -> ApiException.notFound("Loan"));
        RenewalRequest r = requests.save(new RenewalRequest(loan, channel));
        audit.record(loan.getPatron().getCardNumber(), "RENEWAL_REQUESTED",
                loan.getItem().getBarcode() + " via " + channel);
        return new Outcome(true, null, toDto(r),
                "Renewal requested. The library will approve or reject it; keep the item until you hear back.");
    }

    @Transactional(readOnly = true)
    public List<RenewalDto> pending() {
        return requests.findByStatusOrderByRequestedAtAsc(RenewalRequest.Status.PENDING).stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public Page<RenewalDto> decided(Pageable pageable) {
        return requests.findByStatusInOrderByDecidedAtDesc(
                EnumSet.of(RenewalRequest.Status.APPROVED, RenewalRequest.Status.REJECTED, RenewalRequest.Status.CANCELLED),
                pageable).map(this::toDto);
    }

    @Transactional(readOnly = true)
    public List<RenewalDto> ofPatron(Long patronId) {
        return requests.findTop20ByPatronIdOrderByRequestedAtDesc(patronId).stream().map(this::toDto).toList();
    }

    @Transactional
    public RenewalDto approve(Long id) {
        RenewalRequest r = pendingRequest(id);
        boolean beforeDue = !r.getRequestedAt().isAfter(r.getDueAt());
        LoanDto loan = circulation.renewLoan(r.getLoan().getId(), null, beforeDue);
        r.decide(RenewalRequest.Status.APPROVED, AuditService.currentActor(), null, loan.dueAt());
        notices.queue(r.getPatron(), NoticeType.RENEWAL_APPROVED, loan.title(), loan.dueAt(), "renewal-" + r.getId());
        audit.record("RENEWAL_APPROVED", loan.barcode() + " for " + loan.cardNumber() + ", due " + loan.dueAt());
        return toDto(r);
    }

    @Transactional
    public RenewalDto reject(Long id, String reason) {
        if (reason == null || reason.isBlank()) throw ApiException.badRequest("Give the patron a reason for the rejection");
        RenewalRequest r = pendingRequest(id);
        r.decide(RenewalRequest.Status.REJECTED, AuditService.currentActor(), reason.trim(), null);
        Loan loan = r.getLoan();
        notices.queue(r.getPatron(), NoticeType.RENEWAL_REJECTED, loan.getItem().getRecord().getTitle(), loan.getDueAt(),
                "renewal-" + r.getId(), reason.trim());
        audit.record("RENEWAL_REJECTED", loan.getItem().getBarcode() + " for " + r.getPatron().getCardNumber()
                + ": " + reason.trim());
        return toDto(r);
    }

    private RenewalRequest pendingRequest(Long id) {
        RenewalRequest r = requests.findWithDetailsById(id).orElseThrow(() -> ApiException.notFound("Renewal request"));
        if (r.getStatus() != RenewalRequest.Status.PENDING) {
            throw ApiException.conflict("This request was already " + r.getStatus().name().toLowerCase());
        }
        return r;
    }

    private RenewalDto toDto(RenewalRequest r) {
        Loan l = r.getLoan();
        Instant proposed = null;
        String problem = null;
        if (r.getStatus() == RenewalRequest.Status.PENDING) {
            // Re-checked on every view: a reservation or booking may have appeared since the request.
            try {
                proposed = circulation.previewRenewal(l.getId(), null, !r.getRequestedAt().isAfter(r.getDueAt()));
            } catch (ApiException e) {
                problem = e.getMessage();
            }
        }
        return new RenewalDto(r.getId(), l.getId(), l.getItem().getRecord().getTitle(), l.getItem().getBarcode(),
                r.getPatron().getId(), r.getPatron().getFullName(), r.getPatron().getCardNumber(), r.getStatus(),
                r.getChannel(), r.getRequestedAt(), r.getDueAt(), proposed, r.getNewDueAt(), r.getDecidedAt(),
                r.getDecidedBy(), r.getReason(), l.getRenewals(), r.getPatron().getCategory().getMaxRenewals(), problem);
    }
}
