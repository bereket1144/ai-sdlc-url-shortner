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
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "runs")
@Getter
// JPA uses the generated protected constructor when loading an existing row.
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Run {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // Requirements can exceed the default 255-character string column.
    @Column(nullable = false, columnDefinition = "text")
    private String requirement;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private RunKind kind;

    // The earlier run that a BROWNFIELD run changes. Null for a GREENFIELD run. The API checks the pair; the database
    // only keeps the reference to an existing run.
    @Column(updatable = false)
    private UUID baseRunId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RunStatus status;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private RunFailureReason failureReason;

    // How many times the run has been queued: 1 until it is retried.
    @Column(nullable = false)
    private int attempt = 1;

    // Where the latest attempt starts: a stage name, or START for the beginning. Null until the run is retried or is
    // queued again after a reviewer's decision.
    @Column(length = 32)
    private String resumedFrom;

    // Set to true by RunStop (a bulk update, so that the version does not change under the thread that runs the graph)
    // when a reviewer asks a RUNNING run to stop. The graph thread reads it at its safe points. Cleared by start() and
    // by both stop transitions, so a request never reaches into a later attempt.
    @Column(nullable = false)
    private boolean stopRequested;

    // Hibernate sets this when the row is first inserted and never writes it again.
    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    // Hibernate sets this on insert and again on every update.
    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    // Optimistic locking: null until first saved, then 0, and +1 on every change. A wrapper type so that
    // Spring Data can tell a new run (null version) from a stored one.
    @Version
    @Column(nullable = false)
    private Long version;

    public Run(String requirement) {
        this(requirement, RunKind.GREENFIELD);
    }

    public Run(String requirement, RunKind kind) {
        this(requirement, kind, null);
    }

    /** @param baseRunId the run whose result this run changes; null when it changes none */
    public Run(String requirement, RunKind kind, UUID baseRunId) {
        if (requirement == null || requirement.isBlank()) {
            throw new IllegalArgumentException("requirement must not be blank");
        }
        if (kind == null) {
            throw new IllegalArgumentException("kind must not be null");
        }
        this.requirement = requirement;
        this.kind = kind;
        this.baseRunId = baseRunId;
        this.status = RunStatus.CREATED;
    }

    public void start() {
        if (status != RunStatus.CREATED) {
            throw new IllegalStateException(
                    "Only a CREATED run can be started");
        }
        this.stopRequested = false;
        this.status = RunStatus.RUNNING;
    }

    public void succeed() {
        if (status != RunStatus.RUNNING) {
            throw new IllegalStateException(
                    "Only a RUNNING run can succeed");
        }
        this.status = RunStatus.SUCCEEDED;
    }

    public void waitForInput() {
        if (status != RunStatus.RUNNING) {
            throw new IllegalStateException("Only a RUNNING run can wait for input");
        }
        status = RunStatus.WAITING_FOR_INPUT;
    }

    /** Re-enter the existing claim queue after a complete clarification reply. */
    public void resumeAfterInput() {
        if (status != RunStatus.WAITING_FOR_INPUT) {
            throw new IllegalStateException("Only a waiting run can resume");
        }
        status = RunStatus.CREATED;
    }

    /**
     * Queues a waiting run again after a reviewer's decision, to be attempted from {@code startsAt}: a stage name, as in
     * {@link #retry}. The run has not failed, so the attempt number is not used up.
     *
     * @throws IllegalStateException when the run is not waiting for input
     */
    public void resumeAt(String startsAt) {
        if (startsAt == null || startsAt.isBlank()) {
            throw new IllegalArgumentException("startsAt must not be blank");
        }
        if (status != RunStatus.WAITING_FOR_INPUT) {
            throw new IllegalStateException("Only a waiting run can resume");
        }
        this.resumedFrom = startsAt;
        this.status = RunStatus.CREATED;
    }

    /**
     * Ends a waiting run as FAILED, for a reviewer's decision that closes it. {@link #fail} stays for a RUNNING run only
     * (AC-EXEC-5.3); a run that waits is not running, so its end is a transition of its own.
     *
     * @throws IllegalStateException when the run is not waiting for input
     */
    public void failWhileWaiting(RunFailureReason reason) {
        if (reason == null) {
            throw new IllegalArgumentException("failure reason must not be null");
        }
        if (status != RunStatus.WAITING_FOR_INPUT) {
            throw new IllegalStateException("Only a waiting run can end while it waits");
        }
        this.failureReason = reason;
        this.status = RunStatus.FAILED;
    }

    public void fail() {
        fail(RunFailureReason.UNEXPECTED);
    }

    public void fail(RunFailureReason reason) {
        if (status != RunStatus.RUNNING) {
            throw new IllegalStateException(
                    "Only a RUNNING run can fail");
        }
        if (reason == null) {
            throw new IllegalArgumentException("failure reason must not be null");
        }
        this.failureReason = reason;
        this.status = RunStatus.FAILED;
    }

    /**
     * Queues a FAILED run again, to be attempted from {@code startsAt}: a stage name, or START for the beginning. The
     * failure reason is cleared, and set again if this attempt fails. The run is not changed when it is refused.
     *
     * @param maxAttempts how many attempts a run may have in all
     * @throws IllegalStateException when the run is not FAILED or has no attempts left; the message is fixed text
     */
    public void retry(String startsAt, int maxAttempts) {
        if (startsAt == null || startsAt.isBlank()) {
            throw new IllegalArgumentException("startsAt must not be blank");
        }
        if (status != RunStatus.FAILED) {
            throw new IllegalStateException("Only a failed run can be retried.");
        }
        if (attempt >= maxAttempts) {
            throw new IllegalStateException("The run has no attempts left.");
        }
        this.attempt++;
        this.resumedFrom = startsAt;
        this.failureReason = null;
        this.status = RunStatus.CREATED;
    }

    /**
     * Stops a run that is not running, at once: a CREATED run, which the scheduler then never claims, or a run that
     * waits for input. A RUNNING run is asked to stop instead (see {@link RunRepository#requestStop}) and ends with
     * {@link #stopped}.
     *
     * @throws IllegalStateException when the run is not CREATED or waiting for input
     */
    public void stopAtOnce() {
        if (status != RunStatus.CREATED && status != RunStatus.WAITING_FOR_INPUT) {
            throw new IllegalStateException("Only a created or waiting run can be stopped at once");
        }
        this.stopRequested = false;
        this.status = RunStatus.STOPPED;
    }

    /**
     * Ends the attempt of a RUNNING run that a reviewer asked to stop. It is not a failure: there is no failure reason,
     * and the run can be resumed.
     *
     * @throws IllegalStateException when the run is not RUNNING
     */
    public void stopped() {
        if (status != RunStatus.RUNNING) {
            throw new IllegalStateException("Only a RUNNING run can end as stopped");
        }
        this.stopRequested = false;
        this.status = RunStatus.STOPPED;
    }

    /**
     * Queues a STOPPED run again, to be attempted from {@code startsAt}: a stage name, or START for the beginning, as
     * in {@link #retry}. The run did not fail, so the attempt number is not used up.
     *
     * @throws IllegalStateException when the run is not STOPPED
     */
    public void resumeStopped(String startsAt) {
        if (startsAt == null || startsAt.isBlank()) {
            throw new IllegalArgumentException("startsAt must not be blank");
        }
        if (status != RunStatus.STOPPED) {
            throw new IllegalStateException("Only a stopped run can be resumed");
        }
        this.resumedFrom = startsAt;
        this.status = RunStatus.CREATED;
    }

    /**
     * Puts a STOPPED run that was waiting for answers or for a decision back into that wait. What it waits for is
     * stored, so nothing else changes and no attempt is queued.
     *
     * @throws IllegalStateException when the run is not STOPPED
     */
    public void waitAgain() {
        if (status != RunStatus.STOPPED) {
            throw new IllegalStateException("Only a stopped run can wait again");
        }
        this.status = RunStatus.WAITING_FOR_INPUT;
    }

}
