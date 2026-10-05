package io.github.bereket1144.agentcontroller.run;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** References the immutable batch artifact; answering never rewrites the agent's original output. */
@Entity
@Table(name = "clarification_batch")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ClarificationBatch {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private UUID runId;
    @Column(nullable = false)
    private int artifactVersion;
    @Column(columnDefinition = "text")
    private String answers;
    private Instant answeredAt;

    ClarificationBatch(UUID runId, int artifactVersion) {
        this.runId = runId;
        this.artifactVersion = artifactVersion;
    }

    void answer(String answers) {
        this.answers = answers;
        answeredAt = Instant.now();
    }
}
