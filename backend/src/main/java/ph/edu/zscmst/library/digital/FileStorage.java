package ph.edu.zscmst.library.digital;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Stores uploaded files on disk under random keys, outside any web root. Files are only ever
 * served through authenticated API endpoints.
 */
@Component
public class FileStorage {

    public record StoredFile(String key, long size, String sha256) {
    }

    private final Path root;

    public FileStorage(@Value("${app.storage.dir}") String dir) throws IOException {
        this.root = Path.of(dir).toAbsolutePath().normalize();
        Files.createDirectories(root);
    }

    public StoredFile store(InputStream in) {
        String key = UUID.randomUUID().toString();
        Path tmp = root.resolve(key + ".part");
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            long size;
            try (DigestInputStream din = new DigestInputStream(in, sha)) {
                size = Files.copy(din, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(tmp, path(key), StandardCopyOption.ATOMIC_MOVE);
            return new StoredFile(key, size, HexFormat.of().formatHex(sha.digest()));
        } catch (IOException e) {
            deleteQuietly(tmp);
            throw new UncheckedIOException("Could not store file", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public Path path(String key) {
        Path p = root.resolve(key).normalize();
        if (!p.startsWith(root)) throw new IllegalArgumentException("Invalid storage key");
        return p;
    }

    public void delete(String key) {
        deleteQuietly(path(key));
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // an orphaned file is harmless; it is not reachable without a database row
        }
    }
}
