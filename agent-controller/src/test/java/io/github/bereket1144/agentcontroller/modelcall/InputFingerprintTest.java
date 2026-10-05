package io.github.bereket1144.agentcontroller.modelcall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.NoSuchAlgorithmException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InputFingerprintTest {

    @Test
    @DisplayName("AC-AGT-7.5 equal prompts for the same profile have equal fingerprints, written as 64 hex characters")
    void equalInputsGiveEqualFingerprints() {
        String fingerprint = InputFingerprint.of("local", "system text", "user text");

        assertThat(InputFingerprint.of("local", "system text", "user text")).isEqualTo(fingerprint);
        assertThat(fingerprint).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("AC-AGT-7.5 a different profile name, system text or user text gives a different fingerprint")
    void aChangedPartGivesADifferentFingerprint() {
        String fingerprint = InputFingerprint.of("local", "system text", "user text");

        assertThat(InputFingerprint.of("hosted", "system text", "user text")).isNotEqualTo(fingerprint);
        assertThat(InputFingerprint.of("local", "other system text", "user text")).isNotEqualTo(fingerprint);
        assertThat(InputFingerprint.of("local", "system text", "other user text")).isNotEqualTo(fingerprint);
    }

    @Test
    @DisplayName("AC-AGT-7.5 moving text from one part to the next gives a different fingerprint")
    void movingTextBetweenPartsGivesADifferentFingerprint() {
        assertThat(InputFingerprint.of("local", "ab", "c")).isNotEqualTo(InputFingerprint.of("local", "a", "bc"));
        assertThat(InputFingerprint.of("ab", "c", "user")).isNotEqualTo(InputFingerprint.of("a", "bc", "user"));
    }

    @Test
    @DisplayName("AC-AGT-7.5 a hash algorithm the JVM does not have is reported, not ignored")
    void anUnavailableAlgorithmIsReported() {
        assertThatThrownBy(() -> InputFingerprint.hash("NOT-A-HASH", "text"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NOT-A-HASH")
                .hasCauseInstanceOf(NoSuchAlgorithmException.class);
    }
}
