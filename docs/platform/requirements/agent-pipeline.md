# Agent pipeline requirements

## Status of this file

This file holds the requirements of the agent pipeline as it was built. It has 29 stories in five parts: 20 `coded`, 6 `done`, and 3 that are built and tested but still carry an older status line (AGT-62 and AGT-64 say `stale`, as [ADR 0012](../../adr/0012-reconcile-platform-backlog-statuses.md) marked every story that was still open when it was written, and AGT-68 says `open`). The other 36 stories of the original plan are in [agent-pipeline-backlog.md](agent-pipeline-backlog.md): 9 deferred, 12 replaced by a different design and 15 not built. Nothing in the backlog is claimed as delivered.

`done` means a user-directed closure of a story that was already built (ADR 0012); it does not record a new QA or code review pass. A line "Not proved" under a story names a criterion that no automated test names, with the reason. A line "Replaced in part" or "Updated by" names a criterion whose wording was overtaken by a later story. Criterion text is kept as written; the only edits in it are pointers to the backlog file.

## Request

The SDLC graph in [sdlc-flow.md](sdlc-flow.md) runs eight stage nodes. This feature replaces the dummy work of those nodes with real agents. Each agent has a written identity as a senior expert, calls an AI model, and hands a versioned, checked result to the next stage. The requirements agent asks the reviewer questions when a requirement is unclear.

A run goes from a written requirement to stories, a design and a plan (the last two approved by a person when the configuration asks for it), committed code, tests, review and documentation. It ends `SUCCEEDED` when the build and every test pass in a sandbox container; with automatic delivery on, the work is then merged into the repository checkout.

Models are reached through one port, so a small local model for tests and a large hosted model for real runs are a configuration choice. In this file "agent" means one of the platform's own SDLC agents, not a coding assistant.

## How the file is organised

- Five parts. A story is one working behaviour with every layer it needs and its tests. Each story names what it depends on. Story numbers are labels, not order: stories are listed by part, in numeric order within a part.
- **Part A** roles and model access. **Part B** artifacts, requirements, clarification and approval. **Part C** the pipeline: stages, code, build, retry and delivery. **Part D** changing an earlier run. **Part E** metrics, lineage and CI.
- Every story is proved with a scripted model: its criteria pass without a live model. The few opt-in live checks are listed under Models below.

## Ambiguities and assumptions

### Models

- **A model is reached through its HTTP API, set by configuration** (address, model identifier and limits). Three clients serve the one port: a local Ollama server, a hosted messages API (a profile with a key) and any OpenAI-style chat API such as OpenRouter (AGT-63). A profile's `api-style` chooses between them, so changing the model is a configuration change.
- **The context window belongs to the model profile, not to the agents.** The trial model reports a context length of 32,768 tokens (`qwen2.context_length`); a hosted model has a far larger one. No role definition, prompt text or node depends on a window size.
- **A local server may use a smaller window than the model supports** unless each request states the window it wants, so every call states the profile's window and answer limit (AC-AGT-3.2).
- **Token counts are estimates.** They come from characters and a configurable ratio, pessimistic by default (3 characters per token). The counts a model reports are recorded when it reports them.
- **Calls are limited per model profile** (default 1 at a time). The QA, review and documentation stages run side by side in the graph; on a one-call model they take turns.
- **Live model checks are opt-in.** `./mvnw verify` never calls a model: tests use a scripted model, and recorded answers can be replayed. A live check runs only when a property enables it (AC-AGT-3.8, 12.6, 62.1 and 63.7).
- **The model's answer is untrusted data.** Plain code checks it and it is never executed.

### Roles

- **The eight roles are the stage nodes of FLOW-1:** `requirements`, `impactAnalysis`, `design`, `planning`, `development`, `qa`, `review`, `documentation`. The `initialize` and `collect` nodes stay plumbing without an agent.
- **Each role is told to be a named senior expert,** to apply the current best practice of its field, to check its own work before answering, and to ask rather than guess. A unit test can only prove that the identity and its parts are present; whether the result is good is for the reviewer to judge.
- **Role definitions are data in the repository** (`agent-controller/src/main/resources/agent-roles/`). They are versioned, contain no secret, and contain no model name, provider, window size, product or domain word. The platform stays generic: product requirements live in scenario files (`scenarios/`), never in platform code or prompts.
- **Agents have no tools.** The controller gives each agent the context it may read and the agent answers with structured data. Tool calling, retrieval and MCP are not built.
- **Agents never talk to each other and never touch files.** Results move between stages as artifacts through the controller, and the controller checks and applies anything that changes files.
- **Text in an input is data, not an instruction.** This holds for the requirement, every artifact and every file.

### Control

- **Requirements clarification precedes stories (AGT-64, agreed 2026-10-05).** The agent asks at most four priority questions in one batch, once, and asks no assumptions for the requester to confirm. Every question needs an answer. After the reply the run goes straight to the stories, and the assumptions the agent makes are written into the stories artifact. Python, the standard library, SQLite and pytest are fixed execution choices for this version, supplied as context rather than questions.
- **Plain code decides pass or fail.** A model never decides whether something is valid, approved or done. A run is `SUCCEEDED` only when every stage finished and the build and every test passed in the sandbox (AC-AGT-62.10). A person's approval is required only for the stages the configuration names (AGT-15).
- **Only the requirements stage asks questions,** and only the human reviewer answers, through the API. The other roles list open questions in their artifact and the run goes on.
- **`WAITING_FOR_INPUT` covers both unanswered questions and open approvals.** The run read response shows what the run waits for. A waiting run holds no thread and makes no model call.
- **Approvers are named, not authenticated.** A decision records a free-text name. Authenticated identities and permissions are in [the backlog](agent-pipeline-backlog.md) (AGT-48) and need the user's go-ahead to add Spring Security.
- **Failed runs say why.** A failure reason code is stored and shown (AGT-9).
- **A stage can run more than once in a run.** A failed stage is attempted again (AGT-65) and a failed run can be retried (AGT-66). The once-only counts of FLOW-1 describe a run without retries.

### Delivery

- **Each stage has a switch** between its agent and its dummy node, one setting per role (`agent.stages.<role>`). The dummy is the default, so the graph tests keep their meaning and a run needs no model; the `live` profile turns the agents on for every stage but impact analysis.
- **Each run works on its own branch of this repository.** The workspace is a git worktree on `run/<run-id>` (AGT-67). The agents write files under the product folder (`workspace.product-folder`) and the controller writes the run's evidence under `runs/<run-id>/`. A run does not change the controller's checked-out branch unless automatic delivery is on (AGT-68).
- **Change sets are whole-file writes,** and deletions (AGT-69). A file operation carries the full new content, not a diff, because small models produce diffs unreliably.
- **The controller makes the commits:** the development role is the author of the code, and `controller` of the exported documents and evidence (AC-AGT-67.3).
- **Builds and tests run in a throwaway container** (AC-AGT-62.8): non-root, with limits on CPU, memory, processes and time, no credentials and no network. The workspace is copied in and only the report is copied out. Only the controller can start containers; this is a documented limitation.
- **The language is configuration, not platform code.** The command, the image and the file layout are settings (`sandbox.verify.*`); the shipped settings build and test a Python product with pytest.
- **Artifacts live in PostgreSQL** and are also written as files on the run's branch by the controller (AGT-21, AC-AGT-67.3).

## Open questions

