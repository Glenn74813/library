package ph.edu.zscmst.library.patron;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class PatronDtos {

    private PatronDtos() {
    }

    public record PatronDto(Long id, String cardNumber, String rfidUid, String firstName, String lastName,
                            String fullName, String email, String phone, Long categoryId, String categoryName,
                            Long courseId, String courseCode, String photoUrl, LocalDate expiryDate,
                            boolean blocked, String blockReason, boolean hasPin, Instant createdAt, Instant anonymizedAt) {
        public static PatronDto of(Patron p) {
            return new PatronDto(p.getId(), p.getCardNumber(), p.getRfidUid(), p.getFirstName(), p.getLastName(),
                    p.getFullName(), p.getEmail(), p.getPhone(), p.getCategory().getId(), p.getCategory().getName(),
                    p.getCourse() == null ? null : p.getCourse().getId(),
                    p.getCourse() == null ? null : p.getCourse().getCode(), p.getPhotoUrl(), p.getExpiryDate(),
                    p.isBlocked(), p.getBlockReason(), p.getPinHash() != null, p.getCreatedAt(), p.getAnonymizedAt());
        }
    }

    /** {@code cardNumber}: blank on a new patron means "assign the next library ID number" (e.g. 2026-0002). */
    public record PatronRequest(@Size(max = 40) String cardNumber,
                                @Size(max = 64) String rfidUid,
                                @NotBlank @Size(max = 80) String firstName,
                                @NotBlank @Size(max = 80) String lastName,
                                @Email @Size(max = 150) String email,
                                @Size(max = 40) String phone,
                                @NotNull Long categoryId,
                                Long courseId,
                                @Size(max = 500) String photoUrl,
                                LocalDate expiryDate,
                                boolean blocked,
                                @Size(max = 255) String blockReason) {
    }

    public record PinRequest(@NotBlank @Pattern(regexp = "\\d{4,8}", message = "must be 4 to 8 digits") String pin) {
    }

    public record ImportResult(int created, int updated, List<String> errors) {
    }
}
