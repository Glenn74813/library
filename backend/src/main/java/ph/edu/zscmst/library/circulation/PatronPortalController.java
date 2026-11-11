package ph.edu.zscmst.library.circulation;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import ph.edu.zscmst.library.circulation.CirculationDtos.FineDto;
import ph.edu.zscmst.library.circulation.CirculationDtos.HoldDto;
import ph.edu.zscmst.library.circulation.CirculationDtos.LoanDto;
import ph.edu.zscmst.library.circulation.CirculationDtos.PatronSummary;
import ph.edu.zscmst.library.circulation.CirculationDtos.RecordHoldBody;
import ph.edu.zscmst.library.common.ApiException;
import ph.edu.zscmst.library.common.PageResponse;
import ph.edu.zscmst.library.patron.Patron;
import ph.edu.zscmst.library.patron.PatronRepository;
import ph.edu.zscmst.library.security.TokenService;

/** Patron self-service (mobile / responsive web): own loans, renewals, holds and fines. */
@RestController
@RequestMapping("/api/me")
public class PatronPortalController {

    public record ChangePinRequest(@NotBlank String currentPin,
                                   @NotBlank @Pattern(regexp = "\\d{4,8}", message = "must be 4 to 8 digits") String newPin) {
    }

    private final CirculationService service;
    private final PatronRepository patrons;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokens;
    private final RenewalService renewals;

    public PatronPortalController(CirculationService service, PatronRepository patrons,
                                  PasswordEncoder passwordEncoder, TokenService tokens, RenewalService renewals) {
        this.renewals = renewals;
        this.service = service;
        this.patrons = patrons;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
    }

    private static Long patronId(Jwt jwt) {
        Number id = jwt.getClaim("patronId");
        if (id == null) throw new ApiException(HttpStatus.FORBIDDEN, "Not a patron token");
        return id.longValue();
    }

    @GetMapping
    public PatronSummary me(@AuthenticationPrincipal Jwt jwt) {
        return service.summary(patronId(jwt));
    }

    @GetMapping("/loans/history")
    public PageResponse<LoanDto> history(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "0") int page) {
        return PageResponse.of(service.history(patronId(jwt), PageRequest.of(page, 20)), l -> l);
    }

    /** Asks to renew; with renewal approval on, staff decide (see {@link RenewalService}). */
    @PostMapping("/loans/{loanId}/renew")
    public RenewalService.Outcome renew(@AuthenticationPrincipal Jwt jwt, @PathVariable Long loanId) {
        return renewals.request(loanId, patronId(jwt), RenewalRequest.Channel.PORTAL);
    }

    @GetMapping("/renewals")
    public List<RenewalService.RenewalDto> renewals(@AuthenticationPrincipal Jwt jwt) {
        return renewals.ofPatron(patronId(jwt));
    }

    @GetMapping("/holds")
    public List<HoldDto> holds(@AuthenticationPrincipal Jwt jwt) {
        return service.holdsOf(patronId(jwt));
    }

    @PostMapping("/holds")
    @ResponseStatus(HttpStatus.CREATED)
    public HoldDto placeHold(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody RecordHoldBody req) {
        return service.placeHold(patronId(jwt), req.recordId(), req.pickupDate());
    }

    @GetMapping("/holds/calendar/{recordId}")
    public CirculationDtos.BookingCalendar calendar(@AuthenticationPrincipal Jwt jwt, @PathVariable Long recordId) {
        return service.bookingCalendar(recordId, patronId(jwt));
    }

    @DeleteMapping("/holds/{holdId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelHold(@AuthenticationPrincipal Jwt jwt, @PathVariable Long holdId) {
        service.cancelHold(holdId, patronId(jwt));
    }

    @GetMapping("/fines")
    public List<FineDto> fines(@AuthenticationPrincipal Jwt jwt) {
        return service.finesOf(patronId(jwt));
    }

    @PostMapping("/pin")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void changePin(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ChangePinRequest req) {
        Patron p = patrons.findById(patronId(jwt)).orElseThrow(() -> ApiException.notFound("Patron"));
        if (p.getPinHash() == null || !passwordEncoder.matches(req.currentPin(), p.getPinHash())) {
            throw ApiException.badRequest("Current PIN is incorrect");
        }
        p.setPinHash(passwordEncoder.encode(req.newPin()));
        tokens.revokeAll(TokenService.SubjectType.PATRON, p.getId());
    }
}