None for the delivered stories: the design questions raised while this file was a draft were settled in the decision records under `docs/adr/`. Two questions that belong to backlog stories are kept in [the backlog](agent-pipeline-backlog.md).

## User stories and acceptance criteria

## Part A. Roles and model access

Shown when: one role, asked through the scripted model, a local model or a hosted model, returns a checked structured answer, and failures, retries, fallback, budget and replay behave as written below.

### AGT-1 Give every role an expert identity

Status: coded

Depends on: none.

As the platform owner, I want each stage's agent defined by a versioned instruction file that makes it a senior expert in its field, so that every stage starts from a clear identity, standards and boundaries instead of an improvised prompt.

- AC-AGT-1.1 There is exactly one role definition for each of the eight roles. Starting the application with a role missing, or with an unknown extra role, fails and names it.
- AC-AGT-1.2 A definition has these parts, all non-blank: identity (the senior expert the agent is told to be), mission, voice (how it writes), working standards (at least three named practices of its field), inputs (the input kinds it may read, most important first), output (the artifact kind it produces, its structure, and whether a person must approve it before the next stage), boundaries (what it must never do), uncertainty rule (ask or state an assumption, never invent), self-check (what it verifies before answering), and a version. A definition with a part missing or blank fails loading with a message naming the role and the part.
- AC-AGT-1.3 No two roles share the same identity text.
- AC-AGT-1.4 No definition contains a model or provider name, a window size, or a product or domain word. A test checks every definition against a list of such words (at least: shortener, calculator, ollama, qwen, llama, claude, gpt, anthropic, openai).
- AC-AGT-1.5 An input is named from a fixed list of input kinds that the platform defines: the original requirement, the questions and answers so far, selected workspace files, and the artifact kinds. An unknown input kind fails loading and names the role and the kind.
- AC-AGT-1.6 Definitions are read-only data loaded at startup and available by role id.

### AGT-2 Describe models in configuration and give each role one

Status: coded

Depends on: none.

As the platform owner, I want models described by configuration, with their limits, and each role assigned to one, so that moving from a small local model to a large hosted one is a configuration change.

- AC-AGT-2.1 A model profile has: a name, a model identifier, a base address, a context window in tokens, an answer limit in tokens, a call timeout, a maximum number of simultaneous calls (default 1), a retry limit (default 2), and a sampling temperature (default 0).
- AC-AGT-2.2 The default configuration contains one profile for the local trial model with a 32,768-token window. No committed file contains a secret. A key for a hosted model is read only from the environment.
- AC-AGT-2.3 One profile is the primary and at most one is the fallback. Every role uses the primary unless configuration assigns it another profile.
- AC-AGT-2.4 Invalid configuration fails at startup with a message naming the profile and the field: a missing or non-positive window, answer limit, timeout or call limit; an answer limit that leaves no room for a prompt (the answer limit plus the safety margin must be smaller than the window); a role assigned to an unknown profile; a fallback equal to the primary.
- AC-AGT-2.5 The profile of a role can be read by role name.

### AGT-3 Call a model through one port

Status: coded

Depends on: AGT-2.

As the platform, I want every model call to go through one interface, so that roles never know which model answers and a new model is a new implementation.

- AC-AGT-3.1 The port takes a profile, a system text and a user text. It returns the answer text, the finish reason (complete, or cut off at the answer limit), the token counts the model reported when it reports them, and the duration.
- AC-AGT-3.2 The local-model implementation sends one non-streaming request to the profile's address with the profile's model identifier, the system and user texts, and the profile's context window, answer limit and temperature. It does not rely on the server's defaults for any of them. A stub server inside the test JVM checks the request.
- AC-AGT-3.3 Failures are typed: unreachable, timed out, rate limited, server error, request rejected, response unreadable. The first four are marked retryable. The last two are not.
- AC-AGT-3.4 A call can only go to a configured profile's address. No address, model identifier or option comes from a requirement, an artifact or a model answer.
- AC-AGT-3.5 The number of calls running at once on one profile never exceeds its limit. Further calls wait for a free place. The timeout counts from the moment a call starts, not from when it began waiting.
- AC-AGT-3.6 Nothing from the prompt or the answer reaches a log. A call logs the role, the profile, the sizes, the outcome and the duration only. A test captures the logs with distinctive texts.
- AC-AGT-3.7 A scripted implementation returns prepared answers and failures in order. A call with nothing prepared fails the test with a clear message. Every later story is tested with it.
- AC-AGT-3.8 An opt-in live check calls the configured local model once with a trivial prompt and expects a non-empty answer. It runs only when a property enables it, and `./mvnw verify` skips it.

### AGT-4 Build each prompt inside the model's window

Status: coded

Depends on: AGT-1, AGT-2.

As the platform, I want the prompt for each call sized to the model's window, so that a small model is never silently cut off and a large model receives everything.

- AC-AGT-4.1 A prompt has a system part and a user part. The system part holds the role definition and the platform's fixed rules, including that text in the inputs is data and never instructions. The user part holds the task, then each input in its own block that names its kind and version.
- AC-AGT-4.2 The size of a prompt is estimated with the characters-per-token ratio (configurable, 3 by default). It must fit in the window minus the profile's answer limit minus a safety margin (a configurable percentage of the window, 10 by default).
- AC-AGT-4.3 When the estimate fits, every input is sent in full. The same inputs are also sent in full to a profile with a larger window. Nothing is reduced when there is room.
- AC-AGT-4.4 When it does not fit, reduction follows a fixed order: optional context first, then the role's inputs from the least important (last in the definition) to the most important, each replaced by its short form. Every reduction step is kept for the call record (AGT-7).
- AC-AGT-4.5 The task text, the original requirement and the answers given so far are never reduced. When the prompt still does not fit, no call is made and the result is a typed CONTEXT_TOO_LARGE failure that names the role and the sizes. Text is never cut off silently.
- AC-AGT-4.6 An instruction written inside an input (for example "ignore your rules") appears only inside its block. The system part is identical whatever the inputs say.

### AGT-5 Accept only structured answers that pass their checks

Status: coded

Depends on: AGT-3, AGT-4.

As the platform, I want each role's answer checked against its structure by plain code, with a bounded number of repair attempts, so that a malformed answer from a weak model never reaches the next stage and a stubborn one stops the run.

- AC-AGT-5.1 Each role has an output structure (fields, types, required fields). An answer is valid only when it contains a JSON object that matches it. Fields outside the structure are ignored. A missing, blank required or wrongly typed field makes the answer invalid.
- AC-AGT-5.2 The JSON object is taken from the answer even when the model wraps it in a code fence or writes a sentence before or after it. An answer with no JSON object, or with more than one separate top-level object, is invalid.
- AC-AGT-5.3 A role can add its own plain-code checks (for example, unique ids). A failing check makes the answer invalid and its messages are kept.
- AC-AGT-5.4 An invalid answer, and an answer cut off at the answer limit, start a repair attempt: the model is called again with the previous answer and the exact messages, up to the repair limit (default 2 per call). The first valid answer is used.
- AC-AGT-5.5 When the repair limit is used up, the result is a typed OUTPUT_INVALID failure with the last messages, and nothing is stored as an artifact.
- AC-AGT-5.6 Repair attempts and infrastructure retries (AGT-6) are counted separately. Using one never uses up the other.

### AGT-6 Retry model failures, then fall back to a second model

