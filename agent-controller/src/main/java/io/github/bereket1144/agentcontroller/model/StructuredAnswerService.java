package io.github.bereket1144.agentcontroller.model;

import java.util.List;

import io.github.bereket1144.agentcontroller.model.ModelClient.FinishReason;
import io.github.bereket1144.agentcontroller.model.PromptBuilder.Prompt;
import io.github.bereket1144.agentcontroller.model.PromptBuilder.PromptInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/** Calls one configured model until its answer is valid or its bounded repair attempts end. */
@Service
public class StructuredAnswerService {

    private static final Logger log = LoggerFactory.getLogger(StructuredAnswerService.class);

    private final ModelClient model;
    private final PromptBuilder prompts;
    private final OutputValidator validator;
    private final int repairLimit;

    public StructuredAnswerService(ModelClient model, PromptBuilder prompts, OutputValidator validator,
                                   @Value("${agent.output.repair-limit:2}") int repairLimit) {
        if (repairLimit < 0) {
            throw new IllegalArgumentException("agent.output.repair-limit must not be negative");
        }
        this.model = model;
        this.prompts = prompts;
        this.validator = validator;
        this.repairLimit = repairLimit;
    }

    public AcceptedAnswer call(String roleId, ModelProfile profile, String task, List<PromptInput> inputs) {
        Prompt original = prompts.build(roleId, profile, task, inputs);
        Prompt current = original;
        int repairs = 0;
        while (true) {
            ModelClient.ModelResult answer = model.call(roleId, profile,
                    current.systemText(), current.userText());
            OutputValidator.Validation checked = answer.finishReason() == FinishReason.ANSWER_LIMIT
                    ? new OutputValidator.Validation(null, List.of("Answer was cut off at answer limit"))
                    : validator.validate(roleId, answer.text());
            if (checked.valid()) {
                log.info("event=model_output_accepted role={} repairs={}", roleId, repairs);
                return new AcceptedAnswer(checked.value(), repairs);
            }
            log.info("event=model_output_rejected role={} repairs={} issueCount={}",
                    roleId, repairs, checked.messages().size());
            if (repairs == repairLimit) {
                throw new OutputInvalidException(checked.messages(), repairs);
            }
            current = prompts.repair(roleId, original, answer.text(), checked.messages());
            repairs++;
        }
    }

    public record AcceptedAnswer(JsonNode value, int repairAttempts) { }

    public static final class OutputInvalidException extends RuntimeException {
        private final List<String> messages;
        private final int repairAttempts;

        private OutputInvalidException(List<String> messages, int repairAttempts) {
            super("OUTPUT_INVALID: model answer failed validation after " + repairAttempts + " repairs");
            this.messages = List.copyOf(messages);
            this.repairAttempts = repairAttempts;
        }

        public String code() {
            return "OUTPUT_INVALID";
        }

        public List<String> messages() {
            return messages;
        }

        public int repairAttempts() {
            return repairAttempts;
        }
    }
}
