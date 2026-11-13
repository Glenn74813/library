package ph.edu.zscmst.library.circulation;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import ph.edu.zscmst.library.patron.Patron;
import ph.edu.zscmst.library.patron.PatronDtos.PatronDto;

public final class CirculationDtos {

    private CirculationDtos() {
    }

    public record LoanDto(Long id, Long itemId, String barcode, Long recordId, String title, String callNumber,
                          Long patronId, String patronName, String cardNumber, Instant checkoutAt, Instant dueAt,
                          Instant returnedAt, int renewals, int maxRenewals, boolean overdue, long overdueDays,
                          BigDecimal accruedFine, String checkedOutBy, String rfidEpc) {
    }

    public record HoldDto(Long id, Long recordId, String title, Long patronId, String patronName, String cardNumber,
                          HoldStatus status, Instant placedAt, Instant readyAt, Instant expiresAt,
                          String itemBarcode, String itemShelfLocation, Integer queuePosition, LocalDate pickupDate,
                          boolean atRisk, Instant closedAt, String closedBy) {
        static HoldDto of(HoldRequest h, Integer queuePosition) {
            return of(h, queuePosition, false);
        }

        static HoldDto of(HoldRequest h, Integer queuePosition, boolean atRisk) {
            Patron p = h.getPatron();
            return new HoldDto(h.getId(), h.getRecord().getId(), h.getRecord().getTitle(), p.getId(),
                    p.getFullName(), p.getCardNumber(), h.getStatus(), h.getPlacedAt(), h.getReadyAt(),
                    h.getExpiresAt(), h.getItem() == null ? null : h.getItem().getBarcode(),
                    h.getItem() == null ? null : h.getItem().getShelfLocation(), queuePosition, h.getPickupDate(), atRisk,
                    h.getClosedAt(), h.getClosedBy());
        }
    }

    public record FineDto(Long id, Long patronId, String patronName, String cardNumber, BigDecimal amount,
                          String reason, FineStatus status, Instant createdAt, Instant settledAt,
                          String receiptNumber, String settledBy) {
        static FineDto of(Fine f) {
            Patron p = f.getPatron();
            return new FineDto(f.getId(), p.getId(), p.getFullName(), p.getCardNumber(), f.getAmount(),
                    f.getReason(), f.getStatus(), f.getCreatedAt(), f.getSettledAt(), f.getReceiptNumber(),
                    f.getSettledBy());
        }
    }

    public record ReceiptDto(String receiptNumber, String patronName, String cardNumber, List<FineDto> fines,
                             BigDecimal total, Instant paidAt, String cashier) {
    }

    public record PatronSummary(PatronDto patron, List<LoanDto> loans, List<HoldDto> holds, List<FineDto> unpaidFines,
                                BigDecimal unpaidTotal, int maxLoans, List<String> blocks) {
    }

    public record CheckoutRequest(@NotBlank String patron, @NotBlank String item, boolean override) {
    }

    /** {@code notice}: e.g. the loan was shortened because the title is booked by another patron. */
    public record CheckoutResult(LoanDto loan, List<String> warnings, String notice) {
    }

    /**
     * {@code settle}: for an overdue return, how its fine is settled before the return completes (PAY, or
     * WAIVE with {@code waiveReason} by a librarian or administrator). Without it an overdue return is refused.
     */
    public record CheckinRequest(@NotBlank String item, Settle settle, @Size(max = 200) String waiveReason) {
        public CheckinRequest(String item) {
            this(item, null, null);
        }
    }

    public enum Settle { PAY, WAIVE }

    /**
     * What must be cleared before this copy's return completes: the overdue fine returning it now would
     * charge, plus unpaid charges already linked to the borrowing (e.g. damage); {@code total} is their sum.
     */
    public record CheckinPreview(LoanDto loan, BigDecimal overdueFine, List<FineDto> charges, BigDecimal total) {
    }

    /** {@code receipt}: the payment receipt when an overdue fine was paid as part of the return. */
    public record CheckinResult(LoanDto loan, BigDecimal fineAmount, String holdForPatron, String message,
                                HoldSlip holdSlip, ReceiptDto receipt) {
        public CheckinResult(LoanDto loan, BigDecimal fineAmount, String holdForPatron, String message, HoldSlip holdSlip) {
            this(loan, fineAmount, holdForPatron, message, holdSlip, null);
        }

        public CheckinResult(LoanDto loan, BigDecimal fineAmount, String holdForPatron, String message) {
            this(loan, fineAmount, holdForPatron, message, null, null);
        }
    }

    /**
     * What goes on the slip placed inside a book set aside for a reservation. Only the card number and
     * initials identify the patron, since the slip sticks out of the book on an open shelf.
     */
    public record HoldSlip(Long holdId, String title, String barcode, String callNumber, String cardNumber,
                           String initials, Instant pickupBy) {
    }

    public record LostRequest(@NotNull @DecimalMin("0") BigDecimal charge) {
    }

    /** {@code pickupDate} null = next available copy; a date = advance booking. */
    public record HoldRequestBody(@NotNull Long patronId, @NotNull Long recordId, LocalDate pickupDate) {
    }

    public record RecordHoldBody(@NotNull Long recordId, LocalDate pickupDate) {
    }

    /** Dates a patron cannot book because every copy is already booked around them. */
    public record BookingCalendar(LocalDate firstDate, LocalDate lastDate, List<LocalDate> unavailable, int copies) {
    }

    public record PayRequest(@NotEmpty List<Long> fineIds) {
    }

    public record WaiveRequest(@NotBlank @Size(max = 200) String reason) {
    }

    /** {@code loanId}: the borrowing the charge is for (e.g. damage to that book), or null for a general charge. */
    public record ManualFineRequest(@NotNull Long patronId, @NotNull @DecimalMin("0.01") BigDecimal amount,
                                    @NotBlank @Size(max = 200) String reason, Long loanId) {
    }
}