Status: coded

Depends on: AGT-3, AGT-5.

As the platform, I want transient model failures retried and then moved to a second model, so that one slow or unreachable model does not stop a run needlessly.

- AC-AGT-6.1 A retryable failure is retried on the same profile with the same input, up to the profile's retry limit, waiting longer between attempts (the wait is configurable and tests use none). A failure that is not retryable is not retried.
- AC-AGT-6.2 When the retries on the primary are used up and a fallback profile exists, the same request is made once on the fallback, with the same role, inputs and checks, and with the prompt sized again for the fallback's window.
- AC-AGT-6.3 Only a failure of the call itself switches models. An invalid or cut-off answer is repaired on the profile that gave it (AGT-5) and never causes a switch.
- AC-AGT-6.4 When the fallback also fails, or there is none, the result is a typed MODEL_UNAVAILABLE failure.
- AC-AGT-6.5 Every attempt (first, repair, retry, fallback) is a separate call record (AGT-7) and counts toward the run budget (AGT-8).

### AGT-7 Record every model call

Status: coded

Depends on: AGT-3.

As the reviewer, I want every model call recorded with what went in and what came out, so that I can trace any result to the call that made it and replay it.

- AC-AGT-7.1 Every attempt is stored with: the run, the role, the role version, the profile, the model identifier, the attempt kind (first, repair, retry, fallback), an input fingerprint, the prompt text, the answer text, the finish reason, the estimated and reported token counts, the prompt reduction steps, the duration and the outcome.
- AC-AGT-7.2 Failed, invalid and cut-off attempts are stored too, with their outcome.
- AC-AGT-7.3 A stored record is never changed. The service offers no operation that updates or deletes one.
- AC-AGT-7.4 A run's records can be listed in the order they happened by a read operation of the platform. An HTTP form of it belongs to the reviewer interface.
- AC-AGT-7.5 The input fingerprint depends on the prompt text and the profile name only, not on the run id or the time, so equal prompts have equal fingerprints.
- AC-AGT-7.6 Stored text never reaches a log.

### AGT-8 Stop a run that exceeds its budget

Status: coded

Depends on: AGT-7.

As the platform owner, I want limits on how many model calls and tokens a run may use, so that a loop or a stubborn model cannot run forever or cost without bound.

- AC-AGT-8.1 Configuration sets, per run, the maximum number of model calls and of estimated tokens, and, per role, the maximum number of calls.
- AC-AGT-8.2 Before each attempt the runner checks the budget from the call records. When an attempt would pass a limit, no call is made and the result is a typed BUDGET_EXHAUSTED failure naming the limit.
- AC-AGT-8.3 Every attempt counts, including repair attempts, retries, fallbacks and failed attempts.
- AC-AGT-8.4 The budget belongs to the run. Pausing, resuming, answering or repairing does not reset it.

### AGT-9 Say why a run failed

Status: coded

Later stories added codes: the list also holds VERIFICATION_FAILED (AGT-62) and DELIVERY_BLOCKED (AGT-68). ASSEMBLY_CONFLICT, STOPPED_BY_REVIEWER, STAGE_TIMEOUT and RECOVERY_FAILED are reserved for stories in [the backlog](agent-pipeline-backlog.md) and nothing sets them yet.

Depends on: AGT-5.

As a reviewer, I want a failed run to tell me why, so that I do not have to read the logs to learn what went wrong.

- AC-AGT-9.1 A failed run stores a failure reason code from a fixed list: OUTPUT_INVALID, MODEL_UNAVAILABLE, CONTEXT_TOO_LARGE, BUDGET_EXHAUSTED, REPLAY_MISSING, ACCESS_DENIED, REJECTED, ASSEMBLY_CONFLICT, STOPPED_BY_REVIEWER, STAGE_TIMEOUT, RECOVERY_FAILED and UNEXPECTED. A story that adds a failure adds its code here. The code comes with a short fixed sentence and never with model, requirement or answer text.
- AC-AGT-9.2 The run read response shows the reason when the run is FAILED and omits it otherwise.
- AC-AGT-9.3 The reason is stored in the same transaction as the FAILED status, so no run is FAILED without one.
- AC-AGT-9.4 An exception without a typed reason is stored as UNEXPECTED. A run that failed before this story reads back as UNEXPECTED.
- AC-AGT-9.5 The API document lists the field and the codes. This updates AC-SWAG-1.4 with the user's approval, noted in the steps log.

### AGT-10 Replay recorded answers instead of calling a model

Status: coded

Depends on: AGT-7.

As a reviewer without a model, I want a run to replay recorded model answers, so that I can run the platform with no AI account and no local model while everything else still runs for real.

- AC-AGT-10.1 A run's call records can be exported to a recording, and a recording can be loaded. Loading what was exported gives the same records.
- AC-AGT-10.2 In replay mode the port serves the recorded answer or failure for the call with the same role, attempt kind and input fingerprint, and never contacts a model.
- AC-AGT-10.3 Replay changes nothing else. Answers are still checked, repaired, recorded and counted, and a recorded invalid answer is still invalid.
- AC-AGT-10.4 A call with no recording fails with a typed REPLAY_MISSING failure. Replay never falls back to a live call.
- AC-AGT-10.5 A recording can contain failed calls, so the retry and fallback paths replay too.
- AC-AGT-10.6 The mode (live or replay) and the recording to use come from configuration.

### AGT-63 Call an OpenAI-compatible model API such as OpenRouter

Status: coded

Depends on: AGT-3, AGT-62.

As the platform owner, I want to run the agents on any model reached through an OpenAI-style chat API, such as OpenRouter, so that the choice of provider and model is a configuration change and not a code change.

- AC-AGT-63.1 A model profile may name its API style with a setting `api-style`: `ollama`, `anthropic` or `openai`. A profile without it behaves as before (a profile with a key uses the Anthropic style, one without a key uses the Ollama style), so no existing configuration changes.
- AC-AGT-63.2 A profile with the style `openai` is served by a new `ModelClient` implementation that sends `POST <base-address>/chat/completions` with the header `Authorization: Bearer <key>` and a body holding the model, a system message and a user message, the profile's answer limit as `max_tokens`, and its temperature. For OpenRouter the base address is `https://openrouter.ai/api/v1`.
- AC-AGT-63.3 The answer text is the content of the first choice. A finish reason `stop` is COMPLETE and `length` is ANSWER_LIMIT; any other value, no choice, or empty content is RESPONSE_UNREADABLE. The reported prompt and answer token counts are kept when present.
- AC-AGT-63.4 A leading reasoning block between `<think>` and `</think>` in the content is removed before the text is returned, so the answer check sees only the answer.
- AC-AGT-63.5 Failures use the same typed failures and retryability as the other clients: 429 is RATE_LIMITED, 5xx is SERVER_ERROR, other 4xx (including 401, 402 and 404) is REQUEST_REJECTED, a timeout is TIMED_OUT, and no connection is UNREACHABLE. A body with an `error` object is a failure even when the status is 200. A message or a log never holds the key, a prompt, an answer or a response body.
- AC-AGT-63.6 Like the other clients: one call slot per profile, no redirects followed, and a blank key is refused before any request.
- AC-AGT-63.7 An opt-in live check, enabled by a property and the profile and key in the environment, sends one small request and asserts only structure (non-blank text and a finish reason). `./mvnw verify` skips it.

