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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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

    /**
     * Deletes a stored file. Inside a transaction the file goes only once it commits, so a rolled-back
     * delete or replace never leaves a database row pointing at a missing file.
     */
    public void delete(String key) {
        Path p = path(key);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    deleteQuietly(p);
                }
            });
        } else {
            deleteQuietly(p);
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // an orphaned file is harmless; it is not reachable without a database row
        }
    }
}
