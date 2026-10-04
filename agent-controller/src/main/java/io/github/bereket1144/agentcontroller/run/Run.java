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
    @Column(nullable = false)
    private RunStatus status;

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
        if (requirement == null || requirement.isBlank()) {
            throw new IllegalArgumentException("requirement must not be blank");
        }
        this.requirement = requirement;
        this.status = RunStatus.CREATED;
    }

    public void start() {
        if (status != RunStatus.CREATED) {
            throw new IllegalStateException(
                    "Only a CREATED run can be started");
        }
        this.status = RunStatus.RUNNING;
    }

}
