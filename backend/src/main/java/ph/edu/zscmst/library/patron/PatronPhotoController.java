package ph.edu.zscmst.library.patron;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import ph.edu.zscmst.library.common.ApiException;
import ph.edu.zscmst.library.common.AuditService;
import ph.edu.zscmst.library.common.SystemSettings;
import ph.edu.zscmst.library.digital.FileStorage;
import ph.edu.zscmst.library.patron.PatronDtos.PatronDto;

/**
 * Patron photos (upload or webcam capture at the desk). Photos are served under an unguessable
 * random key so the circulation desk and attendance kiosk can show them in plain <img> tags.
 */
@RestController
public class PatronPhotoController {


    private final PatronRepository patrons;
    private final FileStorage storage;
    private final AuditService audit;
    private final SystemSettings system;

    public PatronPhotoController(PatronRepository patrons, FileStorage storage, AuditService audit, SystemSettings system) {
        this.system = system;
        this.patrons = patrons;
        this.storage = storage;
        this.audit = audit;
    }

    @PostMapping(path = "/api/patrons/{id}/photo", consumes = "multipart/form-data")
    @Transactional
    public PatronDto upload(@PathVariable Long id, @RequestPart("file") MultipartFile file) throws IOException {
        Patron p = patrons.findById(id).orElseThrow(() -> ApiException.notFound("Patron"));
        if (file.isEmpty()) throw ApiException.badRequest("Choose a photo");
        system.checkUpload(SystemSettings.UploadKind.PHOTO, file.getSize());
        FileStorage.StoredFile stored;
        try (InputStream raw = file.getInputStream(); BufferedInputStream in = new BufferedInputStream(raw)) {
            in.mark(32);
            byte[] head = in.readNBytes(12);
            in.reset();
            if (imageType(head) == null) throw ApiException.badRequest("Upload a JPEG, PNG or WebP photo");
            stored = storage.store(in);
        }
        String old = p.getPhotoKey();
        p.setPhotoKey(stored.key());
        p.setPhotoUrl("/api/opac/photos/" + stored.key());
        patrons.flush();
        if (old != null) storage.delete(old);
        audit.record("PATRON_PHOTO", p.getCardNumber());
        return PatronDto.of(p);
    }

    @DeleteMapping("/api/patrons/{id}/photo")
    @Transactional
    public PatronDto remove(@PathVariable Long id) {
        Patron p = patrons.findById(id).orElseThrow(() -> ApiException.notFound("Patron"));
        if (p.getPhotoKey() != null) storage.delete(p.getPhotoKey());
        p.setPhotoKey(null);
        p.setPhotoUrl(null);
        return PatronDto.of(p);
    }

    @GetMapping("/api/opac/photos/{key}")
    public ResponseEntity<Resource> photo(@PathVariable String key) throws IOException {
        if (!key.matches("[0-9a-f\\-]{36}")) throw ApiException.notFound("Photo");
        Path path = storage.path(key);
        if (!Files.exists(path)) throw ApiException.notFound("Photo");
        byte[] head;
        try (InputStream in = Files.newInputStream(path)) {
            head = in.readNBytes(12);
        }
        return ResponseEntity.ok()
                .contentType(Optional.ofNullable(imageType(head)).orElse(MediaType.APPLICATION_OCTET_STREAM))
                .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePrivate())
                .body(new FileSystemResource(path));
    }

    private static MediaType imageType(byte[] h) {
        if (h.length >= 3 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF) return MediaType.IMAGE_JPEG;
        if (h.length >= 4 && (h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G') return MediaType.IMAGE_PNG;
        if (h.length >= 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F' && h[8] == 'W' && h[9] == 'E'
                && h[10] == 'B' && h[11] == 'P') return MediaType.parseMediaType("image/webp");
        return null;
    }
}