## Part B. Artifacts, requirements, clarification and approval

Shown when: an ambiguous requirement produces questions, the reviewer answers them in one batch, the stories are stored as a versioned artifact, and the reviewer approves the design and the plan before the run goes on.

### AGT-11 Keep each stage's output as a versioned artifact

Status: coded

Depends on: AGT-5.

As the platform, I want every agent output stored as a versioned, fingerprinted artifact with its lineage, so that each result can be traced to who made it and from what, and a later result never silently overwrites an earlier one.

- AC-AGT-11.1 An artifact has: the run, the kind, a version (1, 2, and so on, per run and kind), the validated content, a short summary, a fingerprint of the content, the producing role and role version, the profile and model identifier of the call that produced it, and the artifact versions it consumed.
- AC-AGT-11.2 Producing a kind again creates the next version. Older versions stay readable and unchanged.
- AC-AGT-11.3 Equal content gives an equal fingerprint, and changed content gives a different one.
- AC-AGT-11.4 An artifact can be read by run, kind and version. An unknown one is reported as not found.
- AC-AGT-11.5 The graph state holds only artifact references (kind, version, fingerprint), never content, so checkpoints stay small and nothing sensitive sits in graph state or logs.
- AC-AGT-11.6 A run's artifacts can be listed with their lineage.

### AGT-12 Turn a requirement into stories and criteria

Status: coded

AGT-64 takes precedence over AC-AGT-12.1 for the requirements stage: the questions come first, as a separate batch, and the stories follow.

Not proved: AC-AGT-12.4 (no test names it; the unchanged FLOW-1 and FLOW-2 tests run with every stage on its dummy node).

Depends on: AGT-1 to AGT-5, AGT-11.

As a requester, I want the requirements stage to turn my text into user stories with numbered acceptance criteria, stated assumptions and open questions, so that vague input becomes a clear engineering problem.

- AC-AGT-12.1 The requirements node calls the requirements role with the run's requirement and stores a requirements artifact. It holds: stories (id, title, role, want, benefit, and acceptance criteria each with an id and a text), assumptions, open questions (id, text, why it matters, optional proposed default) and a summary.
- AC-AGT-12.2 Plain-code checks make an answer invalid when a story has no criterion, an id repeats, a criterion id does not start with its story's id, or a text is blank.
- AC-AGT-12.3 The stage can be switched between the agent and the existing dummy node by configuration, one setting per role, and the dummy is the default. With the agent enabled and a scripted model, a requirement with no open questions passes an artifact reference to the next stage.
- AC-AGT-12.4 With every stage on its dummy, all FLOW-1 and FLOW-2 criteria still hold unchanged.
- AC-AGT-12.5 With the agent enabled, the node logs only the run id, the role, the artifact kind, version and fingerprint, and counts. It never logs requirement, question or answer text.
- AC-AGT-12.6 An opt-in live check sends the clear trial requirement to the configured model and asserts only structure: either a valid requirements artifact or a typed failure, never any wording. `./mvnw verify` skips it.

### AGT-15 Approve the design and the plan before the next stage

Status: done

Disposition (2026-10-05): Closed as already built: design and plan approval, rejection feedback and persisted decisions have integration tests. Final commit approval remains separate.

Depends on: AGT-12, AGT-64, AGT-66.

As a reviewer, I want to approve or reject the design and the plan before the run goes on, so that human oversight comes first and an agent never approves its own work. (The stories are not approved in this version.)

