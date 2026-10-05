package io.github.bereket1144.agentcontroller.model;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Durable claim for an in-flight call. A crash leaves it charged rather than permitting overspend. */
@Entity
@Table(name = "model_budget_reservation")
class ModelBudgetReservation {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID runId;

    @Column(nullable = false)
    private String role;

    @Column(nullable = false)
    private long estimatedPromptTokens;

    @Column(nullable = false)
    private long reservedAnswerTokens;

    protected ModelBudgetReservation() { }

    ModelBudgetReservation(UUID runId, String role, long estimatedPromptTokens, long reservedAnswerTokens) {
        this.id = UUID.randomUUID();
        this.runId = runId;
        this.role = role;
        this.estimatedPromptTokens = estimatedPromptTokens;
        this.reservedAnswerTokens = reservedAnswerTokens;
    }

    UUID id() { return id; }

    String role() { return role; }

    long estimatedPromptTokens() { return estimatedPromptTokens; }

    long reservedAnswerTokens() { return reservedAnswerTokens; }
}
