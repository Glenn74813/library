package ph.edu.zscmst.library.catalog;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import ph.edu.zscmst.library.digital.DigitalService.DigitalSummary;

public final class CatalogDtos {

    private CatalogDtos() {
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** Parses the stored custom-field JSON; tolerant of empty or malformed values. */
    public static Map<String, String> customValues(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            Map<String, String> out = new LinkedHashMap<>();
            JSON.readTree(json).properties().forEach(e -> out.put(e.getKey(), e.getValue().asString()));
            return out;
        } catch (JacksonException e) {
            return Map.of();
        }
    }

    public static String customJson(Map<String, String> values) {
        return values == null || values.isEmpty() ? null : JSON.writeValueAsString(values);
    }

    public record Availability(int total, int available) {
        public static final Availability NONE = new Availability(0, 0);
    }

    public record RecordDto(Long id, String title, String subtitle, String authors, String isbn, String publisher,
                            Integer publicationYear, String edition, String subjects, String callNumber,
                            MaterialType materialType, String language, String summary, int totalCopies,
                            int availableCopies, Instant createdAt, Instant updatedAt, DigitalSummary ebook,
                            Map<String, String> customFields) {
        public static RecordDto of(BibRecord r, Availability a) {
            return of(r, a, null);
        }

        public static RecordDto of(BibRecord r, Availability a, DigitalSummary ebook) {
            return new RecordDto(r.getId(), r.getTitle(), r.getSubtitle(), r.getAuthors(), r.getIsbn(),
                    r.getPublisher(), r.getPublicationYear(), r.getEdition(), r.getSubjects(), r.getCallNumber(),
                    r.getMaterialType(), r.getLanguage(), r.getSummary(), a.total(), a.available(),
                    r.getCreatedAt(), r.getUpdatedAt(), ebook, customValues(r.getCustomFields()));
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
                                String summary,
                                Map<String, @Size(max = 500) String> customFields) {
    }

    public record ItemDto(Long id, Long recordId, String title, String callNumber, String barcode, String rfidEpc,
                          String location, String aisle, String shelf, String shelfLocation, ItemStatus status,
                          LocalDate acquiredDate, BigDecimal price, String notes, Instant dueAt,
                          boolean roomUseOnly, Boolean roomUseOverride) {
        public static ItemDto of(Item i, Instant dueAt, boolean roomUseOnly) {
            BibRecord r = i.getRecord();
            return new ItemDto(i.getId(), r.getId(), r.getTitle(), r.getCallNumber(), i.getBarcode(), i.getRfidEpc(),
                    i.getLocation(), i.getAisle(), i.getShelf(), i.getShelfLocation(), i.getStatus(), i.getAcquiredDate(),
                    i.getPrice(), i.getNotes(), dueAt, roomUseOnly, i.getRoomUseOnly());
        }
    }

    public record ItemRequest(@NotBlank @Size(max = 50) String barcode,
                              @Size(max = 64) String rfidEpc,
                              @Size(max = 100) String location,
                              @Size(max = 20) String aisle,
                              @Size(max = 20) String shelf,
                              /** null = follow the material type; true = room use only; false = can be borrowed */
                              Boolean roomUseOnly,
                              ItemStatus status,
                              LocalDate acquiredDate,
                              BigDecimal price,
                              @Size(max = 500) String notes) {
    }

    /** Public view used by OPAC kiosks: no internal identifiers beyond what a patron needs. */
    public record OpacItemDto(String barcode, String location, String aisle, String shelf, String shelfLocation,
                              String callNumber, ItemStatus status, Instant dueAt, boolean roomUseOnly) {
    }

    /** A custom field shown publicly, with its label. */
    public record LabeledValue(String label, String value) {
    }

    public record OpacDetailDto(RecordDto record, List<OpacItemDto> items, List<LabeledValue> extraFields) {
    }

    public record IsbnLookupResult(String isbn, String title, String subtitle, String authors, String publisher,
                                   Integer publicationYear, String subjects, String coverUrl) {
    }
}