- AC-AGT-15.1 When `agent.approval.required-for` lists a stage (a comma separated list of `design` and `planning`; empty by default; the live profile sets `design,planning`), the run stops after that stage has stored its artifact and waits (WAITING_FOR_INPUT) for a decision about that artifact version. A waiting run makes no model call, holds no thread, and is not claimed by the scheduler.
- AC-AGT-15.2 While it waits, `GET /v1/runs/{id}` shows `approval` with `kind`, `version`, `fingerprint`, `summary` and `rejectionsLeft`. It shows no `approval` at any other time.
- AC-AGT-15.3 `POST /v1/runs/{id}/decisions` with `{"decision": "APPROVE" | "REJECT", "approver": "...", "comment": "..."}` answers 202 for a run that waits for an approval. `approver` is a non-blank name of at most 100 characters; `comment` is required for REJECT and at most 5,000 characters. An invalid body is a 400, an unknown run a 404, and a run that does not wait for an approval (or a second decision for the same version) a 409, all in the RUN-4 format, and nothing changes on a refusal.
- AC-AGT-15.4 APPROVE queues the run, and it continues at the next stage with the approved version (it resumes at the earliest unfinished stage, as AC-AGT-66.2).
- AC-AGT-15.5 REJECT runs the same stage again with the reviewer's comment and all earlier comments for that stage given to the agent, stores the next artifact version and waits for approval again. After `agent.approval.max-rejections` rejections of one stage (default 3) the run ends FAILED with the reason REJECTED.
- AC-AGT-15.6 Every decision is stored with the approver, the decision, the comment, the artifact kind, version and fingerprint, and the time. A run that waits for an approval is still waiting, with the same request, after a restart of the controller.
- AC-AGT-15.7 Only the decisions operation can approve or reject. Nothing an agent returns can.
- AC-AGT-15.8 The API document lists the operation, the `approval` field and the REJECTED reason (this updates AC-SWAG-1.3 and AC-SWAG-1.4 with the user's approval, noted in the steps log). The reviewer page shows the approval request with buttons (UI-3).

### AGT-16 Give each stage only what its role may read

Status: coded

Depends on: AGT-1, AGT-11.

As the platform owner, I want each agent to receive only the artifacts its role is allowed to read, so that agents see what they need and nothing else, and every result records what it was built from.

- AC-AGT-16.1 A role's prompt is built only from the input kinds named in its definition. For a kind that needs approval, the approved version is used.
- AC-AGT-16.2 Asking the runner for an input that the role's definition does not list fails before any model call with a typed ACCESS_DENIED failure, and no content of that input is read.
- AC-AGT-16.3 A stored artifact records exactly the versions that went into its prompt.
- AC-AGT-16.4 A role's answer can only create an artifact of its own output kind. An answer cannot create or change an artifact of another kind, a decision or a role definition.

### AGT-34 Store checkpoints in a readable form owned by migrations

Status: done

Disposition (2026-10-05): Closed as already built. The checkpoint factory, migration and tests exist; the production workflow does not attach the saver. No additional checkpoint integration is scheduled by this closure.

Depends on: none.

As the platform owner, I want graph checkpoints stored as readable text in tables that our own migrations create, so that a paused run survives code changes and a reviewer can read what is stored.

- AC-AGT-34.1 The checkpoint tables are created by a database migration. The application does not create or change them itself when it starts.
- AC-AGT-34.2 A checkpoint stores the graph state as JSON text, not as Java serialised bytes. A test reads a stored checkpoint back as text.
- AC-AGT-34.3 A checkpoint written before a class change that adds or removes an unrelated field can still be read afterwards. Unknown fields are ignored and missing optional fields take their defaults.
- AC-AGT-34.4 The existing proofs (pause at a gate, resume in a new process, the parallel join) still pass on the new storage.

### AGT-64 Ask the requirements questions in one batch before generating stories

Status: coded

Depends on: AGT-5, AGT-8, AGT-11, AGT-12, AGT-34.

As a requester, I want to answer the (at most four) most important requirements questions together before stories, so that clarification is manageable and the stories are built on my decisions.

Scope and precedence: this story delivers requirements-stage clarification, including the pause, read operation and batch reply needed for it. It takes precedence over AC-AGT-12.1's combined stories-and-questions output, AC-AGT-13.6's continuation on unanswered assumptions, AC-AGT-14.1's allowance for missing replies where defaults exist, and AC-AGT-62.4's assumption-only continuation for the requirements stage. AGT-13 and AGT-14 (in [the backlog](agent-pipeline-backlog.md)) retain the generalisation to other roles; they do not require implementing this same behaviour twice. Existing artifact versions remain historical records. Approving the generated stories is not part of this version (AGT-15 covers the design and the plan).

- AC-AGT-64.1 Before stories, the real requirements stage asks its questions in one batch and stores them as a `questions` array. The prompt asks for at most four material questions ordered by importance and tells the agent not to ask the requester to confirm assumptions; the controller keeps at most the first four if the model exceeds the limit. Each question has id, text, why and an optional proposed default. IDs use `Q-1`, `Q-2` and so on and are numbered by the controller. The clarification answer carries an empty `assumptions` array, because the role's output fields are shared with the stories phase. Empty `stories`, `openQuestions` and `assumptions` fields in clarification output are ignored and removed, while nonempty fields remain invalid. Python, the standard library, SQLite and pytest are fixed execution context rather than choices to clarify.
- AC-AGT-64.2 When `questions` has items, the run becomes `WAITING_FOR_INPUT`. While waiting, stories and downstream stages do not run, dispatch records neither success nor failure, and waiting makes no model calls and holds no execution thread.
- AC-AGT-64.3 `GET /v1/runs/{id}/questions` returns `{"questions": [...]}`: the stored questions with their artifact version, `OPEN` or `ANSWERED` status, and the saved answer and its submission time. Before the batch exists it returns an empty array; unknown runs return 404 in the RUN-4 format. `GET /v1/runs/{id}` identifies the clarification wait and current artifact version.
- AC-AGT-64.4 `POST /v1/runs/{id}/replies` accepts one `answers` array with exactly one `{questionId, answer}` per question. A missing question, a duplicate or unknown question ID, a blank answer, or an answer over 5,000 characters returns 400 in the RUN-4 format and leaves the run unchanged. A question never accepts its proposed default as an answer.
- AC-AGT-64.5 An accepted reply returns `202 Accepted` with the run ID and message `Answers submitted.` and queues continuation. Each answer has its question, artifact version and submission time recorded and the question becomes `ANSWERED`. A reply when not waiting for the batch, including a repeated submission, returns 409 in the RUN-4 format and changes nothing.
- AC-AGT-64.6 After an accepted reply, processing continues automatically with the original requirement and all saved answers, and goes straight to the stories: there is no second round of questions and no further clarification artifact, so the stories are the next artifact version after the batch. The stories use the answers as decisions, and every choice that no answer decided is written by the agent as a plain-text statement in the `assumptions` array of the stories artifact. Unanswered questions never become accepted decisions, and model-call budgets continue across both phases. Empty fields from the other output phase are accepted and normalised away.
- AC-AGT-64.7 When the clarification answer has no questions, no clarification artifact is stored, the run does not wait, and stories are generated at once. The stories artifact is then the first requirements artifact version and carries the agent's assumptions. The requirements reference then passes through the existing flow. Dummy-stage routing is unchanged.
- AC-AGT-64.8 After a process restart on the same database, a waiting run retains its batch, answers and question statuses. It does not regenerate the batch or run downstream work while waiting; submitting its complete reply allows continuation without rerunning finished stages.
- AC-AGT-64.9 The OpenAPI document describes the questions and replies operations, the question payload, clarification wait and `WAITING_FOR_INPUT` status. Stage logs contain only identifiers, versions, statuses and counts, never requirement, question, assumption or answer text.

Batch reply example:

```json
{
  "answers": [
    {"questionId": "Q-1", "answer": "Reuse the existing code for duplicate URLs."},
    {"questionId": "Q-2", "answer": "Codes never expire."}
  ]
}
```

Every question needs exactly one answer.

Out of scope for this story: clarification from other roles, a second round of questions after the reply, asking the requester to confirm assumptions, story approval or rejection, brownfield changes, alternative language profiles, authentication, and automatic acceptance of unanswered question defaults. No live model is needed for the basic acceptance tests; they use scripted answers.

## Part C. The pipeline: stages, code, build, retry and delivery

Shown when: a posted requirement goes through every stage, the generated code is committed on a branch of this repository, built and tested in a sandbox container, retried when a stage or the run fails, and delivered.

### AGT-21 Write the run's documents as files in its repository

Status: coded

Depends on: AGT-20 (in [the backlog](agent-pipeline-backlog.md); replaced by AGT-67).

As a reviewer, I want the README, the stories, the design, the plan and the QA and review results to exist as readable files in the run's repository, so that the delivered project is complete and its evidence can be read without a database.

- AC-AGT-21.1 After the documentation, QA and review stages have joined, and when the development agent is on, the controller writes these files into the run's workspace and commits them in one commit: `README.md` from the documentation artifact (its setup, usage, architecture, examples, known limits and evidence links as sections), `docs/requirements.md`, `docs/design.md`, `docs/plan.md`, `docs/qa.md` and `docs/review.md` from the latest version of each artifact, and each diagram source of the design as its own file `docs/diagrams/<n>.mmd`. A file that already exists at one of those paths, for example a README written by the development stage, is replaced. Rendering is plain code and turns any JSON value into Markdown; no model is called.
- AC-AGT-21.2 Writing the same artifact versions twice produces identical files and no second commit.
- AC-AGT-21.3 The commit author is `controller`, and the message names the run and the artifact kinds and versions written.
- AC-AGT-21.4 The files hold the artifacts only. They hold no call records and no log text.
- AC-AGT-21.5 A run that failed before these stages joined writes none of these files.

### AGT-62 Run the whole pipeline once, from Postman, on a real model

Status: coded

Replaced in part: AC-AGT-62.7 (a new git repository made with `git init`) by AC-AGT-67.1 (a worktree of this repository on the branch `run/<run-id>`), and AC-AGT-62.4 for the requirements stage by AGT-64. AC-AGT-62.6 is extended by AGT-69 (deleting files).

Not proved: AC-AGT-62.11 (no test names it; the end-to-end test of AC-AGT-62.15 posts a requirement and the run goes on by itself) and AC-AGT-62.16 (the opt-in live end-to-end test is not written, and it would use `scenarios/greenfield.md`).

Depends on: AGT-6 to AGT-12, AGT-16.

As the platform owner, I want to post one requirement and watch the run go through every stage with a real model and end with committed, built and tested code, so that the whole platform is proved end to end before the later controls (approvals, pauses, repair, gates) are added on top of it. This is a deliberately thin first run: it covers the work of AGT-17, AGT-18, AGT-22, AGT-23, AGT-29 and AGT-33 in their smallest form; those stories are in [the backlog](agent-pipeline-backlog.md).

The story is built in five parts that do not depend on each other: the model client (A), the stage agents (B), file changes and the commit (C), the sandboxed build (D) and the run read operations (E). A scripted end-to-end test joins them (F).

A. Model client

- AC-AGT-62.1 A second implementation of the `ModelClient` port calls a hosted model. A profile chooses it by configuration (address, model identifier, window, answer limit, key). The key is read from the environment only. It returns the same typed results and failures as AC-AGT-3.x (text, finish reason, reported token counts, retryable or not) and never logs a prompt or an answer. A stub server proves it in tests; an opt-in live check sends one small request and asserts only structure.

B. Stage agents

- AC-AGT-62.2 The switch of AC-AGT-12.3 exists for every role (`agent.stages.<role>=agent`), with the dummy as the default. A generic agent stage node serves `impactAnalysis`, `design`, `planning`, `development`, `qa`, `review` and `documentation`.
- AC-AGT-62.3 An agent stage reads only the artifacts its role may read (AGT-16), calls the role through the structured answer service (AGT-5 to AGT-8), stores one artifact of the role's kind (AGT-11), and passes only its reference to the graph state.
- AC-AGT-62.4 In this first run open questions do not pause the run. They stay in the artifact, and each unanswered question is also listed as an assumption. Pausing is AGT-13 (in [the backlog](agent-pipeline-backlog.md)).
- AC-AGT-62.5 The order and the parallel join of FLOW-1 are unchanged: QA, review and documentation run side by side after development and join before the run ends.

C. File changes and the commit

- AC-AGT-62.6 The development role answers with a change set: a list of files, each with a path and its full new content. A path under `.git`, an absolute path, a path with `..`, a link, an empty change set, or a file over the size limit makes the answer invalid, and the repair of AGT-5 applies.
- AC-AGT-62.7 The controller writes the files into the run's workspace, a new git repository made with `git init` in the folder `<base>/<run-id>` under the configured base directory (nothing is cloned and there is no source setting), and makes one commit on its branch `main` with the development role as author and the run id in the message. Creating the workspace again for the same run reuses it. Nothing is written outside the base directory, and the controller's own checkout is never changed.

D. Sandboxed build and test

- AC-AGT-62.8 After the commit the controller builds and tests the workspace in a new container that is removed afterwards. The container runs as a non-root user with limits on CPU, memory, processes and time, has no network, receives the workspace by copy and returns only its reports by copy, and has a label that names the platform. The controller starts it with the `docker` command line behind a port, and a fake stands in for the port in tests.
- AC-AGT-62.9 The result is stored with the commit id: the build outcome, the tests run, passed and failed with the names of the failing tests, and a bounded excerpt of the report. A missing, unreadable or timed-out result is NO_RESULT, and NO_RESULT is a failure.
- AC-AGT-62.10 The run ends SUCCEEDED only when every stage finished, the commit exists, and the build and every test passed. Otherwise it ends FAILED with a reason. The reason `VERIFICATION_FAILED` is added to the list of AC-AGT-9.1, and the stored failure reasons accept it.

E. Run read operations (what Postman calls)

- AC-AGT-62.11 `POST /v1/runs` with `{"requirement": "..."}` is unchanged and answers 201 with the run id. The run then goes on by itself, with no further call.
- AC-AGT-62.12 `GET /v1/runs/{id}` also shows, while they exist: each stage with its state (not started, running, finished), the stored artifacts (kind, version, summary), the commit id, and the verification result. It never shows requirement, question, answer or model text beyond what is already shown today.
- AC-AGT-62.13 `GET /v1/runs/{id}/artifacts/{kind}/{version}` returns that artifact's content as JSON. An unknown run, kind or version is 404 in the RUN-4 format.
- AC-AGT-62.14 The API document lists the new fields and the new operation, so that Postman can import it from `/v3/api-docs` and the Swagger page shows it. This updates AC-SWAG-1.3 and AC-SWAG-1.4 with the user's approval, noted in the steps log.

F. End to end

- AC-AGT-62.15 A scripted end-to-end test in `./mvnw verify` posts a requirement through the HTTP API with a scripted model and a fake container port, waits for the run, and checks: status SUCCEEDED, an artifact of every stage, a commit on `main` of the run's repository that contains the files of the scripted change set, and a stored passing verification result. A second case with a failing scripted build ends FAILED with `VERIFICATION_FAILED`.
- AC-AGT-62.16 An opt-in end-to-end test, enabled by a property and the key in the environment, runs the real model and the real container on the requirement in `scenarios/greenfield.md`. That file asks for a small link-shortening service and pins the language and test tool (Python, the standard library, SQLite and pytest). The platform code contains neither. The test asserts structure only: the run ends SUCCEEDED, or FAILED with a reason; an artifact exists for every stage that ran; the commit and the verification result exist when the run got that far. It never asserts wording. `./mvnw verify` skips it.

### AGT-65 Retry a failed stage, with fix feedback for a failed build

Status: done

Disposition (2026-10-05): Closed as already built: bounded stage retries and build-failure feedback to development have tests.

Depends on: AGT-5 to AGT-9, AGT-62.

As the platform owner, I want a stage that fails to be attempted again, and a failed build to send its failures back to the development agent, so that a run that is almost right does not end FAILED.

- AC-AGT-65.1 When a stage fails with OUTPUT_INVALID, MODEL_UNAVAILABLE, VERIFICATION_FAILED or an unexpected error, the stage is attempted again, up to `agent.stage.max-retries` more times (default 2). BUDGET_EXHAUSTED, CONTEXT_TOO_LARGE, ACCESS_DENIED and any other failure are not retried: the run fails at once with its reason.
- AC-AGT-65.2 Each attempt of an agent stage stores a new artifact version and later stages use the latest. Every attempt's model calls count in the run's budget.
- AC-AGT-65.3 When the failed stage is development after VERIFICATION_FAILED, the attempt is a fix attempt: the development agent receives the names of the failing tests, the bounded report excerpt and the current text of the workspace's files (bounded in file count and total size), and its change set is committed on top as a new commit and verified again.
- AC-AGT-65.4 When every attempt fails, the run ends FAILED with the reason of the last failure.
- AC-AGT-65.5 A stage that fails is recorded with the state FAILED, and one that is attempted again is RUNNING again. The stage list of the run read response, the API document and the reviewer page show FAILED, and the page shows no "Building..." for a failed run. This adds FAILED to the state lists of AC-AGT-62.12 and AC-UI-1.3.
- AC-AGT-65.6 A test report in which no test passed (every test skipped, or none run) is not a pass.
- AC-AGT-65.7 A replay call with no recorded answer ends the run with REPLAY_MISSING, not MODEL_UNAVAILABLE.
- AC-AGT-65.8 Each attempt is logged with the run id, the stage, the attempt number and the failure reason code only.

### AGT-66 Retry a failed run, resuming first and then restarting once

Status: done

Disposition (2026-10-05): Closed as already built: failed-run retry resumes first and restarts once within the configured attempt limit, with tests.

Depends on: AGT-65.

As a reviewer, I want to retry a failed run, first from the stage that failed and then, if that fails again, from the beginning, so that a failure does not cost the work that was already done.

- AC-AGT-66.1 `POST /v1/runs/{id}/retry` on a FAILED run that has attempts left (`agent.run.max-attempts`, default 3 per run) queues it again and answers 202. A run that is not FAILED or has no attempts left is refused with 409 in the RUN-4 format, and an unknown run with 404. Nothing changes on a refusal.
- AC-AGT-66.2 The first retry resumes at the earliest stage that is not FINISHED. The state is rebuilt from what is stored (the requirement, the run kind, the latest version of each artifact, the commit), stages before it do not run again, and the parallel group (QA, review, documentation) resumes as a group.
- AC-AGT-66.3 If the resumed attempt fails, the run is attempted once more from the beginning, reusing the stored clarification answers without asking again, when attempts are left.
- AC-AGT-66.4 The run stores its attempt number and where its attempt started. The run read response shows `attempt` and `resumedFrom`. The failure reason is cleared when the run is queued and set again if it fails.
- AC-AGT-66.5 The run's call budget and the stage retries of AGT-65 apply in every attempt. The attempt cap is checked by plain code.
- AC-AGT-66.6 The API document lists the operation and the new fields, and the reviewer page shows a Retry button on a FAILED run.

### AGT-67 Work on a branch of this repository

Status: coded

Delivery update (user decision, 2026-10-05): AGT-68 supersedes the manual-only delivery part of AC-AGT-67.5 and AC-AGT-67.7 when automatic delivery is enabled. Workspace creation and development commits still leave the controller checkout unchanged. The historical criteria below are retained.

Not proved: AC-AGT-67.7 (a documentation criterion, checked by reading the README and ADR 0010; `scripts/deliver.sh`, which it names, has since been removed).

Depends on: AGT-20 (in [the backlog](agent-pipeline-backlog.md); replaced by this story), AGT-50, AGT-21.

As a reviewer, I want each run's generated product and the agents' commits to be real commits of this repository, so that one repository holds the controller, the product and both sets of history, and a merge puts the product next to the controller.

- AC-AGT-67.1 A run's workspace is a git worktree of the platform repository (`workspace.repository`, the repository's path, required when the development agent is on), created with `git worktree add` on a new branch `run/<run-id>` cut from `workspace.base-ref` (default `HEAD`), in the folder `<workspace.base>/<run-id>`, with a sparse checkout of the product folder (`workspace.product-folder`, default `apps/url-shortener`) and `runs/<run-id>/` only. There is no `git init` and no clone. Creating the workspace again for the same run reuses it. A run that changes no earlier run starts with an empty product folder, whatever the base ref holds there: one commit by the controller removes the files of an earlier run, so that they do not stay beside the new ones.
- AC-AGT-67.2 The agents' files keep flat relative paths (`app.py`, `tests/test_x.py`); the controller writes them under the product folder. The path rules are unchanged (nothing under `.git`, no `..`, no absolute path, no link, bounded size, no clash). The workspace-file reader and the existing-files input show paths relative to the product folder.
- AC-AGT-67.3 Every commit is made in the worktree on the run's branch: one commit per change set by the author `development`, and the document export by `controller`. The export writes the README and `docs/` under the product folder, and also the run's evidence under `runs/<run-id>/`: the latest version of each artifact as a JSON file and the run's lineage as JSON, in the same commit.
- AC-AGT-67.4 The sandbox build is given the product folder's files only and runs its command from that folder's root, as before.
- AC-AGT-67.5 A run never changes the platform repository's checked-out branch, its index or its working files: a test shows `HEAD` and `git status` of the platform checkout unchanged after a run, and two runs side by side get two worktrees. The controller never merges, pushes or deletes a branch.
- AC-AGT-67.6 A change run (BROWNFIELD) is cut from the base run's branch `run/<baseRunId>` instead of `workspace.base-ref`, so its history extends the base's, and the base's branch is unchanged. When the base's branch does not exist the run fails with the reason UNEXPECTED and nothing is written. The `baseRunId` validation of AGT-50 is unchanged.
- AC-AGT-67.7 The documentation says how a reviewer reads and takes the work: `git log --graph run/<run-id>`, merging with `git merge --no-ff run/<run-id>`, and pushing the branches. AGENTS.md decisions 2, 4 and 5 and ADR 0006 describe the arrangement (a new decision record supersedes ADR 0006), and `scripts/deliver.sh` is kept only for runs made before this change.

