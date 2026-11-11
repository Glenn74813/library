package ph.edu.zscmst.library.catalog;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import ph.edu.zscmst.library.digital.DigitalService.DigitalSummary;

public final class CatalogDtos {

    private CatalogDtos() {
    }

    public record Availability(int total, int available) {
        public static final Availability NONE = new Availability(0, 0);
    }

    public record RecordDto(Long id, String title, String subtitle, String authors, String isbn, String publisher,
                            Integer publicationYear, String edition, String subjects, String callNumber,
                            MaterialType materialType, String language, String summary, int totalCopies,
                            int availableCopies, Instant createdAt, Instant updatedAt, DigitalSummary ebook) {
        public static RecordDto of(BibRecord r, Availability a) {
            return of(r, a, null);
        }

        public static RecordDto of(BibRecord r, Availability a, DigitalSummary ebook) {
            return new RecordDto(r.getId(), r.getTitle(), r.getSubtitle(), r.getAuthors(), r.getIsbn(),
                    r.getPublisher(), r.getPublicationYear(), r.getEdition(), r.getSubjects(), r.getCallNumber(),
                    r.getMaterialType(), r.getLanguage(), r.getSummary(), a.total(), a.available(),
                    r.getCreatedAt(), r.getUpdatedAt(), ebook);
        }
    }

    public record RecordDetailDto(RecordDto record, List<ItemDto> items) {
    }

    public record RecordRequest(@NotBlank @Size(max = 500) String title,
                                @Size(max = 500) String subtitle,
                                @Size(max = 500) String authors,
                                @Size(max = 20) String isbn,
                                @Size(max = 255) String publisher,
                                @Min(1000) @Max(2200) Integer publicationYear,
                                @Size(max = 60) String edition,
                                @Size(max = 1000) String subjects,
                                @Size(max = 100) String callNumber,
                                @NotNull MaterialType materialType,
                                @Size(max = 40) String language,
                                String summary) {
    }

    public record ItemDto(Long id, Long recordId, String title, String callNumber, String barcode, String rfidEpc,
                          String location, ItemStatus status, LocalDate acquiredDate, BigDecimal price, String notes,
                          Instant dueAt) {
        public static ItemDto of(Item i, Instant dueAt) {
            BibRecord r = i.getRecord();
            return new ItemDto(i.getId(), r.getId(), r.getTitle(), r.getCallNumber(), i.getBarcode(), i.getRfidEpc(),
                    i.getLocation(), i.getStatus(), i.getAcquiredDate(), i.getPrice(), i.getNotes(), dueAt);
        }
    }

    public record ItemRequest(@NotBlank @Size(max = 50) String barcode,
                              @Size(max = 64) String rfidEpc,
                              @Size(max = 100) String location,
                              ItemStatus status,
                              LocalDate acquiredDate,
                              BigDecimal price,
                              @Size(max = 500) String notes) {
    }

    /** Public view used by OPAC kiosks: no internal identifiers beyond what a patron needs. */
    public record OpacItemDto(String barcode, String location, String callNumber, ItemStatus status, Instant dueAt) {
    }

    public record OpacDetailDto(RecordDto record, List<OpacItemDto> items) {
    }

    public record IsbnLookupResult(String isbn, String title, String subtitle, String authors, String publisher,
                                   Integer publicationYear, String subjects, String coverUrl) {
    }
}
