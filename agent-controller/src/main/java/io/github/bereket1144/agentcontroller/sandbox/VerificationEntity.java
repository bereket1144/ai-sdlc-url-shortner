package io.github.bereket1144.agentcontroller.sandbox;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

/**
 * One row of {@code verification_result}. It has no setters and is {@link Immutable}, so a stored result is never
 * changed. It is package-private: the rest of the platform sees only {@link VerificationResult}. The column
 * {@code recorded_at} is filled by the database and is not mapped.
 */
@Entity
@Table(name = "verification_result")
@Immutable
class VerificationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private UUID runId;

    @Column(nullable = false, length = 64)
    private String commitId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private VerificationResult.Outcome outcome;

    @Column(nullable = false)
    private int testsRun;

    @Column(nullable = false)
    private int testsPassed;

    @Column(nullable = false)
    private int testsFailed;

    @Column(nullable = false, columnDefinition = "text")
    private String failingTests;

    @Column(nullable = false, columnDefinition = "text")
    private String excerpt;

    /** Required by JPA. */
    protected VerificationEntity() {
    }

    VerificationEntity(UUID runId, VerificationResult result) {
        this.runId = runId;
        this.commitId = result.commitId();
        this.outcome = result.outcome();
        this.testsRun = result.testsRun();
        this.testsPassed = result.testsPassed();
        this.testsFailed = result.testsFailed();
        this.failingTests = String.join("\n", result.failingTests());
        this.excerpt = result.excerpt();
    }

    VerificationResult toResult() {
        List<String> names = failingTests.isEmpty() ? List.of() : Arrays.asList(failingTests.split("\n"));
        return new VerificationResult(outcome, testsRun, testsPassed, testsFailed, names, excerpt, commitId);
    }
}