### AGT-68 Automatically bring completed work into the controller checkout

Status: coded

Depends on: AGT-67, AGT-21.

As a requester, I want the controller to merge completed work automatically into the repository checkout, so that I can see and run the generated product under apps/url-shortener/ without a manual Git command.

- AC-AGT-68.1 When `workspace.auto-merge=true`, a run whose development verification passed and whose workflow and document export finished is merged into the currently checked-out branch of `workspace.repository` before it becomes SUCCEEDED. Its product files and evidence appear there; existing design and planning approvals still apply. The live profile enables this setting; the default is false.
- AC-AGT-68.2 Delivery preserves the run's commits and authors and creates a merge commit by `controller` with the run ID. Repeating delivery of an already merged branch creates no commit. The run branch and its worktree are retained; nothing is pushed.
- AC-AGT-68.3 A checkout with local tracked or untracked edits, a detached HEAD, or a Git operation already in progress is not changed. Delivery fails with the stable reason DELIVERY_BLOCKED, retains the generated work, and can be attempted again through the existing failed-run retry after the checkout is ready.
- AC-AGT-68.4 Conflicting changes are refused before the checkout is changed. Only the configured product folder and run evidence may differ on the run branch from its common ancestor with the checkout. Unrelated committed platform changes are retained; a run that changes other paths is refused.
- AC-AGT-68.5 A failed, stopped or waiting run is not delivered. The completion transaction checks stop requests before delivery and holds the run lock until success is recorded, so a stop cannot be accepted halfway through delivery. With automatic delivery disabled, existing run behavior leaves the controller checkout unchanged.

