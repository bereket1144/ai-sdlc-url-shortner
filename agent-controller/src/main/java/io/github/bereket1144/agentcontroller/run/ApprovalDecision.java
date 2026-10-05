package io.github.bereket1144.agentcontroller.run;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Immutable;

/**
 * One row of {@code approval_decision}: who decided what about which version of which artifact, and when. Immutable, so
 * a stored decision is never changed. Package-private, with a package-private constructor: only
 * {@link ApprovalService} stores one.
 */
@Entity
@Table(name = "approval_decision")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class ApprovalDecision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private UUID runId;

    @Column(nullable = false, updatable = false, length = 64)
    private String stage;

    @Column(nullable = false, updatable = false)
    private String artifactKind;

    @Column(nullable = false, updatable = false)
    private int artifactVersion;

    @Column(nullable = false, updatable = false, length = 64)
    private String fingerprint;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 16)
    private ApprovalVerdict decision;

    @Column(nullable = false, updatable = false, length = 100)
    private String approver;

    @Column(updatable = false, columnDefinition = "text")
    private String comment;

    // Hibernate sets this when the row is inserted.
    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant decidedAt;

    ApprovalDecision(UUID runId, String stage, String artifactKind, int artifactVersion, String fingerprint,
                     ApprovalVerdict decision, String approver, String comment) {
        this.runId = runId;
        this.stage = stage;
        this.artifactKind = artifactKind;
        this.artifactVersion = artifactVersion;
        this.fingerprint = fingerprint;
        this.decision = decision;
        this.approver = approver;
        this.comment = comment;
    }
}
