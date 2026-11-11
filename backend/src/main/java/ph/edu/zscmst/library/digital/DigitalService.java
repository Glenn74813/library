package ph.edu.zscmst.library.digital;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import ph.edu.zscmst.library.catalog.BibRecord;
import ph.edu.zscmst.library.catalog.BibRecordRepository;
import ph.edu.zscmst.library.catalog.MaterialType;
import ph.edu.zscmst.library.common.ApiException;
import ph.edu.zscmst.library.common.AuditService;

/**
 * E-books. A title is either <b>school-owned</b> (the library uploads the PDF/EPUB and every
 * signed-in patron can read it in the app) or <b>commercial</b> (only a link to the vendor's
 * platform is stored; the file is never uploaded or served here).
 */
@Service
public class DigitalService {

    public static final String SCHOOL_OWNED = "SCHOOL_OWNED";
    public static final String COMMERCIAL = "COMMERCIAL";

    /** Shown on catalog/OPAC results. */
    public record DigitalSummary(String kind, EbookFormat format, String provider, String externalUrl) {
    }

    /** Full digital details for the staff record page. */
    public record DigitalInfo(String kind, String filename, EbookFormat format, Long sizeBytes, Instant uploadedAt,
                              String uploadedBy, String externalUrl, String externalProvider, long views) {
    }

    public record EbookDto(Long recordId, String title, String authors, Integer publicationYear,
                           MaterialType materialType, String kind, EbookFormat format, Long sizeBytes,
                           String provider, String externalUrl) {
    }

    public record OpenFile(Path path, EbookFormat format, String filename, long size) {
    }

    private final BibRecordRepository records;
    private final DigitalFileRepository files;
    private final EbookViewRepository views;
    private final FileStorage storage;
    private final AuditService audit;

    public DigitalService(BibRecordRepository records, DigitalFileRepository files, EbookViewRepository views,
                          FileStorage storage, AuditService audit) {
        this.records = records;
        this.files = files;
        this.views = views;
        this.storage = storage;
        this.audit = audit;
    }

    private BibRecord record(Long id) {
        return records.findById(id).orElseThrow(() -> ApiException.notFound("Record"));
    }

    @Transactional(readOnly = true)
    public DigitalInfo info(Long recordId) {
        BibRecord r = record(recordId);
        DigitalFile f = files.findByRecordId(recordId).orElse(null);
        String kind = f != null ? SCHOOL_OWNED : r.getExternalUrl() != null ? COMMERCIAL : null;
        return new DigitalInfo(kind, f == null ? null : f.getOriginalFilename(), f == null ? null : f.getFormat(),
                f == null ? null : f.getSizeBytes(), f == null ? null : f.getUploadedAt(),
                f == null ? null : f.getUploadedBy(), r.getExternalUrl(), r.getExternalProvider(),
                f == null ? 0 : views.countByRecordId(recordId));
    }

    /** E-book availability for a page of records (used by catalog and OPAC listings). */
    @Transactional(readOnly = true)
    public Map<Long, DigitalSummary> summaries(Collection<BibRecord> list) {
        Map<Long, DigitalSummary> result = new HashMap<>();
        if (list.isEmpty()) return result;
        Map<Long, DigitalFile> owned = new HashMap<>();
        files.findByRecordIdIn(list.stream().map(BibRecord::getId).toList())
                .forEach(f -> owned.put(f.getRecord().getId(), f));
        for (BibRecord r : list) {
            DigitalFile f = owned.get(r.getId());
            if (f != null) {
                result.put(r.getId(), new DigitalSummary(SCHOOL_OWNED, f.getFormat(), null, null));
            } else if (r.getExternalUrl() != null) {
                result.put(r.getId(), new DigitalSummary(COMMERCIAL, null, r.getExternalProvider(), r.getExternalUrl()));
            }
        }
        return result;
    }

