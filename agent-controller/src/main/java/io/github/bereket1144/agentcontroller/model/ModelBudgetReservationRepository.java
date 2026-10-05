package io.github.bereket1144.agentcontroller.model;

import java.util.List;
import java.util.UUID;

import org.springframework.data.repository.Repository;

interface ModelBudgetReservationRepository extends Repository<ModelBudgetReservation, UUID> {
    ModelBudgetReservation save(ModelBudgetReservation reservation);

    List<ModelBudgetReservation> findByRunId(UUID runId);

    void deleteById(UUID id);
}
