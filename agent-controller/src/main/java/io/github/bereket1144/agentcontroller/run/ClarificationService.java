package io.github.bereket1144.agentcontroller.run;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import io.github.bereket1144.agentcontroller.artifact.ArtifactRef;
import io.github.bereket1144.agentcontroller.artifact.ArtifactStore;
import io.github.bereket1144.agentcontroller.artifact.ArtifactStore.NewArtifact;
import io.github.bereket1144.agentcontroller.model.StructuredAnswerService.AcceptedAnswer;
import io.swagger.v3.oas.annotations.media.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Owns atomic batch storage, human replies and the waiting-to-queued transition. No model calls occur here. */
@Service
public class ClarificationService {
    private static final Logger log = LoggerFactory.getLogger(ClarificationService.class);
    private static final int MAX_QUESTIONS = 4;
    private final RunRepository runs;
    private final ClarificationRepository batches;
    private final ArtifactStore artifacts;
    private final JsonMapper mapper = JsonMapper.shared();

    public ClarificationService(RunRepository runs, ClarificationRepository batches, ArtifactStore artifacts) {
        this.runs = runs;
        this.batches = batches;
        this.artifacts = artifacts;
    }

    @Transactional(readOnly = true)
    public Questions questions(UUID runId) {
        List<Item> questions = new ArrayList<>();
        for (ClarificationBatch batch : batches.findByRunIdOrderByArtifactVersionAsc(runId)) {
            JsonNode content = artifacts.find(runId, "requirements", batch.getArtifactVersion()).orElseThrow().content();
            Map<String, String> answers = new HashMap<>();
            if (batch.getAnswers() != null) {
                for (Answer answer : mapper.readValue(batch.getAnswers(), Answer[].class)) {
                    answers.put(answer.questionId(), answer.answer());
                }
            }
            for (JsonNode item : content.get("questions")) {
                String id = item.get("id").asString();
                boolean answered = answers.containsKey(id);
                questions.add(new Item(id, item.get("text").asString(), item.get("why").asString(),
                        item.has("proposedDefault") ? item.get("proposedDefault").asString() : null,
                        batch.getArtifactVersion(), answered ? "ANSWERED" : "OPEN",
                        answers.get(id), answered ? batch.getAnsweredAt() : null));
            }
        }
        return new Questions(List.copyOf(questions));
    }

    /** Stores the one batch of a run: at most four questions, numbered by the controller, and waits for the reply. */
    @Transactional
    public ArtifactRef store(UUID runId, AcceptedAnswer answer) {
        Run run = locked(runId);
        ObjectNode content = mapper.createObjectNode();
        ArrayNode priorityQuestions = content.putArray("questions");
        // The prompt orders questions by impact. Enforce the batch size even when the model exceeds it.
        for (JsonNode question : answer.value().get("questions")) {
            if (priorityQuestions.size() == MAX_QUESTIONS) {
                break;
            }
            // IDs belong to the controller, so the model's numbering is replaced.
            priorityQuestions.add(((ObjectNode) question.deepCopy()).put("id", "Q-" + (priorityQuestions.size() + 1)));
        }
        ArtifactRef ref = artifacts.store(new NewArtifact(runId, "requirements", content,
                priorityQuestions.size() + " clarification questions", answer.profile(), List.of()));
        run.waitForInput();
        batches.save(new ClarificationBatch(runId, ref.version()));
        log.info("event=clarification_batch_stored runId={} version={} questionCount={} status={}",
                runId, ref.version(), priorityQuestions.size(), run.getStatus());
        return ref;
    }

    @Transactional(readOnly = true)
    public List<ArtifactRef> references(UUID runId) {
        return batches.findByRunIdOrderByArtifactVersionAsc(runId).stream()
                .map(batch -> artifacts.find(runId, "requirements", batch.getArtifactVersion()).orElseThrow())
                .map(artifact -> new ArtifactRef(artifact.kind(), artifact.version(), artifact.fingerprint())).toList();
    }

    @Transactional(readOnly = true)
    public Awaiting awaiting(UUID runId) {
        return batches.findByRunIdOrderByArtifactVersionAsc(runId).stream()
                .filter(batch -> batch.getAnswers() == null)
                .findFirst().map(batch -> new Awaiting("requirementsClarification", batch.getArtifactVersion()))
                .orElse(null);
    }

    @Transactional
    public void reply(UUID runId, Replies reply) {
        Run run = locked(runId);
        Awaiting awaiting = awaiting(runId);
        if (run.getStatus() != RunStatus.WAITING_FOR_INPUT || awaiting == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Run is not waiting for clarification.");
        }
        Set<String> pending = questions(runId).questions().stream()
                .filter(item -> item.artifactVersion() == awaiting.artifactVersion())
                .map(Item::id).collect(Collectors.toSet());
        if (reply.answers() == null) {
            throw invalidReply();
        }
        Set<String> supplied = new HashSet<>();
        for (Answer answer : reply.answers()) {
            if (answer == null || answer.questionId() == null || answer.answer() == null
                    || answer.answer().isBlank() || answer.answer().length() > 5000
                    || !supplied.add(answer.questionId())) {
                throw invalidReply();
            }
        }
        // Exactly one answer per question: none missing, none unknown.
        if (!supplied.equals(pending)) {
            throw invalidReply();
        }
        ClarificationBatch batch = batches.findByRunIdOrderByArtifactVersionAsc(runId).stream()
                .filter(value -> value.getArtifactVersion() == awaiting.artifactVersion()).findFirst().orElseThrow();
        batch.answer(mapper.writeValueAsString(reply.answers()));
        run.resumeAfterInput();
        log.info("event=clarification_replied runId={} version={} answerCount={} status={}",
                runId, awaiting.artifactVersion(), supplied.size(), run.getStatus());
    }

    private Run locked(UUID runId) {
        return runs.lockById(runId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found."));
    }

    private static ResponseStatusException invalidReply() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Answer every current question exactly once. "
                        + "Each answer must be nonblank and at most 5000 characters.");
    }

    public record Questions(List<Item> questions) { }
    public record Item(String id, String text, String why, String proposedDefault, int artifactVersion,
                       String status, String answer, Instant answeredAt) { }
    public record Answer(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String questionId,
                         @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 5000) String answer) { }
    public record Replies(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Answer> answers) { }
    public record Awaiting(String reason, int artifactVersion) { }
}
