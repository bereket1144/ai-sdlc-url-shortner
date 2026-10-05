package io.github.bereket1144.agentcontroller.modelcall;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import io.github.bereket1144.agentcontroller.model.ModelClient;
import io.github.bereket1144.agentcontroller.model.ModelProfile;
import io.github.bereket1144.agentcontroller.modelcall.ModelCallRecords.StoredAttempt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The model client of replay mode ({@code agent.model-client.mode=replay}). It serves the answers and failures of a
 * recording and never contacts a model. A call is matched by its role and input fingerprint, and the recorded
 * attempts of one role and fingerprint are served in the order they were recorded; that order is what tells a first
 * attempt from its retries, because a repair or a fallback has a different prompt or profile and so another
 * fingerprint. Nothing else changes: the caller still checks, repairs, records and counts what it gets.
 */
@Component
@ConditionalOnProperty(name = "agent.model-client.mode", havingValue = "replay")
public class ReplayModelClient implements ModelClient {

    private static final Logger log = LoggerFactory.getLogger(ReplayModelClient.class);

    private record Key(String role, String inputFingerprint) { }

    // Each recorded attempt is served once. A queue per key keeps concurrent branches from taking the same one.
    private final Map<Key, Queue<StoredAttempt>> recorded = new ConcurrentHashMap<>();

    public ReplayModelClient(@Value("${agent.model-client.recording}") String recordingFile) {
        Recording.read(Path.of(recordingFile)).forEach(attempt -> recorded
                .computeIfAbsent(new Key(attempt.role(), attempt.inputFingerprint()),
                        key -> new ConcurrentLinkedQueue<>())
                .add(attempt));
        log.info("event=replay_recording_loaded recordedAttempts={}",
                recorded.values().stream().mapToInt(Queue::size).sum());
    }

    @Override
    public ModelResult call(String role, ModelProfile profile, String systemText, String userText) {
        Objects.requireNonNull(systemText, "systemText must not be null");
        Objects.requireNonNull(userText, "userText must not be null");
        String fingerprint = InputFingerprint.of(profile.name(), systemText, userText);
        Queue<StoredAttempt> candidates = recorded.get(new Key(role, fingerprint));
        StoredAttempt attempt = candidates == null ? null : candidates.poll();
        if (attempt == null) {
            log.warn("event=model_replay role={} profile={} fingerprint={} outcome={}",
                    role, profile.name(), fingerprint, FailureReason.REPLAY_MISSING);
            throw new ModelCallException(FailureReason.REPLAY_MISSING, profile.name());
        }
        log.info("event=model_replay role={} profile={} fingerprint={} outcome={}",
                role, profile.name(), fingerprint, attempt.outcome());
        return switch (attempt.outcome()) {
            // The call returned an answer; whether the answer is valid is decided later, as in live mode.
            case SUCCESS, INVALID_ANSWER, ANSWER_CUT_OFF -> new ModelResult(attempt.answerText(),
                    attempt.finishReason(), attempt.reportedPromptTokens(), attempt.reportedAnswerTokens(),
                    Duration.ofMillis(attempt.durationMs()));
            // Every other outcome is a recorded failure of the call, with the failure's own name.
            default -> throw new ModelCallException(FailureReason.valueOf(attempt.outcome().name()),
                    profile.name());
        };
    }
}
