package ph.edu.zscmst.library.digital;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

import ph.edu.zscmst.library.catalog.BibRecord;

/** Uploaded file of a school-owned e-book. The bytes live in {@link FileStorage} under {@code storageKey}. */
@Entity
@Table(name = "digital_file")
public class DigitalFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "record_id")
    private BibRecord record;
    private String storageKey;
    private String originalFilename;
    private String format;
    private long sizeBytes;
    private String sha256;
    private String uploadedBy;
    private Instant uploadedAt = Instant.now();

    protected DigitalFile() {
    }

    public DigitalFile(BibRecord record) {
        this.record = record;
    }

    public void replaceContent(FileStorage.StoredFile stored, String originalFilename, EbookFormat format, String by) {
        this.storageKey = stored.key();
        this.sizeBytes = stored.size();
        this.sha256 = stored.sha256();
        this.originalFilename = originalFilename;
        this.format = format.name();
        this.uploadedBy = by;
        this.uploadedAt = Instant.now();
    }

    public Long getId() { return id; }
    public BibRecord getRecord() { return record; }
    public String getStorageKey() { return storageKey; }
    public String getOriginalFilename() { return originalFilename; }
    public EbookFormat getFormat() { return EbookFormat.valueOf(format); }
    public long getSizeBytes() { return sizeBytes; }
    public String getSha256() { return sha256; }
    public String getUploadedBy() { return uploadedBy; }
    public Instant getUploadedAt() { return uploadedAt; }
}
