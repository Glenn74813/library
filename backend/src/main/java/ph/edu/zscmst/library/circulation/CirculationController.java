package ph.edu.zscmst.library.circulation;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import ph.edu.zscmst.library.circulation.CirculationDtos.CheckinRequest;
import ph.edu.zscmst.library.circulation.CirculationDtos.CheckinResult;
import ph.edu.zscmst.library.circulation.CirculationDtos.CheckoutRequest;
import ph.edu.zscmst.library.circulation.CirculationDtos.CheckoutResult;
import ph.edu.zscmst.library.circulation.CirculationDtos.FineDto;
import ph.edu.zscmst.library.circulation.CirculationDtos.HoldDto;
import ph.edu.zscmst.library.circulation.CirculationDtos.HoldRequestBody;
import ph.edu.zscmst.library.circulation.CirculationDtos.LoanDto;
import ph.edu.zscmst.library.circulation.CirculationDtos.LostRequest;
import ph.edu.zscmst.library.circulation.CirculationDtos.ManualFineRequest;
import ph.edu.zscmst.library.circulation.CirculationDtos.PatronSummary;
import ph.edu.zscmst.library.circulation.CirculationDtos.PayRequest;
import ph.edu.zscmst.library.circulation.CirculationDtos.ReceiptDto;
import ph.edu.zscmst.library.circulation.CirculationDtos.WaiveRequest;
import ph.edu.zscmst.library.common.ApiException;
import ph.edu.zscmst.library.common.PageResponse;

/** Circulation desk API (staff). */
@RestController
@RequestMapping("/api")
public class CirculationController {

    private final CirculationService service;

    public CirculationController(CirculationService service) {
        this.service = service;
    }

    @PostMapping("/circulation/checkout")
    public CheckoutResult checkout(@Valid @RequestBody CheckoutRequest req, Authentication auth) {
        if (req.override() && auth.getAuthorities().stream()
                .noneMatch(a -> a.getAuthority().equals("ROLE_ADMIN") || a.getAuthority().equals("ROLE_LIBRARIAN"))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only librarians can override loan rules");
        }
        return service.checkout(req.patron(), req.item(), req.override());
    }

    @PostMapping("/circulation/checkin")
    public CheckinResult checkin(@Valid @RequestBody CheckinRequest req) {
        return service.checkin(req.item());
    }

    @PostMapping("/circulation/loans/{id}/renew")
    public LoanDto renew(@PathVariable Long id) {
        return service.renew(id, null);
    }

    @PostMapping("/circulation/loans/{id}/lost")
    @PreAuthorize("hasAnyRole('ADMIN','LIBRARIAN')")
    public LoanDto lost(@PathVariable Long id, @Valid @RequestBody LostRequest req) {
        return service.markLost(id, req.charge());
    }

    @GetMapping("/circulation/loans")
    public PageResponse<LoanDto> loans(@RequestParam(defaultValue = "false") boolean overdue,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        return PageResponse.of(service.activeLoans(overdue, PageRequest.of(page, Math.min(size, 100))), l -> l);
    }

    @GetMapping("/circulation/patrons/{id}/summary")
    public PatronSummary summary(@PathVariable Long id) {
        return service.summary(id);
    }

    @GetMapping("/circulation/patrons/{id}/history")
    public PageResponse<LoanDto> history(@PathVariable Long id, @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "20") int size) {
        return PageResponse.of(service.history(id, PageRequest.of(page, Math.min(size, 100))), l -> l);
    }

    // holds

    @GetMapping("/holds")
    public List<HoldDto> holds() {
        return service.activeHolds();
    }

    @PostMapping("/holds")
    @ResponseStatus(HttpStatus.CREATED)
    public HoldDto placeHold(@Valid @RequestBody HoldRequestBody req) {
        return service.placeHold(req.patronId(), req.recordId());
    }

    @DeleteMapping("/holds/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelHold(@PathVariable Long id) {
        service.cancelHold(id, null);
    }

    // fines

    @GetMapping("/fines")
    public PageResponse<FineDto> fines(@RequestParam(required = false) FineStatus status,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        return PageResponse.of(service.listFines(status, PageRequest.of(page, Math.min(size, 100))), f -> f);
    }

    @GetMapping("/fines/patron/{patronId}")
    public List<FineDto> patronFines(@PathVariable Long patronId) {
        return service.finesOf(patronId);
    }

    @PostMapping("/fines/pay")
    public ReceiptDto pay(@Valid @RequestBody PayRequest req) {
        return service.pay(req.fineIds());
    }

    @GetMapping("/fines/receipts/{receiptNumber}")
    public ReceiptDto receipt(@PathVariable String receiptNumber) {
        return service.receipt(receiptNumber);
    }

    @PostMapping("/fines/{id}/waive")
    @PreAuthorize("hasAnyRole('ADMIN','LIBRARIAN')")
    public FineDto waive(@PathVariable Long id, @Valid @RequestBody WaiveRequest req) {
        return service.waive(id, req.reason());
    }

    @PostMapping("/fines")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','LIBRARIAN')")
    public FineDto addFine(@Valid @RequestBody ManualFineRequest req) {
        return service.addManualFine(req.patronId(), req.amount(), req.reason());
    }
}