### AGT-69 Delete files in a change set, and do not re-check unchanged code

Status: coded

Depends on: AGT-62, AGT-65.

As the platform owner, I want the development agent to be able to delete and rename files, and a fix attempt that changes nothing to cost no build, so that a repair such as renaming a badly named file can be made and a run does not check the same broken commit again and again.

- AC-AGT-69.1 A change in the development answer may be `{"path": "<relative path>", "delete": true}` instead of a path with content. The same path rules apply as for a written file, a change set that holds only deletions is valid, and a path may appear only once in a change set (written or deleted, never both). A change that has both `content` and `delete: true`, or that deletes a path ending in `/`, makes the answer invalid and the repair of AGT-5 applies.
- AC-AGT-69.2 The controller removes a deleted file from the product folder and from the commit. Deleting a file that is not there is not an error and changes nothing. Only files are deleted: a path that is a folder refuses the whole change set and nothing is written.
- AC-AGT-69.3 The development role's text and the task of a fix attempt explain deleting, and renaming as writing the new file and deleting the old one.
- AC-AGT-69.4 A fix attempt whose change set leaves the product unchanged (no file differs after it) does not run the build. It counts as an attempt of the stage and is logged as unchanged with the run id, the stage and the attempt number only. The next attempt's task says that the previous answer changed nothing and that at least one file must change or be deleted.
- AC-AGT-69.5 An example in tests shows the repair that failed in practice: a first commit holds a module named like a standard library module and its failing tests, the fix attempt writes the module under a new name, updates the imports and deletes the old file, and the second build passes.

## Part D. Changing an earlier run

Shown when: a change request on a finished run starts from that run's branch, the agents see the existing files, and the impact is analysed before the design.

### AGT-49 Understand existing code before changing it

Status: done

Disposition (2026-10-05): Closed as already built: existing-code context, impact analysis and the scripted brownfield end-to-end test exist. Brownfield support is retained.

