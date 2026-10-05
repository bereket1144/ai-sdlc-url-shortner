# Agent pipeline requirements

## Request

The SDLC graph in `sdlc-flow.md` runs eight stage nodes that do dummy work. This feature replaces the dummy work with real agents. Each agent is a senior expert with a written identity. It calls an AI model, asks the reviewer when something is unclear, and hands a versioned, checked result to the next stage.

A run goes from a written requirement to approved stories, an approved design, a task plan, committed code, tests, review and documentation. It ends when every acceptance criterion of the stories is proved by a passing test and a person has approved the exact commit.

The first model is a small local one, so the agents must work unchanged on a large hosted model later. In this file "agent" means one of the platform's own SDLC agents, not a coding assistant.

## How the file is organised

- Eight parts, in order. A story is one working behaviour with every layer it needs and its tests, small enough for one logical step. Each story names what it depends on.
- **Part A** roles and model access. **Part B** requirements and the clarification loop. **Part C** design and plan. **Part D** code. **Part E** checks, review and repair. **Part F** recovery, audit and metrics. **Part G** change, brownfield and re-planning. **Part H** scenarios, evaluation and delivery.
- Story numbers are labels, not order. A story added later took the next free number and sits in the part where it belongs. The build order is given below.
- Parts A to C are ready to code. **Parts D to H are drafts.** Their criteria are confirmed with the user before the first story of the part starts, because they depend on what the earlier parts teach us and on the sandbox design. Parts F to H are outlines with fewer criteria than the earlier parts.
- Build order, each step ending in something that can be shown:
  1. AGT-1 to AGT-5: a role asks a model and gets a checked answer.
  2. AGT-11 and AGT-12: the first real agent stores a requirements artifact, with the opt-in live check.
  3. AGT-7, AGT-8 and AGT-9: the agents' calls are recorded and bounded, and a failed run says why.
  4. AGT-34, AGT-13, AGT-14, AGT-15 and AGT-35: ask, answer, approve and resume, on readable checkpoints and a bounded dispatcher.
  5. AGT-6, AGT-10 and AGT-36: retry and fall back, replay, and time limits.
  6. Then Part C, and Parts D to H in order.
- Every story is proved with a scripted model. A story is not coded until its criteria pass without a live model.

## Ambiguities and assumptions

### Models

- **A model is reached through its HTTP API, set by configuration** (address and model identifier). No library for it is added. The first one is a local Ollama server. A hosted model is a second implementation of the same port, added later.
- **The context window belongs to the model profile, not to the agents.** The trial model reports a context length of 32,768 tokens (`qwen2.context_length`). A hosted model will have a far larger one. No role definition, prompt text or node depends on a window size, so moving to a large model is a configuration change.
- **A local server may use a smaller window than the model supports** unless each request states the window it wants. Every call states the profile's window and answer limit explicitly. The design step confirms how the local API takes them (assumed: its chat endpoint with a window option and an answer-length option; check against the installed version).
- **Token counts are estimates.** They come from characters and a configurable ratio, pessimistic by default (assumed: 3 characters per token). The counts a model reports are recorded when it reports them.
- **Calls are limited per model profile** (default 1 at a time). The QA, review and documentation branches run side by side in the graph; on a one-call model they take turns.
- **Live model checks are opt-in.** `./mvnw verify` never calls a model. Tests use a scripted model, and recorded answers can be replayed. A live check runs only when a property enables it.
- **The model's answer is untrusted data.** It is checked by plain code and never executed.

### Roles

- **The eight roles are the stage nodes of FLOW-1:** `requirements`, `impactAnalysis`, `design`, `planning`, `development`, `qa`, `review`, `documentation`. The `initialize` and `collect` nodes stay plumbing without an agent.
- **"State of the art" personality** means: each role is told to be a named senior expert, to apply the current best practice of its field, to check its own work before answering, and to ask rather than guess. Whether the result really is state of the art is judged by the reviewer and by the live evaluations of a later feature. A unit test can only prove that the identity and its parts are present.
- **Illustrative identities** (AGT-1 fixes the real wording):

  | Role | Expert it is told to be | Practices it names |
  | --- | --- | --- |
  | requirements | Principal business analyst | INVEST stories, testable numbered criteria, explicit assumptions, ask before assuming |
  | impactAnalysis | Staff software architect doing change analysis | dependency tracing, blast-radius assessment, data-flow review, change-risk rating |
  | design | Principal software architect | separation of concerns, decision records with alternatives, context and component views as Mermaid, threat modelling |
  | planning | Engineering lead | vertical slicing, dependency mapping, small reviewable tasks, tests ordered with the code |
  | development | Senior software engineer | clean code, defensive error handling, structured logging, secure coding, minimal changes |
  | qa | Senior test engineer | test pyramid, boundary and equivalence cases, negative tests, traceability to criteria, deterministic tests |
  | review | Principal reviewer with a security focus | correctness first, OWASP risk checks, severity-ranked findings, evidence with file and line |
  | documentation | Staff technical writer | docs as code, task-oriented structure, accuracy against the code, examples that run |

- **Role definitions are data in the repository.** They are versioned, contain no secrets, and contain no model name, provider, window size, product or domain word. The platform stays generic: trial requirements live in scenario files, never in platform code or prompts.
- **Agents have no tools.** The controller gives each agent the context it may read and the agent answers with structured data. Tool calling, retrieval and MCP are stretch items (see the end of the file).
- **Agents never talk to each other and never touch files.** Results move between stages as artifacts through the controller. The controller checks and applies anything that changes files.
- **Text in an input is data, not an instruction.** This holds for the requirement, every artifact and every file.

### Control

