package ph.edu.zscmst.library.catalog;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/** Bibliographic record (the "title"); physical copies are {@link Item}s. */
@Entity
@Table(name = "bib_record")
public class BibRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String title;
    private String subtitle;
    private String authors;
    private String isbn;
    private String publisher;
    private Integer publicationYear;
    private String edition;
    private String subjects;
    private String callNumber;
    @Enumerated(EnumType.STRING)
    private MaterialType materialType = MaterialType.BOOK;
    private String language;
    private String summary;
    /** Vendor platform link for commercial e-books (the file itself is never hosted here). */
    private String externalUrl;
    private String externalProvider;
    /** Values of library-defined custom fields as a JSON object (key -> value). */
    private String customFields;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSubtitle() { return subtitle; }
    public void setSubtitle(String subtitle) { this.subtitle = subtitle; }
    public String getAuthors() { return authors; }
    public void setAuthors(String authors) { this.authors = authors; }
    public String getIsbn() { return isbn; }
    public void setIsbn(String isbn) { this.isbn = isbn; }
    public String getPublisher() { return publisher; }
    public void setPublisher(String publisher) { this.publisher = publisher; }
    public Integer getPublicationYear() { return publicationYear; }
    public void setPublicationYear(Integer publicationYear) { this.publicationYear = publicationYear; }
    public String getEdition() { return edition; }
    public void setEdition(String edition) { this.edition = edition; }
    public String getSubjects() { return subjects; }
    public void setSubjects(String subjects) { this.subjects = subjects; }
    public String getCallNumber() { return callNumber; }
    public void setCallNumber(String callNumber) { this.callNumber = callNumber; }
    public MaterialType getMaterialType() { return materialType; }
    public void setMaterialType(MaterialType materialType) { this.materialType = materialType; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getExternalUrl() { return externalUrl; }
    public void setExternalUrl(String externalUrl) { this.externalUrl = externalUrl; }
    public String getExternalProvider() { return externalProvider; }
    public String getCustomFields() { return customFields; }
    public void setCustomFields(String customFields) { this.customFields = customFields; }
    public void setExternalProvider(String externalProvider) { this.externalProvider = externalProvider; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