Updated by AGT-67: the "repository" of a run in AC-AGT-49.4 is the run's branch `run/<run-id>` of this repository.

Not proved: AC-AGT-49.5 (a scenario file; checked by reading `scenarios/brownfield.md`, not by a test).

Depends on: AGT-16, AGT-50, AGT-65.

As a requester, I want the agents of a change to see the existing code, so that the impact is analysed and the change fits what is there.

- AC-AGT-49.1 For a BROWNFIELD run the impact analysis, design, planning and development agents also receive the existing files of the workspace (`SELECTED_WORKSPACE_FILES`), bounded in file count and total size as in AGT-65, before the other inputs they already get. A GREENFIELD run is unchanged.
- AC-AGT-49.2 A BROWNFIELD run goes through the impact analysis stage after the requirements and before the design, and the design and planning agents receive its artifact.
- AC-AGT-49.3 Development changes existing files with whole-file rewrites and adds new ones. The committed change set is built and tested as before, running the whole test suite including the base's tests.
- AC-AGT-49.4 A scripted end-to-end test runs a GREENFIELD run to SUCCEEDED and then a BROWNFIELD run on it: it ends SUCCEEDED, the impact stage ran, the second repository's history holds the first run's commits and new ones, the base run's repository is unchanged, and the development prompt contained the existing file text.
- AC-AGT-49.5 `scenarios/brownfield.md` holds a requirement that changes the product built by an earlier run (for example link expiry), with the way to start it.

### AGT-50 Choose the run kind and name the base run

Status: done

Disposition (2026-10-05): Closed as already built: run kind, validated base-run selection and worktree inheritance exist. Brownfield support is retained.

Replaced in part: AC-AGT-50.5 (a git clone of the base run's workspace repository) by AC-AGT-67.6 (the change run's branch is cut from the base run's branch `run/<baseRunId>`).

Not proved: AC-AGT-50.6 (the page's own behaviour, checked by hand in a browser; there is no browser test).

Depends on: AGT-20 (in [the backlog](agent-pipeline-backlog.md); replaced by AGT-67).

As a requester, I want to say that a run changes the result of an earlier run, so that the platform starts from that code and not from nothing.

- AC-AGT-50.1 `POST /v1/runs` accepts an optional `kind` (`GREENFIELD` by default, or `BROWNFIELD`) and an optional `baseRunId`. A BROWNFIELD run needs a `baseRunId`, and a GREENFIELD run must not have one. A kind that is neither, a `baseRunId` that is not a UUID, a missing `baseRunId` for BROWNFIELD and one given for GREENFIELD are each refused with 400 in the RUN-4 format, and nothing is stored.
- AC-AGT-50.2 The base run must exist, be SUCCEEDED and have a stored commit. Otherwise the request is refused with 400 in the RUN-4 format and nothing is stored. A path or a repository address is never accepted as a base.
- AC-AGT-50.3 A request without `kind` behaves exactly as before and creates a GREENFIELD run. This updates the "request body unchanged" clause of AC-FLOW-1.6 with the user's approval, noted in the steps log.
- AC-AGT-50.4 The run stores its kind and base run id. The run read response shows `kind` and, for BROWNFIELD, `baseRunId`. The API document lists both request fields and both response fields (this updates AC-SWAG-1.3 and AC-SWAG-1.4).
- AC-AGT-50.5 The workspace of a BROWNFIELD run is a git clone of the base run's workspace repository, so it starts with the base's files and history and new commits stack on top. The base run's repository is never changed. When the base run's workspace no longer exists the run fails with the reason UNEXPECTED and nothing is written.
- AC-AGT-50.6 The reviewer page's start box offers "change an existing run": a choice of the runs the page remembers, or a pasted run id, sent as `baseRunId` with the kind BROWNFIELD. This is checked by hand.

## Part E. Metrics, lineage and CI

Shown when: the reliability numbers and the lineage of a run can be read, and every change is checked in CI.

### AGT-46 Measure how reliably the platform runs

Status: coded

Not proved: AC-AGT-46.4 (the page's own behaviour, checked by hand in a browser; there is no browser test).

Depends on: none. The numbers come from data the platform already stores (runs, model calls, verification results).

As the platform owner, I want reliability numbers computed from what the platform stores, so that I can see how often runs succeed, retry, fall back and fail, and how long they take.

- AC-AGT-46.1 `GET /v1/metrics` returns, computed by plain code from the stored runs, model calls and verification results: the number of runs and the count per status; the success rate (SUCCEEDED over finished runs); the count per failure reason; the end-to-end latency of finished runs (average, median and 95th percentile, in seconds, with the time spent waiting for people included and said so); the number of model calls in total and per run on average; the number of retries, repairs and fallbacks and each one's share of all model calls; the sum of the reported prompt and answer tokens; and the verification pass rate (PASSED over all stored results). Mean time to recovery and the rollback frequency are present with the value null and a short text saying the platform has no recovery or rollback yet.
- AC-AGT-46.2 With no runs every count is 0 and every rate or latency is null; the operation never fails because there is no data.
- AC-AGT-46.3 The response holds numbers and fixed labels only: no requirement, question, answer, model text or run id.
- AC-AGT-46.4 The reviewer page shows these numbers in a small metrics section.

### AGT-47 Trace any change back to its requirement

Status: coded

Not proved: AC-AGT-47.3 (the page's own behaviour, checked by hand in a browser; there is no browser test).

Depends on: AGT-11.

As a reviewer, I want to ask why a run's output exists and be told which artifacts it was built from and which files serve which criteria, so that every change can be justified.

- AC-AGT-47.1 `GET /v1/runs/{id}/lineage` returns the run's artifacts (kind, version, fingerprint and summary), for each artifact the artifacts it was built from (kind, version and fingerprint, from its stored `consumed` references), the commit id and the verification outcome when they exist, and the files of the development artifact, each with the criterion ids that the development agent says it serves (`affectedCriteria`).
- AC-AGT-47.2 The answer is built only from stored artifacts and their consumed references. An unknown run is a 404 in the RUN-4 format. A run with no artifacts yet returns empty lists.
- AC-AGT-47.3 The reviewer page shows, for each artifact, what it was built from, and the files with their criteria.

### AGT-61 Run the checks in CI

Status: coded

Not proved: AC-AGT-61.1 and AC-AGT-61.2 (a GitHub Actions workflow, `.github/workflows/verify.yml`; it can only be proved by running it on GitHub, so no test names these criteria).

Depends on: none.

As the platform owner, I want every change checked automatically, so that tests and coverage cannot regress unnoticed.

- AC-AGT-61.1 A workflow runs `./mvnw verify` on every change, with Docker available for the integration tests, and fails when a test fails or coverage is below the gate.
- AC-AGT-61.2 The unit coverage report is kept as a build artifact.

## Outside this file

Still part of the project, but kept in other files:

- **Reviewer interface:** the page that follows a run, answers questions, approves, stops and starts a change is in [reviewer-ui.md](reviewer-ui.md). The run API is in [run-api.md](run-api.md), where BUG-1 (an oversized request body must be refused before Spring reads all of it) is deferred.
- **Written documents:** the [architecture overview](../architecture.md), the [limitations](../limitations.md), the [testing approach](../testing.md), the [engineering summary](../engineering-summary.md), the [decision records](../../adr/) and the [steps log](../../steps.md), which records the AI-assisted work. These are written by hand with the agents' help and are not stories.