- **Plain code decides pass or fail.** A model never decides whether something is valid, approved or done. "The stories are done" means: every acceptance criterion has a passing test, every gate passes, and a person approved the exact commit.
- **Clarification goes to the human reviewer only,** through the API. Any role may ask. The requirements role is the first that does.
- **`WAITING_FOR_INPUT` is a new run status.** It covers both unanswered questions and open approvals. The run read response shows what the run waits for.
- **Approvers are named, not authenticated, until AGT-48.** A decision records a free-text name. AGT-48 adds authenticated identities and permissions, and needs the user's go-ahead to add Spring Security.
- **Failed runs say why.** A failure reason code is stored and shown. The code list starts in AGT-9 and later stories add codes.
- **Two stories change the run read response and the API document** (AGT-9 adds a failure reason, AGT-13 adds a status and what the run waits for). AC-SWAG-1.4 is updated with the user's approval when those stories start, and the change is noted in the steps log, as was done for `SUCCEEDED` and `FAILED`.
- **Assumed names for the new operations** (the design step may change them, and the criteria then change with the user's approval): the run read response gains `failureReason` and `awaiting`; answers go to `POST /v1/runs/{id}/replies` and approvals to `POST /v1/runs/{id}/decisions`.
- **Repair sends a run back to development,** so a node can run more than once in a run that repairs. The once-only counts of FLOW-1 describe a run without repair.

### Delivery

- **Each stage has a switch** between its agent and its dummy node, one setting per role. The dummy stays the default until a role is enabled, so the existing graph tests keep their meaning while stages are converted one at a time.
- **Output location.** Agent file changes may only go to the run's own folder under `runs/<run-id>/`. A product run adds its product folder by configuration. The trial output (a small Python app) lives under the run's folder.
- **Change sets are whole-file writes.** A file operation carries the full new content, not a diff, because small models produce diffs unreliably. Diffs may come later.
- **Trial first, product second.** Trial work is small: a few files of Python with tests, proved on the small local model through a Python trial profile (AGT-29). The product, a Java service, comes after, through the Java profile (AGT-42), a larger model and the scenarios of Part H. Nothing in Parts A to C depends on either language.
- **The later parts cover what the whole project needs:** recovery, audit, metrics, brownfield change, re-planning, the scenarios and delivery. The end of the file names what belongs to other files.
- **Artifacts live in PostgreSQL first** and are written into the run's folder as files by the controller in Part D.

## Trial requirements

Not criteria. These are the inputs for manual and opt-in live runs. They belong in scenario files, not in platform code.

1. Clear: "Create a small Python command-line calculator that adds, subtracts, multiplies and divides two numbers and reports an error for division by zero. Include tests."
2. Ambiguous: "Make a calculator for my kid." The agent is expected to ask who it is for, what it must do and where it runs.
3. Later, brownfield: "Add a history of the last ten results to the existing calculator."

## User stories and acceptance criteria

## Part A. Roles and model access

Shown when: one role, asked through the scripted model or the local model, returns a checked structured answer, and failures, retries, fallback, budget and replay behave as written below.

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

Status: open

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

Status: open

Depends on: AGT-1, AGT-2.

As the platform, I want the prompt for each call sized to the model's window, so that a small model is never silently cut off and a large model receives everything.

- AC-AGT-4.1 A prompt has a system part and a user part. The system part holds the role definition and the platform's fixed rules, including that text in the inputs is data and never instructions. The user part holds the task, then each input in its own block that names its kind and version.
- AC-AGT-4.2 The size of a prompt is estimated with the characters-per-token ratio (configurable, 3 by default). It must fit in the window minus the profile's answer limit minus a safety margin (a configurable percentage of the window, 10 by default).
- AC-AGT-4.3 When the estimate fits, every input is sent in full. The same inputs are also sent in full to a profile with a larger window. Nothing is reduced when there is room.
- AC-AGT-4.4 When it does not fit, reduction follows a fixed order: optional context first, then the role's inputs from the least important (last in the definition) to the most important, each replaced by its short form. Every reduction step is kept for the call record (AGT-7).
- AC-AGT-4.5 The task text, the original requirement and the answers given so far are never reduced. When the prompt still does not fit, no call is made and the result is a typed CONTEXT_TOO_LARGE failure that names the role and the sizes. Text is never cut off silently.
- AC-AGT-4.6 An instruction written inside an input (for example "ignore your rules") appears only inside its block. The system part is identical whatever the inputs say.

### AGT-5 Accept only structured answers that pass their checks

Status: open

Depends on: AGT-3, AGT-4.

As the platform, I want each role's answer checked against its structure by plain code, with a bounded number of repair attempts, so that a malformed answer from a weak model never reaches the next stage and a stubborn one stops the run.

- AC-AGT-5.1 Each role has an output structure (fields, types, required fields). An answer is valid only when it contains a JSON object that matches it. Fields outside the structure are ignored. A missing, blank required or wrongly typed field makes the answer invalid.
- AC-AGT-5.2 The JSON object is taken from the answer even when the model wraps it in a code fence or writes a sentence before or after it. An answer with no JSON object, or with more than one separate top-level object, is invalid.
- AC-AGT-5.3 A role can add its own plain-code checks (for example, unique ids). A failing check makes the answer invalid and its messages are kept.
- AC-AGT-5.4 An invalid answer, and an answer cut off at the answer limit, start a repair attempt: the model is called again with the previous answer and the exact messages, up to the repair limit (default 2 per call). The first valid answer is used.
- AC-AGT-5.5 When the repair limit is used up, the result is a typed OUTPUT_INVALID failure with the last messages, and nothing is stored as an artifact.
- AC-AGT-5.6 Repair attempts and infrastructure retries (AGT-6) are counted separately. Using one never uses up the other.

### AGT-6 Retry model failures, then fall back to a second model

Status: open

Depends on: AGT-3, AGT-5.

As the platform, I want transient model failures retried and then moved to a second model, so that one slow or unreachable model does not stop a run needlessly.

- AC-AGT-6.1 A retryable failure is retried on the same profile with the same input, up to the profile's retry limit, waiting longer between attempts (the wait is configurable and tests use none). A failure that is not retryable is not retried.
- AC-AGT-6.2 When the retries on the primary are used up and a fallback profile exists, the same request is made once on the fallback, with the same role, inputs and checks, and with the prompt sized again for the fallback's window.
- AC-AGT-6.3 Only a failure of the call itself switches models. An invalid or cut-off answer is repaired on the profile that gave it (AGT-5) and never causes a switch.
- AC-AGT-6.4 When the fallback also fails, or there is none, the result is a typed MODEL_UNAVAILABLE failure.
- AC-AGT-6.5 Every attempt (first, repair, retry, fallback) is a separate call record (AGT-7) and counts toward the run budget (AGT-8).

### AGT-7 Record every model call

Status: open

Depends on: AGT-3.

As the reviewer, I want every model call recorded with what went in and what came out, so that I can trace any result to the call that made it and replay it.

- AC-AGT-7.1 Every attempt is stored with: the run, the role, the role version, the profile, the model identifier, the attempt kind (first, repair, retry, fallback), an input fingerprint, the prompt text, the answer text, the finish reason, the estimated and reported token counts, the prompt reduction steps, the duration and the outcome.
- AC-AGT-7.2 Failed, invalid and cut-off attempts are stored too, with their outcome.
- AC-AGT-7.3 A stored record is never changed. The service offers no operation that updates or deletes one.
- AC-AGT-7.4 A run's records can be listed in the order they happened by a read operation of the platform. An HTTP form of it belongs to the reviewer interface.
- AC-AGT-7.5 The input fingerprint depends on the prompt text and the profile name only, not on the run id or the time, so equal prompts have equal fingerprints.
- AC-AGT-7.6 Stored text never reaches a log.

### AGT-8 Stop a run that exceeds its budget

Status: open

Depends on: AGT-7.

As the platform owner, I want limits on how many model calls and tokens a run may use, so that a loop or a stubborn model cannot run forever or cost without bound.

- AC-AGT-8.1 Configuration sets, per run, the maximum number of model calls and of estimated tokens, and, per role, the maximum number of calls.
- AC-AGT-8.2 Before each attempt the runner checks the budget from the call records. When an attempt would pass a limit, no call is made and the result is a typed BUDGET_EXHAUSTED failure naming the limit.
- AC-AGT-8.3 Every attempt counts, including repair attempts, retries, fallbacks and failed attempts.
- AC-AGT-8.4 The budget belongs to the run. Pausing, resuming, answering or repairing does not reset it.

### AGT-9 Say why a run failed

Status: open

Depends on: AGT-5.

As a reviewer, I want a failed run to tell me why, so that I do not have to read the logs to learn what went wrong.

- AC-AGT-9.1 A failed run stores a failure reason code from a fixed list: OUTPUT_INVALID, MODEL_UNAVAILABLE, CONTEXT_TOO_LARGE, BUDGET_EXHAUSTED, REPLAY_MISSING, ACCESS_DENIED, REJECTED, ASSEMBLY_CONFLICT, STOPPED_BY_REVIEWER, STAGE_TIMEOUT, RECOVERY_FAILED and UNEXPECTED. A story that adds a failure adds its code here. The code comes with a short fixed sentence and never with model, requirement or answer text.
- AC-AGT-9.2 The run read response shows the reason when the run is FAILED and omits it otherwise.
- AC-AGT-9.3 The reason is stored in the same transaction as the FAILED status, so no run is FAILED without one.
- AC-AGT-9.4 An exception without a typed reason is stored as UNEXPECTED. A run that failed before this story reads back as UNEXPECTED.
- AC-AGT-9.5 The API document lists the field and the codes. This updates AC-SWAG-1.4 with the user's approval, noted in the steps log.

### AGT-10 Replay recorded answers instead of calling a model

Status: open

Depends on: AGT-7.

As a reviewer without a model, I want a run to replay recorded model answers, so that I can run the platform with no AI account and no local model while everything else still runs for real.

- AC-AGT-10.1 A run's call records can be exported to a recording, and a recording can be loaded. Loading what was exported gives the same records.
- AC-AGT-10.2 In replay mode the port serves the recorded answer or failure for the call with the same role, attempt kind and input fingerprint, and never contacts a model.
- AC-AGT-10.3 Replay changes nothing else. Answers are still checked, repaired, recorded and counted, and a recorded invalid answer is still invalid.
- AC-AGT-10.4 A call with no recording fails with a typed REPLAY_MISSING failure. Replay never falls back to a live call.
- AC-AGT-10.5 A recording can contain failed calls, so the retry and fallback paths replay too.
- AC-AGT-10.6 The mode (live or replay) and the recording to use come from configuration.

### AGT-36 Stop a stage that runs too long

Status: open

Depends on: AGT-5.

As the platform owner, I want a time limit on every stage, so that a hung model call, branch or task cannot hold a run forever.

- AC-AGT-36.1 Configuration sets a wall-clock limit per role (default 30 minutes). The limit counts only the time a stage is running. Time spent waiting for a person counts toward nothing.
- AC-AGT-36.2 When a stage passes its limit, its work is interrupted: model calls in progress are abandoned, sibling branches are asked to stop, and the run ends FAILED with the reason STAGE_TIMEOUT.
- AC-AGT-36.3 An interrupted stage leaves no partly applied change, because work that changes files is applied all or nothing (AC-AGT-23.1). This is checked from Part D on. The interruption is recorded.
- AC-AGT-36.4 A stage that finishes within its limit is unaffected, and one stage passing its limit does not stop other runs.

## Part B. Requirements and the clarification loop

Shown when: an ambiguous requirement produces questions, the reviewer answers them, and the stories are approved.

### AGT-11 Keep each stage's output as a versioned artifact

Status: open

Depends on: AGT-5.

As the platform, I want every agent output stored as a versioned, fingerprinted artifact with its lineage, so that each result can be traced to who made it and from what, and a later result never silently overwrites an earlier one.

- AC-AGT-11.1 An artifact has: the run, the kind, a version (1, 2, and so on, per run and kind), the validated content, a short summary, a fingerprint of the content, the producing role and role version, the profile and model identifier of the call that produced it, and the artifact versions it consumed.
- AC-AGT-11.2 Producing a kind again creates the next version. Older versions stay readable and unchanged.
- AC-AGT-11.3 Equal content gives an equal fingerprint, and changed content gives a different one.
- AC-AGT-11.4 An artifact can be read by run, kind and version. An unknown one is reported as not found.
- AC-AGT-11.5 The graph state holds only artifact references (kind, version, fingerprint), never content, so checkpoints stay small and nothing sensitive sits in graph state or logs.
- AC-AGT-11.6 A run's artifacts can be listed with their lineage.

### AGT-12 Turn a requirement into stories and criteria

Status: open

Depends on: AGT-1 to AGT-5, AGT-11.

As a requester, I want the requirements stage to turn my text into user stories with numbered acceptance criteria, stated assumptions and open questions, so that vague input becomes a clear engineering problem.

- AC-AGT-12.1 The requirements node calls the requirements role with the run's requirement and stores a requirements artifact. It holds: stories (id, title, role, want, benefit, and acceptance criteria each with an id and a text), assumptions, open questions (id, text, why it matters, optional proposed default) and a summary.
- AC-AGT-12.2 Plain-code checks make an answer invalid when a story has no criterion, an id repeats, a criterion id does not start with its story's id, or a text is blank.
- AC-AGT-12.3 The stage can be switched between the agent and the existing dummy node by configuration, one setting per role, and the dummy is the default. With the agent enabled and a scripted model, a requirement with no open questions passes an artifact reference to the next stage.
- AC-AGT-12.4 With every stage on its dummy, all FLOW-1 and FLOW-2 criteria still hold unchanged.
- AC-AGT-12.5 With the agent enabled, the node logs only the run id, the role, the artifact kind, version and fingerprint, and counts. It never logs requirement, question or answer text.
- AC-AGT-12.6 An opt-in live check sends the clear trial requirement to the configured model and asserts only structure: either a valid requirements artifact or a typed failure, never any wording. `./mvnw verify` skips it.

### AGT-34 Store checkpoints in a readable form owned by migrations

Status: open

Depends on: none.

As the platform owner, I want graph checkpoints stored as readable text in tables that our own migrations create, so that a paused run survives code changes and a reviewer can read what is stored.

- AC-AGT-34.1 The checkpoint tables are created by a database migration. The application does not create or change them itself when it starts.
- AC-AGT-34.2 A checkpoint stores the graph state as JSON text, not as Java serialised bytes. A test reads a stored checkpoint back as text.
- AC-AGT-34.3 A checkpoint written before a class change that adds or removes an unrelated field can still be read afterwards. Unknown fields are ignored and missing optional fields take their defaults.
- AC-AGT-34.4 The existing proofs (pause at a gate, resume in a new process, the parallel join) still pass on the new storage.

### AGT-13 Pause the run when an agent has questions

Status: open

Depends on: AGT-12, AGT-34.

As a requester, I want the run to stop and wait when an agent has questions, so that nothing is built on guesses.

- AC-AGT-13.1 When a validated answer from any role holds open questions and the run's clarification rounds are below the limit (default 3), the node stores the artifact, keeps the open questions, and the run's status becomes WAITING_FOR_INPUT. The dispatcher records neither success nor failure for a graph that stopped to wait.
- AC-AGT-13.2 A waiting run is not claimed by the scheduler. No model call is made and no thread is held while it waits.
- AC-AGT-13.3 The run read response shows the status WAITING_FOR_INPUT and what the run waits for: each open question (id, text, why, proposed default) and the artifact version they belong to.
- AC-AGT-13.4 After a process restart on the same database, the run is still waiting with the same questions, and no earlier node runs again.
- AC-AGT-13.5 An answer with more open questions than the limit (default 7) is invalid, so a repair attempt asks the agent for the most important ones.
- AC-AGT-13.6 When the clarification rounds are used up, the agent is told to go on and list every unanswered point as an assumption. Those assumptions are recorded in the artifact and the run goes on.
- AC-AGT-13.7 The new status appears in the API document. This updates AC-SWAG-1.4 with the user's approval, noted in the steps log.

### AGT-14 Answer the questions and resume

Status: open

Depends on: AGT-13.

As a requester, I want to answer the agent's questions and have the run carry on, so that the stage can use my answers.

- AC-AGT-14.1 A reply operation accepts, for a waiting run, one answer per open question, or the instruction to take that question's proposed default. An answer to an unknown question, a missing answer for a question with no proposed default, or an answer longer than 5,000 characters is refused with 400 in the RUN-4 format, and the run is unchanged.
- AC-AGT-14.2 A reply for a run that is not waiting for answers is refused with 409 in the RUN-4 format and changes nothing. A second reply to the same questions is refused the same way.
- AC-AGT-14.3 After an accepted reply the run continues without any manual dispatch call. The scheduler resumes it, and the same role is called again with the original requirement, all questions and all answers so far. The result is the next artifact version.
- AC-AGT-14.4 Resuming never repeats earlier work. The call records of earlier nodes are unchanged and no earlier node runs again.
- AC-AGT-14.5 Questions and answers are stored together with the time and the artifact version they belong to.

### AGT-15 Approve a stage's result before the next stage

Status: open

Depends on: AGT-13, AGT-14.

As a reviewer, I want to approve or reject the stories before any design is made, so that human oversight comes first and an agent never approves its own work.

- AC-AGT-15.1 When the role definition says a result needs approval and the stage has no open questions, the run waits (WAITING_FOR_INPUT) for an approval of that artifact version. The run read response shows the request: the artifact kind, version and fingerprint, and its summary. Requirements and design need approval.
- AC-AGT-15.2 The reviewer approves (name, optional note) or rejects (name, required comment). A blank name, or a decision for a version that is no longer the latest, is refused in the RUN-4 format and changes nothing.
- AC-AGT-15.3 Approval lets the run continue without a manual dispatch call, and later stages read that approved version.
- AC-AGT-15.4 Rejection calls the role again with the comment and all earlier feedback. The result is the next artifact version, which needs approval again. After the rejection limit (default 3) the run is FAILED with the reason REJECTED.
- AC-AGT-15.5 Every decision is stored: the approver's name, the decision, the comment, the artifact kind, version and fingerprint, and the time. A decision applies to that version only.
- AC-AGT-15.6 Approval is possible only through the decision operation. No agent path approves anything.

### AGT-35 Run several runs side by side within a limit

Status: open

Depends on: AGT-14.

As the platform owner, I want runs started on a bounded set of threads, so that one long run no longer holds up other runs or the resuming of runs whose questions were answered.

- AC-AGT-35.1 Configuration sets the maximum number of runs executing at once (default 2).
- AC-AGT-35.2 The scheduler hands a claimed or resumed run to a bounded pool and does not wait for it. With a free place it takes the next run at its next poll.
- AC-AGT-35.3 When every place is busy, further runs stay CREATED, or stay waiting to resume, and are taken as places free up. No run is started twice (AC-EXEC-4.3).
- AC-AGT-35.4 A run that waits for input holds no place.
- AC-AGT-35.5 A failure in one run does not stop other runs or the polling.
- AC-AGT-35.6 On shutdown the application stops taking runs and gives running ones a configurable time to finish. A run that does not finish stays RUNNING for reconciliation (AGT-39).

## Part C. Design and plan

Shown when: an approved spec becomes an approved design and a valid task plan. No code is written yet.

### AGT-16 Give each stage only what its role may read

Status: open

Depends on: AGT-1, AGT-11.

As the platform owner, I want each agent to receive only the artifacts its role is allowed to read, so that agents see what they need and nothing else, and every result records what it was built from.

- AC-AGT-16.1 A role's prompt is built only from the input kinds named in its definition. For a kind that needs approval, the approved version is used.
- AC-AGT-16.2 Asking the runner for an input that the role's definition does not list fails before any model call with a typed ACCESS_DENIED failure, and no content of that input is read.
- AC-AGT-16.3 A stored artifact records exactly the versions that went into its prompt.
- AC-AGT-16.4 A role's answer can only create an artifact of its own output kind. An answer cannot create or change an artifact of another kind, a decision or a role definition.

### AGT-17 Design from the approved spec

Status: open

Depends on: AGT-12, AGT-15, AGT-16.

As a reviewer, I want a design document with diagrams made from the approved stories, so that I can approve how it will be built before any code exists.

- AC-AGT-17.1 The design node reads the approved requirements artifact and stores a design artifact. It holds: an overview; components and their responsibilities; the data held; the interfaces; key decisions, each with the alternatives considered and the reason; risks; a mapping from every acceptance criterion to the components that satisfy it; diagram sources; open questions; and a summary.
- AC-AGT-17.2 Plain-code checks make an answer invalid when a criterion of the approved spec is missing from the mapping, the mapping names a criterion that does not exist, a decision has no reason, or a diagram source is blank or does not start with a Mermaid diagram keyword from a fixed list.
- AC-AGT-17.3 The design stage can ask questions (AGT-13, AGT-14), and its result needs approval (AGT-15) before planning starts.
- AC-AGT-17.4 The stage has the same agent or dummy switch as AGT-12.

### AGT-18 Plan the work as a checked task graph

Status: open

Depends on: AGT-17.

As the platform, I want the planner to break the approved design into tasks with dependencies, checked by plain code, so that work is ordered and every criterion is covered.

- AC-AGT-18.1 The planning node reads the approved requirements and design and stores a plan artifact. It holds: tasks (id, title, description, the tasks it depends on, the criteria it serves, the file paths it expects to write, and an estimated output size in tokens) and a summary.
- AC-AGT-18.2 Plain-code checks make a plan invalid when a task id repeats, a dependency names an unknown task, the dependencies contain a cycle, a task serves no criterion or names an unknown one, a criterion of the approved spec is served by no task, or a file path is absolute, contains `..` or is outside the allowed write area.
- AC-AGT-18.3 The planner is told the development role's answer limit. A task whose estimated output size does not fit in that limit minus the safety margin makes the plan invalid, so a small model gets small tasks and a large model gets larger ones.
- AC-AGT-18.4 The plan has a deterministic order: tasks whose dependencies are done are ordered by task id, and the plan shows that order.
- AC-AGT-18.5 The planning stage can ask questions and has the same agent or dummy switch as AGT-12.

### AGT-19 Take a requirement to an approved plan on a scripted model

Status: open

Depends on: AGT-14 to AGT-18.

As the platform owner, I want one end-to-end proof of the text pipeline, so that the pause, the answers, the approvals and the hand-over between stages are shown to work together.

- AC-AGT-19.1 With a scripted model, the agent enabled for requirements, design and planning, and the later stages on their dummies, a vague requirement goes through: questions, a reply, a second requirements version, approval, design, approval, plan, and then ends SUCCEEDED.
- AC-AGT-19.2 The run's artifacts are the approved requirements version, the approved design version and the plan, each recording the versions it consumed, and the chain can be followed from the plan back to the original requirement.
- AC-AGT-19.3 The call records show exactly the calls that were scripted. No call is repeated because of a pause or a resume.
- AC-AGT-19.4 No log line of the run contains requirement text, answers or scripted model text.
- AC-AGT-19.5 A restart while the run waits for answers, for the spec approval and for the design approval resumes each wait at the same point.
- AC-AGT-19.6 A failure at any stage ends the run FAILED with its reason and leaves the earlier artifacts readable.

## Part D. Code (draft)

Shown when: the tasks of the trial requirement become committed files in the run's own workspace.

### AGT-20 Give each run its own workspace and branch

Status: open

Depends on: AGT-19.

As the platform, I want every run to work in its own copy of the repository on its own branch, so that generated work never touches the controller's checkout or another run.

- AC-AGT-20.1 When a run reaches its first writing stage, a workspace is created: a fresh clone of the configured source at a path derived from the run id under a configured base directory, on a new branch named `run/<run-id>`.
- AC-AGT-20.2 Creating the workspace again for the same run reuses it and does not clone again.
- AC-AGT-20.3 Nothing is written outside the base directory. The controller's own checkout is never changed.
- AC-AGT-20.4 Commits are made only on the run branch, never on the main branch.
- AC-AGT-20.5 A run's workspace can be removed when the run ends. Removal deletes only that run's folder.

### AGT-21 Write approved artifacts as files in the run's folder

Status: open

Depends on: AGT-20.

As a reviewer, I want the stories, the design and the plan to exist as readable files in the run's folder, so that the evidence of a run can be read without a database.

- AC-AGT-21.1 The controller writes each approved or produced artifact into the workspace as readable files under `runs/<run-id>/<kind>/`: Markdown for the text, the diagram sources as separate files, and the artifact's structured content, and commits them. Rendering is plain code.
- AC-AGT-21.2 Writing the same artifact version twice produces identical files and no second commit.
- AC-AGT-21.3 The commit author is the controller. The message names the run, the artifact kind and the version.
- AC-AGT-21.4 The files hold the artifact only. They hold no call records and no log text.

### AGT-22 Let the development agent propose file changes

Status: open

Depends on: AGT-18, AGT-20.

As the platform, I want the development agent to return file changes for one task as structured data, so that the controller, not the agent, decides whether and how they are applied.

- AC-AGT-22.1 For one task, the development node is given the task, the criteria it serves, the relevant part of the design, the summaries of the tasks it depends on, and the current content of the files the task lists, sized by AGT-4. A listed file that does not fit is left out, last listed first. A file the task cannot do without that does not fit makes the call fail with CONTEXT_TOO_LARGE. It returns a change set: operations (path; create, replace or delete; the full new content for create and replace), a one-line commit subject and notes.
- AC-AGT-22.2 Plain-code checks make a change set invalid when: it is empty; a path repeats; a path is absolute, contains `..` or is outside the allowed write area (configuration; by default only `runs/<run-id>/`); an operation touches a protected path; a replace or delete names a file that does not exist; a create names one that does; or content matches a configured secret pattern.
- AC-AGT-22.3 Protected paths are the test files that exist (only the QA role changes tests) and the paths listed in configuration.
- AC-AGT-22.4 An invalid change set is repaired like any invalid answer, and the refusal is recorded. A change set is never partly applied.
- AC-AGT-22.5 The stage has the same agent or dummy switch as AGT-12.

### AGT-23 Apply and commit a change set

Status: open

Depends on: AGT-22.

As the platform, I want the controller to apply a valid change set to the run's workspace and commit it with the agent as author, so that the history shows which agent did what for which task.

- AC-AGT-23.1 A valid change set is applied to the workspace all or nothing.
- AC-AGT-23.2 One commit is made on the run branch for each change set. The author is the role, and the message holds the agent's subject with the run id and the task id.
- AC-AGT-23.3 Applying the same change set again, as a resumed step does, makes no second commit. An operation id derived from the run, the task and the input artifact versions identifies it.
- AC-AGT-23.4 The commit id is recorded with the task and in the artifact lineage.
- AC-AGT-23.5 The controller runs nothing from the workspace in this story.

### AGT-24 Work through the tasks in dependency order

Status: open

Depends on: AGT-18, AGT-23.

As the platform, I want the development stage to work through the plan's tasks in dependency order, so that each task is done after the ones it depends on and the stage ends only when all are done.

- AC-AGT-24.1 Each task has a stored state: pending, ready, running, done or failed. A task is ready only when all the tasks it depends on are done.
- AC-AGT-24.2 The stage runs one ready task at a time in the plan's order. Development proposes, the controller applies and commits, and the task becomes done. Running tasks side by side is a later story.
- AC-AGT-24.3 A task that cannot be completed (the answer stays invalid after repair, the budget is used up, the model is unavailable) is marked failed. Tasks that depend on it stay pending and never run. The stage ends FAILED with the reason of the first failure.
- AC-AGT-24.4 After a restart or a resume, finished tasks are not run again and their commits stay single. The stage goes on with the rest.
- AC-AGT-24.5 The stage ends when every task is done, with a development artifact that lists the tasks, their commit ids and the head commit.
- AC-AGT-24.6 Task records, not the graph checkpoint, are the source of truth for progress. With the checkpoint discarded and the stage started again, finished tasks are not run again.

### AGT-37 Run independent tasks side by side

Status: open

Depends on: AGT-24, AGT-35.

As the platform owner, I want tasks that do not depend on each other and write different files to run at the same time, so that a larger plan finishes sooner on a model that can serve several calls.

- AC-AGT-37.1 Configuration sets how many tasks may run at once (default 1, which gives exactly the behaviour of AGT-24). Ready tasks run at the same time up to that limit, each in its own working copy of the run branch.
- AC-AGT-37.2 Two tasks that list the same file path never run at the same time.
- AC-AGT-37.3 The change sets of finished tasks are merged into the run branch in the plan's order, one commit each. A change that cannot be merged cleanly ends the stage with the reason ASSEMBLY_CONFLICT.
- AC-AGT-37.4 A failed task blocks only the tasks that depend on it. Independent tasks already running finish, and the stage ends FAILED when no more work can run.
- AC-AGT-37.5 Tasks running at once still obey the model profile's call limit (AC-AGT-3.5).

## Part E. Checks, review and repair (draft)

Shown when: the trial requirement ends with tests, a review, documentation, passing gates and an approved commit, and a failing check goes through repair.

### AGT-25 Write the tests as a change set

Status: open

Depends on: AGT-22, AGT-24.

As the platform, I want the QA agent to write the tests for every criterion, so that each criterion is proved by a test that names it.

- AC-AGT-25.1 The QA node, running beside review and documentation (AC-FLOW-1.4), reads the approved criteria and the files at the development head and returns a change set limited to the test area (configured test paths). The node does not apply it.
- AC-AGT-25.2 Each test names the criteria it proves by their ids, written with letters, digits and underscores in place of other characters.
- AC-AGT-25.3 Every criterion of the approved spec must appear in at least one proposed test, or the answer is invalid and goes to repair. A change set that touches a path outside the test area is invalid.
- AC-AGT-25.4 The QA role is the only one that adds or changes tests (AC-AGT-22.3).
- AC-AGT-25.5 The stage has the same agent or dummy switch as AGT-12.

### AGT-26 Review the work and report findings

Status: open

Depends on: AGT-24.

As a reviewer, I want a review of the code with each issue ranked and located, so that I can see what was found and how it was settled.

- AC-AGT-26.1 The review node reads the approved spec, the design and the files at the development head. It returns findings (id, severity blocking, should-fix or note, file, line, description, suggested fix) and a summary. It cannot change any file.
- AC-AGT-26.2 Plain-code reference check: a finding whose file does not exist at the reviewed commit, or whose line is outside the file, makes the answer invalid and goes to repair.
- AC-AGT-26.3 The review artifact names the commit it reviewed. The number of blocking findings is available to the gates.
- AC-AGT-26.4 The stage has the same agent or dummy switch as AGT-12.

### AGT-27 Document the work

Status: open

Depends on: AGT-24.

As a reader of the result, I want documentation that matches the code, so that I can run and understand what was built.

- AC-AGT-27.1 The documentation node reads the approved spec, the design and the files at the development head. It returns a change set limited to the documentation area (configured paths) and a list of the files and commands its text mentions. The node does not apply it.
- AC-AGT-27.2 Plain-code reference check: every file in the mentioned list exists at the development head or is created by the change set, or the answer is invalid and goes to repair.
- AC-AGT-27.3 The stage has the same agent or dummy switch as AGT-12.

### AGT-28 Assemble and freeze one candidate

Status: open

Depends on: AGT-25 to AGT-27.

As the platform, I want the reviewed work, the tests and the documentation combined into one frozen revision, so that every later check and approval applies to exactly the same commit.

- AC-AGT-28.1 After the parallel stages join, the controller applies the QA change set and then the documentation change set to the run branch, each all or nothing and each as one commit. Two change sets that write the same path end the run FAILED with the reason ASSEMBLY_CONFLICT, and nothing is merged silently.
- AC-AGT-28.2 The head commit after assembly is recorded as the candidate: an artifact holds its commit id and the artifact versions it contains.
- AC-AGT-28.3 Nothing changes the candidate. A result tied to another commit (a build, a test run, a review, an approval) is refused as stale.
- AC-AGT-28.4 Assembling twice, as a resumed step does, makes no second set of commits.

### AGT-29 Build and test the candidate through an execution profile

Status: open

Depends on: AGT-28.

As the platform, I want builds and tests to run through a profile that returns one common result, so that the gates read facts and not model opinions.

- AC-AGT-29.1 An execution profile is chosen by name per run in configuration. The trial profile builds and tests a small Python project. A profile turns its tool's output into one common result: the build outcome, the tests run, passed, failed and errored with the names of the failing tests, the line and branch coverage when the tool gives them, and a bounded excerpt of the raw report.
- AC-AGT-29.2 A missing, unreadable or timed-out result is NO_RESULT. The gates treat it as a failure and never as a pass.
- AC-AGT-29.3 The result is stored with the candidate's commit id. A fake runner stands in for the real one in tests.
- AC-AGT-29.4 The verification stage also runs the review role on the candidate, as a final review, beside the profile run, and ends only when both have finished.

### AGT-30 Decide with plain-code gates

Status: open

Depends on: AGT-29.

As the platform, I want the decision to accept, repair or stop to come from plain code that reads the evidence, so that no model ever decides that work is good enough.

- AC-AGT-30.1 The gate checks, in plain code, on the candidate's evidence: the build passed; all tests passed; coverage is at or above the configured threshold (default 100%); every criterion is proved by a passing test whose name contains the criterion's id; the final review has no blocking finding; no protected file changed; no file holds a secret pattern; and every path named by an artifact exists.
- AC-AGT-30.2 The result lists each check with pass or fail and its evidence. The decision is ACCEPT only when every check passes.
- AC-AGT-30.3 A check with no evidence fails. No model takes part in the decision.

### AGT-31 Repair within limits, then ask a person

Status: open

Depends on: AGT-30.

As a reviewer, I want failing checks to go back to the development agent a limited number of times and then to me, so that work is neither abandoned nor accepted by default.

- AC-AGT-31.1 When the decision is not ACCEPT and repair attempts remain (default 3 per run), the failing evidence goes back to the development role. Development proposes a change, the controller commits it, and the work is assembled, frozen and verified again. Each repair makes a new candidate and the earlier results become stale.
- AC-AGT-31.2 Repair attempts are counted separately from retries and count toward the run budget.
- AC-AGT-31.3 When the repair attempts are used up, the run waits (WAITING_FOR_INPUT). The reviewer sees the failing checks and the evidence of every attempt, and chooses to stop (the run is FAILED with the reason STOPPED_BY_REVIEWER) or to accept a coverage shortfall. A shortfall can be accepted only when every other check passes and coverage is at least 95%, and the reviewer supplies a reason for each uncovered item.
- AC-AGT-31.4 The choice is recorded with the approver's name. Accepting a shortfall does not change the configured threshold.

### AGT-32 Approve the merge of the exact commit

Status: open

Depends on: AGT-31.

As a reviewer, I want to approve one exact commit with its evidence in front of me, so that what I approve is what was checked.

- AC-AGT-32.1 When the gate accepts, or a shortfall was accepted, the run waits for merge approval. The request shows the candidate's commit id, a summary of the diff, the evidence, the review findings with how each was settled, and the coverage gaps with their reasons.
- AC-AGT-32.2 The approval names the commit id. It is refused if the candidate has changed.
- AC-AGT-32.3 Only after approval is the run SUCCEEDED. A rejection ends the run FAILED with the reason REJECTED and keeps the comment.
- AC-AGT-32.4 The controller publishes and merges nothing. The result is the run branch in the run's workspace. Getting it to the reviewer belongs to the reviewer interface.

### AGT-33 Run builds and tests in a sandbox container

Status: open

Depends on: AGT-29. Needs a design step and the user's approval of how the controller starts containers before it is coded. No Docker client library is added until then.

As the platform owner, I want builds and tests to run in a disposable container with no secrets, so that generated code never runs inside the controller.

- AC-AGT-33.1 Each verification runs in a new container that is removed afterwards.
- AC-AGT-33.2 The container runs as a non-root user with limits on CPU, memory, processes, disk and time. It has no host credentials, no access to the controller's database and no access to the container runtime socket.
- AC-AGT-33.3 The workspace is copied in and only the reports are copied out.
- AC-AGT-33.4 The build and test steps run without open network access. Dependencies arrive through a separate, controlled step or are already in the image.
- AC-AGT-33.5 A container that passes a limit is stopped and reported as NO_RESULT (AC-AGT-29.2).
- AC-AGT-33.6 Only the controller can start containers. This is a documented limitation.

### AGT-40 Show how every review finding was settled and that all changed code was reviewed

Status: open

Depends on: AGT-26, AGT-28, AGT-31.

As a reviewer, I want every changed file reviewed and every finding settled in the open, so that I can see that all submitted code was reviewed and how each issue ended.

- AC-AGT-40.1 The final review (AC-AGT-29.4) lists as reviewed every file that differs between the base and the candidate. The gate fails when a changed file is not listed.
- AC-AGT-40.2 Each finding of the first review gets a resolution in the final review: fixed (naming a later commit that changed the file), no longer applicable, accepted with the reviewer's reason, or open. A claimed fix with no later commit on that file is refused as invalid.
- AC-AGT-40.3 An open blocking finding fails the gate. Findings of other severities may stay open and are listed with their resolution.
- AC-AGT-40.4 The findings and their resolutions are written to the run's folder (AGT-21) for the candidate commit.

### AGT-41 Report unit and functional coverage and name every gap

Status: open

Depends on: AGT-25, AGT-29.

As a reviewer, I want the unit and functional coverage reports with every shortfall named and explained, so that I can see exactly what is and is not proved.

- AC-AGT-41.1 The unit coverage report that the profile produced is stored in the run's folder with the candidate's commit id.
- AC-AGT-41.2 A functional coverage report is generated by plain code: a table of every acceptance criterion with the tests that prove it and their results. When the candidate has an OpenAPI document, a second table lists every endpoint and status code with the tests that call it.
- AC-AGT-41.3 Every uncovered item reported (a line or branch, a criterion, an endpoint or a status code) needs a reason written by the QA role. An item without a reason fails the gate. A reason for an item that is not uncovered is ignored.
- AC-AGT-41.4 The reports state the real numbers. A named gap explains a shortfall and never turns it into a pass.

### AGT-42 Build and test Java projects through an execution profile

Status: open

Depends on: AGT-29, AGT-33.

As the platform owner, I want a Java profile that returns the same common result as the trial profile, so that the platform can build and judge a Java product.

- AC-AGT-42.1 The Java profile builds with the Maven wrapper, runs the unit and integration tests, reads the coverage report, and returns the common result of AC-AGT-29.1 with line and branch coverage.
- AC-AGT-42.2 Integration tests of the product use an embedded database, because the sandbox cannot start containers. The profile runs as a non-root user.
- AC-AGT-42.3 Dependencies are fetched in a separate, controlled step. The build and test steps run without network access (AC-AGT-33.4).
- AC-AGT-42.4 Fixture reports for a passing build, a compile error, a failing test and a coverage shortfall each give the right common result in tests.

### AGT-43 Stop a run safely and undo its changes

Status: open

Depends on: AGT-20, AGT-31.

As a reviewer, I want to stop a run at any time and have it end in a clean, known state, so that a run that cannot or should not continue never leaves loose ends.

- AC-AGT-43.1 A stop operation ends a run that is not finished as FAILED with the reason STOPPED_BY_REVIEWER. Stopping a finished run, or stopping a run twice, is refused with 409 in the RUN-4 format and changes nothing.
- AC-AGT-43.2 A run that cannot continue (budget, repair limit, stage time limit, failed recovery) ends the same way with its own reason code, and its work is stopped.
- AC-AGT-43.3 When a run ends this way its workspace and any container are removed. The run branch is kept as evidence unless configuration says to delete it. The main branch is never touched. The run records whether it was rolled back.
- AC-AGT-43.4 A stopped run keeps its artifacts, call records and decisions readable.

## Part F. Recovery, audit and metrics (draft outline)

Shown when: a run killed at any point is picked up without repeating work, every action is in a trail that shows tampering, and the reliability numbers can be read.

### AGT-38 Give every operation a stable identity and keep its result once

Status: open

Depends on: AGT-23, AGT-28.

As the platform owner, I want every operation that has an effect to have a stable identity and a stored result, so that an operation may run more than once while its effects apply once.

- AC-AGT-38.1 Every operation with an effect (a stage result, a change set applied, an artifact file written, a candidate frozen, a gate verdict, a sandbox run) has an id derived from the run, the stage or task, the kind of work and the input artifact versions. The same work always has the same id.
- AC-AGT-38.2 The result of an operation is stored under its id before the run advances. A second result for the same id is ignored and recorded as a duplicate.
- AC-AGT-38.3 Asking for an operation whose result is stored returns that result and does not repeat its effect.
- AC-AGT-38.4 The controller records which worker (thread or container) it started for which operation. A result reported under an id that the worker was not given is refused.

### AGT-39 Pick up runs left behind by a crash

Status: open

Depends on: AGT-35, AGT-38.

As a reviewer, I want a run that was running when the controller or a worker died to be picked up and finished, so that a crash costs time but not work. This is the recovery story that `run-execution.md` deferred.

- AC-AGT-39.1 A running run renews a lease while it executes. A run is stranded when it is RUNNING and its lease has expired (the length is configurable).
- AC-AGT-39.2 At startup, and then at a configured interval, the controller reconciles each stranded run: a stored result for its pending operation is used, a worker that is still alive is stopped, and otherwise the operation is issued again as an infrastructure retry.
- AC-AGT-39.3 After reconciliation the run resumes from its last checkpoint. An operation with a stored result is not repeated: no second model call, commit or artifact version.
- AC-AGT-39.4 A run waiting for input is never treated as stranded.
- AC-AGT-39.5 A run that cannot be reconciled within the retry limit ends FAILED with the reason RECOVERY_FAILED.
- AC-AGT-39.6 A test kills the process before a model call returns, after a change set is applied but before its result is stored, and after the result is stored. In each case the run ends with one commit and one artifact version.

### AGT-44 Keep an append-only audit trail

Status: open

Depends on: AGT-38.

As a reviewer, I want every action of a run recorded in a trail that shows tampering, so that I can trust the history of what the agents and people did.

- AC-AGT-44.1 Every node execution, gate decision, approval, retry, fallback, policy finding and change of run status is stored as an audit event: the run, the actor (an agent role, an approver name or the system), the type, ids and codes as details, and the time. Details never hold requirement, answer or model text.
- AC-AGT-44.2 The database role the application uses can add events but cannot change or delete them. A test using that role tries both and fails.
- AC-AGT-44.3 Each event stores the fingerprint of the one before it and its own fingerprint over its content and that fingerprint.
- AC-AGT-44.4 A verify command recomputes the chain and reports it intact, or reports the first event where it breaks. Changing a stored event in a test makes verification fail at that event.
- AC-AGT-44.5 The documentation says what this proves: the trail is tamper-evident, not tamper-proof.

### AGT-45 Anchor the audit chain with the approver

Status: open

Depends on: AGT-32, AGT-44.

As a reviewer, I want the trail's fingerprint recorded where the controller cannot rewrite it, so that later changes to the trail can be detected.

- AC-AGT-45.1 The merge approval request shows the current chain fingerprint.
- AC-AGT-45.2 The approver records that fingerprint outside the controller, in the protected main branch, in a commit signed with the approver's own key. The controller holds no credential that can write there.
- AC-AGT-45.3 The verify command accepts an anchored fingerprint and reports whether the chain up to that event matches it.
- AC-AGT-45.4 The documentation states the guarantee: changes after an anchor are detectable, and changes by a compromised controller before the next anchor are not.

### AGT-46 Measure how reliably the platform runs

Status: open

Depends on: AGT-44.

As the platform owner, I want reliability numbers computed from the trail, so that I can see how often runs succeed, retry, fall back, stop and recover, and how long they take.

- AC-AGT-46.1 From the audit events, plain code computes per run and across runs: the success rate; the retry frequency by stage; the stop and rollback frequency; the fallback frequency; the mean time to recovery (from a failed step to a healthy one); and the end-to-end latency, with the time spent waiting for people shown separately.
- AC-AGT-46.2 A metrics report is written into the run's folder, and the summary across runs can be read through a read operation of the platform.
- AC-AGT-46.3 The numbers come only from events. Adding or removing events in a test changes them as defined, and each definition is written in the report.

### AGT-47 Trace any change back to its requirement

Status: open

Depends on: AGT-11, AGT-15, AGT-23.

As a reviewer, I want to ask why a file exists and be told the task, the decision, the criterion, the requirement and the approvals behind it, so that every change can be justified.

- AC-AGT-47.1 For a file or a commit of the candidate, a lineage operation returns: the task that produced it, the design components and decisions behind that task, the acceptance criteria it serves, the requirement story they came from, and who approved each of those artifacts and when.
- AC-AGT-47.2 The answer is built only from stored ids, artifact lineage and decisions.
- AC-AGT-47.3 A file that no task of the run produced is reported as not produced by this run.

### AGT-48 Know who approves

Status: open

Depends on: AGT-15. Needs the user's go-ahead to add Spring Security (AGENTS.md).

As the platform owner, I want decisions to come from known people with the right permissions, so that an approval means something.

- AC-AGT-48.1 The reply, decision and stop operations need an authenticated identity. A request without one is refused with 401 in the RUN-4 format. The health endpoint and the API document stay open (AC-RUN-3.4).
- AC-AGT-48.2 Each identity has permissions for the decisions it may make (for example spec, design or merge). A decision outside them is refused with 403 in the RUN-4 format.
- AC-AGT-48.3 A decision records the authenticated identity, not a typed name.
- AC-AGT-48.4 No agent has an identity. Nothing an agent returns can approve, reply or stop.

## Part G. Change, brownfield and re-planning (draft outline)

Shown when: a change to existing code is analysed before design, and a requirement that changes mid-run triggers a re-plan with new approvals.

### AGT-50 Choose the run kind and name the existing code

Status: open

Depends on: AGT-20.

As a requester, I want to say that a run changes existing code and which code, so that the platform starts from the right base.

- AC-AGT-50.1 `POST /v1/runs` accepts an optional kind (GREENFIELD by default, or BROWNFIELD) and, for BROWNFIELD, the base to start from (a configured source and a revision). An invalid kind or base is refused with 400 in the RUN-4 format.
- AC-AGT-50.2 A request without a kind behaves as before.
- AC-AGT-50.3 The workspace of a brownfield run starts from the named base.
- AC-AGT-50.4 The API document and the read response show the kind. This updates AC-SWAG-1.3 and AC-SWAG-1.4 and the "request body unchanged" clause of AC-FLOW-1.6, with the user's approval noted in the steps log.

### AGT-49 Understand existing code before changing it

Status: open

Depends on: AGT-16, AGT-20, AGT-50.

As a reviewer, I want the impact of a change on existing code analysed and approved before any design, so that a brownfield change starts from what is really there.

- AC-AGT-49.1 For a brownfield run the impact node is given a read-only snapshot of the workspace (the file list with sizes and the content of selected files, sized by AGT-4) and returns an impact report: the modules, APIs and data flows affected with reasons, a change risk, and questions.
- AC-AGT-49.2 Reference check: every file, class or endpoint the report names exists in the snapshot, or the answer is invalid and goes to repair.
- AC-AGT-49.3 The report needs approval before design starts (AGT-15). A greenfield run does not run the node (AC-FLOW-1.2).
- AC-AGT-49.4 The snapshot is read-only and leaves out protected and secret paths.
- AC-AGT-49.5 The stage has the same agent or dummy switch as AGT-12.

### AGT-51 Change the requirement of a running run

Status: open

Depends on: AGT-11, AGT-15.

As a requester, I want to change the requirement of a run in progress, so that the work follows what I now want and nothing built on the old text is trusted.

- AC-AGT-51.1 A requester can submit a revised requirement for a run that is not finished. It becomes a new version of the requirement input, kept with a fingerprint.
- AC-AGT-51.2 Every artifact that consumed the old version, directly or through its lineage, and every approval of those artifacts, is marked stale.
- AC-AGT-51.3 The run restarts from the requirements stage with the revised text. The budget carries over and is not reset.
- AC-AGT-51.4 A revision for a finished run is refused with 409 in the RUN-4 format.

### AGT-52 Refuse stale results

Status: open

Depends on: AGT-36, AGT-51.

As the platform, I want results built on old versions refused, so that late or slow work can never overwrite newer decisions.

- AC-AGT-52.1 A result built from an artifact version that is now stale is refused, even if it arrives late, and the refusal is recorded.
- AC-AGT-52.2 Work in progress whose inputs became stale is cancelled (the mechanism of AGT-36) and its change sets are never applied.
- AC-AGT-52.3 A result tied to a commit that is not the current candidate is refused (AC-AGT-28.3).

### AGT-53 Re-plan after a change

Status: open

Depends on: AGT-24, AGT-52.

As a reviewer, I want the plan redone after a change, with the difference shown, so that finished work is kept, outdated work is replaced and the gates open again.

- AC-AGT-53.1 After the revised requirements and design are approved again, the planner runs again and a new plan version is stored. The difference from the old plan is shown: tasks added, removed and changed.
- AC-AGT-53.2 Done tasks whose inputs did not change are kept. Pending tasks are replaced by the new plan. Running tasks whose inputs changed are cancelled and their results refused. New tasks are scheduled by the usual rule.
- AC-AGT-53.3 Approvals tied to old versions no longer apply, and the gates concerned open again.
- AC-AGT-53.4 The run budget carries over, so a run cannot loop forever by re-planning.

## Part H. Scenarios, evaluation and delivery (draft outline)

Shown when: the three scenarios and the failure drill have been run and recorded, a recorded run can be replayed, and the controller starts with `docker compose up`.

### AGT-54 Run the greenfield scenario for the product

Status: open

Depends on: AGT-32, AGT-41, AGT-42, AGT-44, AGT-46.

As the platform owner, I want one complete run that builds the product from a written requirement, so that the platform is proved on the product it was made for.

- AC-AGT-54.1 `scenarios/greenfield.md` states a request to build a link-shortening service with link creation, redirects and click statistics. It pins, as decisions that must appear as numbered criteria in the approved spec: where data is kept, the code format, collision handling, concurrent creation, idempotency, URL validation, the redirect status, unknown and expired codes, atomic click counting, the error format and audit content.
- AC-AGT-54.2 A run of the scenario ends SUCCEEDED with the product under `apps/url-shortener/` and the evidence under `runs/<run-id>/`: requirements, design, plan, review, QA (unit and functional coverage with gaps), audit and metrics.
- AC-AGT-54.3 The product builds and its tests pass in the sandbox through the Java profile, and its coverage and gaps are reported as in AGT-41.
- AC-AGT-54.4 The commit history has one commit per task, with the agent as author and the run and task in the message.
- AC-AGT-54.5 Each approval in the run was made with the evidence in front of the approver (AGT-15, AGT-32).

### AGT-55 Run the brownfield scenario

Status: open

Depends on: AGT-49, AGT-50, AGT-53, AGT-54.

As the platform owner, I want a run that changes the existing product, so that brownfield work, impact analysis and re-planning are shown on a real change.

- AC-AGT-55.1 `scenarios/brownfield.md` asks to add an expiry to the links of the product that the greenfield run built.
- AC-AGT-55.2 The run includes the impact report and its approval, then design, plan and code, and ends SUCCEEDED.
- AC-AGT-55.3 A requirement change made while the run is in progress triggers detection, refusal of stale results, a re-plan with its difference shown, and new approvals, and the run still ends SUCCEEDED.
- AC-AGT-55.4 The approved criteria state the expiry boundary exactly: a link works strictly before the instant and is expired at or after it, in UTC. The product's tests prove it with a clock they can set.

### AGT-56 Run the ambiguous scenario

Status: open

Depends on: AGT-6, AGT-15, AGT-54.

As the platform owner, I want a run that starts from a vague requirement, so that clarification, narrowing the scope and the model fallback are shown.

- AC-AGT-56.1 `scenarios/ambiguous.md` holds a deliberately vague request about making the product more dependable.
- AC-AGT-56.2 The run produces questions and assumptions before anything else, the reviewer narrows the scope at the spec approval, and the run ends SUCCEEDED on the narrowed scope.
- AC-AGT-56.3 The recording of this run includes a failed primary-model call followed by the fallback model's answer, and the call records show both.

### AGT-57 Run the failure drill

Status: open

Depends on: AGT-31, AGT-43, AGT-54.

As the platform owner, I want a run that cannot succeed to end safely, so that the repair limit, the human choice, the budget and the rollback are shown.

- AC-AGT-57.1 `scenarios/failure-drill.md` holds a requirement with an acceptance criterion that cannot be met.
- AC-AGT-57.2 The repair attempts run out, the run waits for the reviewer, and the reviewer chooses to stop. The run ends FAILED with the reason STOPPED_BY_REVIEWER.
- AC-AGT-57.3 The workspace is removed, the main branch is untouched, the budget was respected, and the stop is counted in the rollback metric.

### AGT-58 Replay a recorded run with everything else real

Status: open

Depends on: AGT-10, AGT-54 to AGT-57.

As a reviewer without a model, I want to replay a recorded scenario with everything but the model real, so that I can check the platform's behaviour myself.

- AC-AGT-58.1 Each scenario has a recording under `recordings/`. Replay of a scenario completes without contacting any model.
- AC-AGT-58.2 In replay, change sets are applied, builds run, tests run and coverage is measured for real. A recording whose answer holds a patch that breaks a test fails its gate in replay.
- AC-AGT-58.3 A replay regression check replays a recording after a platform change and fails when the controller's decisions differ from the recorded ones.

### AGT-59 Evaluate the agents against known cases

Status: open

Depends on: AGT-10, AGT-26.

As the platform owner, I want the agents tested against cases with known answers, so that their behaviour is measured and not assumed.

- AC-AGT-59.1 Evaluation cases are data: patches with planted defects for the review role, vague requirements that must produce questions, changes with a known set of affected modules, and answers that cite files that do not exist.
- AC-AGT-59.2 An evaluation run calls the configured model on demand, never in `./mvnw verify`, and records the model, the prompt version, the inputs, the outputs and the result of each case.
- AC-AGT-59.3 Plain code scores each case (for example, a planted defect counts as found when a finding names its file and line). No model scores another model.
- AC-AGT-59.4 The committed evaluation report is labelled as a recorded result for that model and prompt version.

### AGT-60 Start the controller with `docker compose up`

Status: open

Depends on: AGT-10.

As a reviewer, I want one command to start the controller and its database, so that I need only Docker to try the platform.

- AC-AGT-60.1 The Compose file starts PostgreSQL and the controller from the repository, with settings from environment variables and no secret in a committed file.
- AC-AGT-60.2 The controller reports healthy at `/actuator/health`, and a run can be created against it.
- AC-AGT-60.3 The model mode is replay unless a live model is configured. The local model server is reached at a configured address or through an optional Compose profile.
- AC-AGT-60.4 From a fresh clone, following only the README, a reviewer starts the stack and replays a scenario. The check is made by hand and its result is recorded in the steps log.

### AGT-61 Run the checks in CI

Status: open

Depends on: none.

As the platform owner, I want every change checked automatically, so that tests and coverage cannot regress unnoticed.

- AC-AGT-61.1 A workflow runs `./mvnw verify` on every change, with Docker available for the integration tests, and fails when a test fails or coverage is below the gate.
- AC-AGT-61.2 The unit coverage report is kept as a build artifact.

## Open questions for the design step

- How the local model client talks to the server: a plain HTTP client or a Spring AI abstraction, and the exact request fields for the window, the answer limit and a JSON-only answer. A hosted model may use the Spring AI starter that is already in the build.
- The role definition file format, and where the files live.
- How the pause is built in the graph: an interrupt before an input node, resume with the answers as new state, and which component resumes a run (the scheduler is assumed).
- Where artifacts, questions, decisions, tasks and call records are stored, and who owns the checkpoint tables.
- Where the workspace source comes from when the controller runs in a container, and whether Ollama runs on the host or in the Compose file. The reviewer needs only Docker, so replay is the default for them.
- How the controller starts sandbox containers, and the image and dependency plan for the Python trial profile.
- How the default of one call at a time on a local model interacts with the three parallel branches and their timeouts.
- Whether a safe stop is its own run status or FAILED with a reason (assumed: FAILED with a reason and a rolled-back flag).
- How a lease is stored and renewed for crash reconciliation, and how the application's database role is split from the migration role for the audit trail.
- How the product's integration tests use an embedded database inside the sandbox, and which larger model the product scenarios use (the 32,768-token local model is the trial only).

## Outside this file

Still required by the project, but belonging to other files or documents:

- **Reviewer interface:** streaming progress and tokens, a command-line client, and downloading the result. It gets its own requirements file. Publishing a branch or opening a pull request after merge approval is decided there.
- **Written documents:** the architecture overview, risks and limitations, setup instructions, decision records, the AI usage notes and the final engineering summary. These are written by hand with the agents' help and are not stories.
- **BUG-1 in `run-api.md`:** an oversized request body must be refused before Spring reads all of it.
- **Expiry of runs that wait too long** for a person.

Stretch, only after everything above is complete: retrieval over standards and code, tool calling and an MCP connection to an outside tool, a second execution profile, and a run on an unrelated requirement.

Not planned: training or fine-tuning models, a front end, and a real cloud deployment.
