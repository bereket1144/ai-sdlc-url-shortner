package io.github.bereket1144.agentcontroller.run;

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

    public Run(String requirement) {
        if (requirement == null || requirement.isBlank()) {
            throw new IllegalArgumentException("requirement must not be blank");
        }
        this.requirement = requirement;
    }

}
