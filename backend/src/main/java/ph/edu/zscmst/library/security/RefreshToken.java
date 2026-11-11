package ph.edu.zscmst.library.security;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Opaque refresh token; only its SHA-256 hash is stored. */
@Entity
@Table(name = "refresh_token")
public class RefreshToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String tokenHash;
    @Enumerated(EnumType.STRING)
    private TokenService.SubjectType subjectType;
    private Long subjectId;
    private Instant expiresAt;
    private boolean revoked;
    private Instant createdAt = Instant.now();

    protected RefreshToken() {
    }

    public RefreshToken(String tokenHash, TokenService.SubjectType subjectType, Long subjectId, Instant expiresAt) {
        this.tokenHash = tokenHash;
        this.subjectType = subjectType;
        this.subjectId = subjectId;
        this.expiresAt = expiresAt;
    }

    public TokenService.SubjectType getSubjectType() { return subjectType; }
    public Long getSubjectId() { return subjectId; }
    public Instant getExpiresAt() { return expiresAt; }
    public boolean isRevoked() { return revoked; }
    public void revoke() { this.revoked = true; }
}
