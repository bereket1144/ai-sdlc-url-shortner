package io.github.bereket1144.agentcontroller.run;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
        List<Item> assumptions = new ArrayList<>();
        for (ClarificationBatch batch : batches.findByRunIdOrderByArtifactVersionAsc(runId)) {
            JsonNode content = artifacts.find(runId, "requirements", batch.getArtifactVersion()).orElseThrow().content();
            Map<String, String> answers = new HashMap<>();
            if (batch.getAnswers() != null) {
                for (Answer answer : mapper.readValue(batch.getAnswers(), Answer[].class)) {
                    answers.put(answer.questionId(), answer.answer());
                }
            }
            addItems(questions, content.get("questions"), batch, answers, true);
            addItems(assumptions, content.get("assumptions"), batch, answers, false);
        }
        return new Questions(List.copyOf(questions), List.copyOf(assumptions));
    }

    private static void addItems(List<Item> target, JsonNode values, ClarificationBatch batch,
                                 Map<String, String> answers, boolean required) {
        for (JsonNode item : values) {
            String id = item.get("id").asString();
            boolean answered = answers.containsKey(id);
            String status = batch.getAnswers() == null ? "OPEN" : answered ? "ANSWERED" : "ASSUMED";
            target.add(new Item(id, item.get("text").asString(), item.get("why").asString(),
                    item.has("proposedDefault") ? item.get("proposedDefault").asString() : null,
                    batch.getArtifactVersion(), required, status,
                    answers.get(id), answered ? batch.getAnsweredAt() : null));
        }
    }

    @Transactional
    public ArtifactRef store(UUID runId, AcceptedAnswer answer) {
        Run run = locked(runId);
        Questions history = questions(runId);
        ObjectNode content = (ObjectNode) answer.value().deepCopy();
        content.remove("stories");
        content.remove("openQuestions");
        // The prompt orders questions by impact. Enforce the batch size even when the model exceeds it.
        ArrayNode priorityQuestions = mapper.createArrayNode();
        for (JsonNode question : content.get("questions")) {
            if (priorityQuestions.size() == MAX_QUESTIONS) {
                break;
            }
            priorityQuestions.add(question);
        }
        content.set("questions", priorityQuestions);
        int nextId = history.questions().size() + history.assumptions().size() + 1;
        // IDs belong to the controller, so a new round cannot reuse the model's numbering.
        for (String field : List.of("questions", "assumptions")) {
            for (JsonNode item : content.get(field)) {
                ((ObjectNode) item).put("id", "Q-" + nextId++);
            }
        }
        int count = content.get("questions").size() + content.get("assumptions").size();
        ArtifactRef ref = artifacts.store(new NewArtifact(runId, "requirements", content,
                count + " clarification items", answer.profile(), references(runId)));
        ClarificationBatch batch = new ClarificationBatch(runId, ref.version());
        if (priorityQuestions.isEmpty()) {
            batch.answer("[]");
        } else {
            run.waitForInput();
        }
        batches.save(batch);
        log.info("event=clarification_batch_stored runId={} version={} itemCount={} status={}",
                runId, ref.version(), count, run.getStatus());
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
        Questions history = questions(runId);
        List<Item> pending = new ArrayList<>(history.questions());
        pending.addAll(history.assumptions());
        pending.removeIf(item -> item.artifactVersion() != awaiting.artifactVersion());
        Map<String, String> supplied = new HashMap<>();
        if (reply.answers() == null) {
            throw invalidReply();
        }
        for (Answer answer : reply.answers()) {
            if (answer == null || answer.questionId() == null || answer.answer() == null
                    || answer.answer().isBlank() || answer.answer().length() > 5000
                    || supplied.putIfAbsent(answer.questionId(), answer.answer()) != null) {
                throw invalidReply();
            }
        }
        List<Item> earlier = new ArrayList<>(history.questions());
        earlier.addAll(history.assumptions());
        if (earlier.stream().anyMatch(item -> item.artifactVersion() != awaiting.artifactVersion()
                && supplied.containsKey(item.id()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That clarification batch was already answered.");
        }
        if (supplied.keySet().stream().anyMatch(id -> pending.stream().noneMatch(item -> item.id().equals(id)))
                || pending.stream().anyMatch(item -> item.required() && !supplied.containsKey(item.id()))) {
            throw invalidReply();
        }
        ClarificationBatch batch = batches.findByRunIdOrderByArtifactVersionAsc(runId).stream()
                .filter(value -> value.getArtifactVersion() == awaiting.artifactVersion()).findFirst().orElseThrow();
        batch.answer(mapper.writeValueAsString(reply.answers()));
        run.resumeAfterInput();
        log.info("event=clarification_replied runId={} version={} itemCount={} status={}",
                runId, awaiting.artifactVersion(), supplied.size(), run.getStatus());
    }

    private Run locked(UUID runId) {
        return runs.lockById(runId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found."));
    }

    private static ResponseStatusException invalidReply() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Answer every current question; assumption replies are optional. "
                        + "Each supplied answer must be nonblank and at most 5000 characters.");
    }

    public record Questions(List<Item> questions, List<Item> assumptions) { }
    public record Item(String id, String text, String why, String proposedDefault, int artifactVersion,
                       boolean required, String status, String answer, Instant answeredAt) { }
    public record Answer(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String questionId,
                         @Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 5000) String answer) { }
    public record Replies(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Answer> answers) { }
    public record Awaiting(String reason, int artifactVersion) { }
}