    @Transactional
    public DigitalInfo upload(Long recordId, MultipartFile upload) {
        BibRecord r = record(recordId);
        if (r.getExternalUrl() != null) {
            throw ApiException.conflict("This title is a commercial e-book (vendor link). Commercial e-books are not "
                    + "uploaded; remove the vendor link only if the school owns the rights to this title.");
        }
        if (upload.isEmpty()) throw ApiException.badRequest("The file is empty");
        String filename = cleanFilename(upload.getOriginalFilename());

        FileStorage.StoredFile stored;
        EbookFormat format;
        try (InputStream raw = upload.getInputStream(); BufferedInputStream in = new BufferedInputStream(raw)) {
            in.mark(128);
            byte[] head = in.readNBytes(64);
            in.reset();
            format = EbookFormat.detect(head);
            if (format == null) throw ApiException.badRequest("Only PDF and EPUB files can be uploaded");
            stored = storage.store(in);
        } catch (IOException e) {
            throw ApiException.badRequest("Could not read the uploaded file");
        }

        DigitalFile f = files.findByRecordId(recordId).orElseGet(() -> new DigitalFile(r));
        String previousKey = f.getStorageKey();
        f.replaceContent(stored, filename, format, AuditService.currentActor());
        files.saveAndFlush(f);
        if (previousKey != null) storage.delete(previousKey);
        audit.record("EBOOK_UPLOAD", "record " + recordId + " " + filename + " (" + stored.size() + " bytes)");
        return info(recordId);
    }

    @Transactional
    public void removeFile(Long recordId) {
        files.findByRecordId(recordId).ifPresent(f -> {
            files.delete(f);
            files.flush();
            storage.delete(f.getStorageKey());
            audit.record("EBOOK_REMOVE", "record " + recordId + " " + f.getOriginalFilename());
        });
    }

    @Transactional
    public DigitalInfo setVendorLink(Long recordId, String url, String provider) {
        BibRecord r = record(recordId);
        String u = url == null || url.isBlank() ? null : url.trim();
        if (u != null) {
            if (files.existsByRecordId(recordId)) {
                throw ApiException.conflict("This title has a school-owned file. Remove the file first; "
                        + "a title is either school-owned or commercial.");
            }
            try {
                String scheme = URI.create(u).getScheme();
                if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) throw new IllegalArgumentException();
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("Vendor link must be a web address starting with https://");
            }
        }
        r.setExternalUrl(u);
        r.setExternalProvider(u == null || provider == null || provider.isBlank() ? null : provider.trim());
        records.flush();
        audit.record("EBOOK_VENDOR_LINK", "record " + recordId + " " + (u == null ? "removed" : u));
        return info(recordId);
    }

    @Transactional(readOnly = true)
    public Page<EbookDto> ebooks(String q, Pageable pageable) {
        Page<BibRecord> page = files.findEbookRecords(q == null ? "" : q.trim(), pageable);
        Map<Long, DigitalFile> owned = new HashMap<>();
        files.findByRecordIdIn(page.map(BibRecord::getId).getContent()).forEach(f -> owned.put(f.getRecord().getId(), f));
        return page.map(r -> {
            DigitalFile f = owned.get(r.getId());
            return new EbookDto(r.getId(), r.getTitle(), r.getAuthors(), r.getPublicationYear(), r.getMaterialType(),
                    f != null ? SCHOOL_OWNED : COMMERCIAL, f == null ? null : f.getFormat(),
                    f == null ? null : f.getSizeBytes(), f == null ? r.getExternalProvider() : null,
                    f == null ? r.getExternalUrl() : null);
        });
    }

    /** Opens a school-owned e-book for reading; logs the view when a patron reads it. */
    @Transactional
    public OpenFile open(Long recordId, Long patronId) {
        DigitalFile f = files.findByRecordId(recordId).orElseThrow(() -> ApiException.notFound("E-book file"));
        if (patronId != null) views.save(new EbookView(recordId, patronId));
        return new OpenFile(storage.path(f.getStorageKey()), f.getFormat(), f.getOriginalFilename(), f.getSizeBytes());
    }

    /** Streams the file inline (for the in-app reader), never cached by browsers or proxies. */
    public static ResponseEntity<Resource> inline(OpenFile f) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(f.format().contentType()))
                .contentLength(f.size())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename(f.filename(), StandardCharsets.UTF_8).build().toString())
                .cacheControl(CacheControl.noStore())
                .body(new FileSystemResource(f.path()));
    }

    private static String cleanFilename(String name) {
        String n = name == null ? "ebook" : name.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}\"]", "").trim();
        if (n.isEmpty()) n = "ebook";
        return n.length() > 200 ? n.substring(n.length() - 200) : n;
    }
}
