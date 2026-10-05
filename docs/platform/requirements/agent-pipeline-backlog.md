# Agent pipeline backlog

This file holds the stories of the agent pipeline that are not part of what the platform delivers. They were written before the code, and they keep their original ids and their text exactly; ids were not renumbered. **Nothing in this file is claimed as built.** Where partial code exists, the table of the group says so. What was built, with its tests, is in [agent-pipeline.md](agent-pipeline.md), which holds 29 stories. This file holds the other 36, in three groups by reason: 9 deferred, 12 replaced by a different design and 15 not built.

Statuses keep the meaning of [ADR 0012](../../adr/0012-reconcile-platform-backlog-statuses.md): `deferred` is postponed work, `stale` is a story whose criteria were not reconciled with the implementation and the scope, and AGT-20 is `done` because AGT-67 superseded it. The criteria of the stories here are not accepted as met. Part letters inside the stories (for example "Part D") refer to the original eight-part layout of the file, not to the five parts of agent-pipeline.md.

## Deferred

Postponed by a user decision (ADR 0012); the table says what exists today in place of each.

| Story | What exists today |
| --- | --- |
| AGT-13 Pause the run when an agent has questions | The requirements stage pauses and takes one batch of answers (AGT-64). Questions from other roles and several rounds are postponed. |
| AGT-14 Answer the questions and resume | The batch reply of AGT-64 for the requirements stage. Replies and resuming for every role are postponed. |
| AGT-19 Take a requirement to an approved plan on a scripted model | No separate test. The scripted end-to-end tests (AC-AGT-62.15, and the approval tests of AGT-15) cover parts of it. |
| AGT-25 Write the tests as a change set | The development agent writes the code and its tests in one change set. There is no QA-only test change set. |
| AGT-35 Run several runs side by side within a limit | The scheduler takes one run at a time. |
| AGT-36 Stop a stage that runs too long | Model calls and sandbox builds have their own time limits (`call-timeout`, `sandbox.verify.timeout`); there is no limit per stage. |
| AGT-37 Run independent tasks side by side | Development makes one change set per attempt. Only QA, review and documentation run side by side. |
| AGT-43 Stop a run at any point and resume it from where it stopped | Partial code and tests exist: the STOPPED status, `POST /v1/runs/{id}/stop` and `/resume`, migration V16 and the page buttons ([ADR 0011](../../adr/0011-stop-cooperatively-and-retain-work-for-resume.md)). The acceptance of its criteria was not verified, so the story stays here. |
| AGT-48 Know who approves | Approvals record a free-text approver name. There are no keys, identities or permissions. |

### AGT-13 Pause the run when an agent has questions

Status: deferred

Disposition (2026-10-05): Clarification from every role and multiple rounds are postponed. Requirements-stage clarification remains implemented under AGT-64.

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

Status: deferred

Disposition (2026-10-05): Generalised replies and continuation for every role are postponed. The requirements-stage batch reply under AGT-64 remains in place.

Depends on: AGT-13.

As a requester, I want to answer the agent's questions and have the run carry on, so that the stage can use my answers.

- AC-AGT-14.1 A reply operation accepts, for a waiting run, one answer per open question, or the instruction to take that question's proposed default. An answer to an unknown question, a missing answer for a question with no proposed default, or an answer longer than 5,000 characters is refused with 400 in the RUN-4 format, and the run is unchanged.
- AC-AGT-14.2 A reply for a run that is not waiting for answers is refused with 409 in the RUN-4 format and changes nothing. A second reply to the same questions is refused the same way.
- AC-AGT-14.3 After an accepted reply the run continues without any manual dispatch call. The scheduler resumes it, and the same role is called again with the original requirement, all questions and all answers so far. The result is the next artifact version.
- AC-AGT-14.4 Resuming never repeats earlier work. The call records of earlier nodes are unchanged and no earlier node runs again.
- AC-AGT-14.5 Questions and answers are stored together with the time and the artifact version they belong to.

### AGT-19 Take a requirement to an approved plan on a scripted model

Status: deferred

Disposition (2026-10-05): The separate requirements-to-plan proof is postponed for consolidation into the main end-to-end acceptance tests. Its full historical criteria are not claimed as verified.

Depends on: AGT-14 to AGT-18.

As the platform owner, I want one end-to-end proof of the text pipeline, so that the pause, the answers, the approvals and the hand-over between stages are shown to work together.

- AC-AGT-19.1 With a scripted model, the agent enabled for requirements, design and planning, and the later stages on their dummies, a vague requirement goes through: questions, a reply, a second requirements version, approval, design, approval, plan, and then ends SUCCEEDED.
- AC-AGT-19.2 The run's artifacts are the approved requirements version, the approved design version and the plan, each recording the versions it consumed, and the chain can be followed from the plan back to the original requirement.
- AC-AGT-19.3 The call records show exactly the calls that were scripted. No call is repeated because of a pause or a resume.
- AC-AGT-19.4 No log line of the run contains requirement text, answers or scripted model text.
- AC-AGT-19.5 A restart while the run waits for answers, for the spec approval and for the design approval resumes each wait at the same point.
- AC-AGT-19.6 A failure at any stage ends the run FAILED with its reason and leaves the earlier artifacts readable.

### AGT-25 Write the tests as a change set

Status: deferred

Disposition (2026-10-05): The separate QA-only test-writing mechanism and exclusive test ownership are postponed. Development continues to generate code and tests together; QA assessment and coverage evidence are still required.

Depends on: AGT-22, AGT-24.

As the platform, I want the QA agent to write the tests for every criterion, so that each criterion is proved by a test that names it.

- AC-AGT-25.1 The QA node, running beside review and documentation (AC-FLOW-1.4), reads the approved criteria and the files at the development head and returns a change set limited to the test area (configured test paths). The node does not apply it.
- AC-AGT-25.2 Each test names the criteria it proves by their ids, written with letters, digits and underscores in place of other characters.
- AC-AGT-25.3 Every criterion of the approved spec must appear in at least one proposed test, or the answer is invalid and goes to repair. A change set that touches a path outside the test area is invalid.
- AC-AGT-25.4 The QA role is the only one that adds or changes tests (AC-AGT-22.3).
- AC-AGT-25.5 The stage has the same agent or dummy switch as AGT-12.

### AGT-35 Run several runs side by side within a limit

Status: deferred

Disposition (2026-10-05): Concurrent runs are postponed; the current scheduler executes runs sequentially.

Depends on: AGT-14.

As the platform owner, I want runs started on a bounded set of threads, so that one long run no longer holds up other runs or the resuming of runs whose questions were answered.

- AC-AGT-35.1 Configuration sets the maximum number of runs executing at once (default 2).
- AC-AGT-35.2 The scheduler hands a claimed or resumed run to a bounded pool and does not wait for it. With a free place it takes the next run at its next poll.
- AC-AGT-35.3 When every place is busy, further runs stay CREATED, or stay waiting to resume, and are taken as places free up. No run is started twice (AC-EXEC-4.3).
- AC-AGT-35.4 A run that waits for input holds no place.
- AC-AGT-35.5 A failure in one run does not stop other runs or the polling.
- AC-AGT-35.6 On shutdown the application stops taking runs and gives running ones a configurable time to finish. A run that does not finish stays RUNNING and is ended by AGT-39 at the next start.

### AGT-36 Stop a stage that runs too long

Status: deferred

Disposition (2026-10-05): The additional stage-wide timer and cancellation mechanism are postponed. Existing model-call and sandbox timeouts and bounded retries remain in place.

Depends on: AGT-5.

As the platform owner, I want a time limit on every stage, so that a hung model call, branch or task cannot hold a run forever.

- AC-AGT-36.1 Configuration sets a wall-clock limit per role (default 30 minutes). The limit counts only the time a stage is running. Time spent waiting for a person counts toward nothing.
- AC-AGT-36.2 When a stage passes its limit, its work is interrupted: model calls in progress are abandoned, sibling branches are asked to stop, and the run ends FAILED with the reason STAGE_TIMEOUT.
- AC-AGT-36.3 An interrupted stage leaves no partly applied change, because work that changes files is applied all or nothing (AC-AGT-23.1). This is checked from Part D on. The interruption is recorded.
- AC-AGT-36.4 A stage that finishes within its limit is unaffected, and one stage passing its limit does not stop other runs.

### AGT-37 Run independent tasks side by side

Status: deferred

Disposition (2026-10-05): Concurrent development tasks are postponed. Parallel QA, review and documentation remain in place.

Depends on: AGT-24, AGT-35.

As the platform owner, I want tasks that do not depend on each other and write different files to run at the same time, so that a larger plan finishes sooner on a model that can serve several calls.

- AC-AGT-37.1 Configuration sets how many tasks may run at once (default 1, which gives exactly the behaviour of AGT-24). Ready tasks run at the same time up to that limit, each in its own working copy of the run branch.
- AC-AGT-37.2 Two tasks that list the same file path never run at the same time.
- AC-AGT-37.3 The change sets of finished tasks are merged into the run branch in the plan's order, one commit each. A change that cannot be merged cleanly ends the stage with the reason ASSEMBLY_CONFLICT.
- AC-AGT-37.4 A failed task blocks only the tasks that depend on it. Independent tasks already running finish, and the stage ends FAILED when no more work can run.
- AC-AGT-37.5 Tasks running at once still obey the model profile's call limit (AC-AGT-3.5).

### AGT-43 Stop a run at any point and resume it from where it stopped

Status: deferred

Disposition (2026-10-05): Further Stop/Resume work and acceptance verification are postponed. Partial uncommitted backend code, migration, tests and existing UI controls are retained; this status does not claim they pass acceptance checks or remove safe termination.

Depends on: AGT-15, AGT-66.

As a reviewer, I want to stop a run whenever I like and resume it later from where it stopped, so that I stay in control of what the agents do and a stop costs no work.

- AC-AGT-43.1 `POST /v1/runs/{id}/stop` on a CREATED, RUNNING or WAITING_FOR_INPUT run answers 202. A CREATED or waiting run becomes STOPPED at once. A RUNNING run is asked to stop and becomes STOPPED at the next safe point: before the next model call, the next stage attempt or the next build, between stages, and a build container that is running is stopped; a model call already in flight may finish and its result is stored. A SUCCEEDED, FAILED or already STOPPED run is refused with 409 in the RUN-4 format, an unknown run with 404, and nothing changes on a refusal.
- AC-AGT-43.2 STOPPED is a run status shown in the read response and the API document. A stopped run is not claimed by the scheduler and is not a failure: it has no failure reason and is left out of the success rate. Its artifacts, call records, decisions, branch and workspace are kept.
- AC-AGT-43.3 The stage that was running when the run stopped is shown as NOT_STARTED again, and stages that finished stay FINISHED.
- AC-AGT-43.4 `POST /v1/runs/{id}/resume` on a STOPPED run answers 202 and queues it. It continues at the earliest stage that is not FINISHED with the state rebuilt from what is stored, as the first retry does (AC-AGT-66.2). A resume does not use up the run's attempts, and if the run later fails the retry rules apply as before. A run that is not STOPPED is refused with 409, an unknown run with 404.
- AC-AGT-43.5 A run that was stopped while waiting for answers or for an approval resumes into the same wait, with the same questions or the same approval request, and does not go past it.
- AC-AGT-43.6 The metrics report counts stopped runs separately. The API document lists both operations and the new status (this updates AC-SWAG-1.4 with the user's approval, noted in the steps log). The reviewer page has the Stop and Resume buttons (UI-3).

### AGT-48 Know who approves

Status: deferred

Disposition (2026-10-05): Authenticated approval identities and API permissions are postponed for the local prototype. Named, recorded approvals remain in place.

Depends on: AGT-15. A small request filter is assumed; Spring Security is added only with the user's go-ahead (AGENTS.md).

As the platform owner, I want decisions to come from known people with the right permissions, so that an approval means something. The set of people is a small configured list; there is no registration or account management.

- AC-AGT-48.1 Identities are configured: a name, a key read from the environment, and the decisions the identity may make (for example spec, design or merge). The reply, decision and stop operations need a key. A request without a known key is refused with 401 in the RUN-4 format. The health endpoint and the API document stay open (AC-RUN-3.4).
- AC-AGT-48.2 A decision outside the identity's permissions is refused with 403 in the RUN-4 format.
- AC-AGT-48.3 A decision records the identity's name, not a typed name.
- AC-AGT-48.4 No agent has an identity. Nothing an agent returns can approve, reply or stop.

## Replaced by a different design

The behaviour was built in another form under a story that is delivered, or under a decision record; the table names what replaced each story, and the criteria below describe the design that was not built.

| Story | Replaced by |
| --- | --- |
| AGT-17 Design from the approved spec | AC-AGT-62.2 and 62.3 (one generic agent stage serves the design role) and AGT-15 (approval of the design). The plain-code design checks of AC-AGT-17.2 are not built. |
| AGT-18 Plan the work as a checked task graph | The same generic stage for the planning role and AGT-15 (approval of the plan). The checks of AC-AGT-18.2 to 18.4 (cycles, coverage, size) are not built, so tasks do not schedule the development stage. |
| AGT-20 Give each run its own workspace and branch | AGT-67 and [ADR 0010](../../adr/0010-work-in-a-git-worktree-of-this-repository.md): a git worktree of this repository on the branch `run/<run-id>`, not a clone. |
| AGT-22 Let the development agent propose file changes | AC-AGT-62.6 and AGT-69: one change set of whole files and deletions per development attempt, with the path rules of `ChangeSetCheck`. Per-task change sets, protected paths and secret patterns are not built. |
| AGT-23 Apply and commit a change set | AC-AGT-67.1 to 67.3: the controller writes the files and commits them with the development role as author. There is no operation id and no commit per task. |
| AGT-26 Review the work and report findings | The generic stage for the review role, and AGT-21, which writes `docs/review.md`. The reference check of AC-AGT-26.2 is not built. |
| AGT-27 Document the work | The generic stage for the documentation role, and AGT-21, which writes `README.md` from its artifact. The reference check of AC-AGT-27.2 is not built. |
| AGT-29 Build and test the candidate through an execution profile | AC-AGT-62.8 to 62.10 and [ADR 0005](../../adr/0005-run-builds-and-tests-in-one-disposable-container.md): one command and image set in `sandbox.verify.*`, and a JUnit XML report read by plain code. There is no profile abstraction, no coverage figure and no final review beside the build. |
| AGT-30 Decide with plain-code gates | AC-AGT-62.10 and AC-AGT-65.6: a run succeeds only when the build and every test pass, and a report with no passing test is not a pass. The other checks (coverage, criteria proved, review findings, protected files, secrets) are not built. |
| AGT-31 Repair within limits, then ask a person | AGT-65 (bounded stage attempts, fix attempts after a failed build) and AGT-66 (retry of a failed run). There is no coverage-shortfall choice and no wait for a reviewer when the repairs are used up. |
| AGT-32 Approve the merge of the exact commit | AGT-68 (automatic delivery after verification). The story's own scope update says there is no separate final approval. |
| AGT-33 Run builds and tests in a sandbox container | AC-AGT-62.8 and 62.9 and [ADR 0005](../../adr/0005-run-builds-and-tests-in-one-disposable-container.md): a labelled, non-root container with limits and no network, started with the `docker` command line behind a port; the workspace goes in by copy and only the report comes out. A disk limit and a read-only file system are not set. |

### AGT-17 Design from the approved spec

Status: stale

Depends on: AGT-12, AGT-15, AGT-16.

As a reviewer, I want a design document with diagrams made from the approved stories, so that I can approve how it will be built before any code exists.

- AC-AGT-17.1 The design node reads the approved requirements artifact and stores a design artifact. It holds: an overview; components and their responsibilities; the data held; the interfaces; key decisions, each with the alternatives considered and the reason; risks; a mapping from every acceptance criterion to the components that satisfy it; diagram sources; open questions; and a summary.
- AC-AGT-17.2 Plain-code checks make an answer invalid when a criterion of the approved spec is missing from the mapping, the mapping names a criterion that does not exist, a decision has no reason, or a diagram source is blank or does not start with a Mermaid diagram keyword from a fixed list.
- AC-AGT-17.3 The design stage can ask questions (AGT-13, AGT-14), and its result needs approval (AGT-15) before planning starts.
- AC-AGT-17.4 The stage has the same agent or dummy switch as AGT-12.

### AGT-18 Plan the work as a checked task graph

Status: stale

Depends on: AGT-17.

As the platform, I want the planner to break the approved design into tasks with dependencies, checked by plain code, so that work is ordered and every criterion is covered.

- AC-AGT-18.1 The planning node reads the approved requirements and design and stores a plan artifact. It holds: tasks (id, title, description, the tasks it depends on, the criteria it serves, the file paths it expects to write, and an estimated output size in tokens) and a summary.
- AC-AGT-18.2 Plain-code checks make a plan invalid when a task id repeats, a dependency names an unknown task, the dependencies contain a cycle, a task serves no criterion or names an unknown one, a criterion of the approved spec is served by no task, or a file path is absolute, contains `..` or is outside the allowed write area.
- AC-AGT-18.3 The planner is told the development role's answer limit. A task whose estimated output size does not fit in that limit minus the safety margin makes the plan invalid, so a small model gets small tasks and a large model gets larger ones.
- AC-AGT-18.4 The plan has a deterministic order: tasks whose dependencies are done are ordered by task id, and the plan shows that order.
- AC-AGT-18.5 The planning stage can ask questions and has the same agent or dummy switch as AGT-12.

### AGT-20 Give each run its own workspace and branch

Status: done

Disposition (2026-10-05): Closed as superseded by AGT-67 and ADR 0010. The implemented worktree arrangement replaces the clone-based workspace and deletion criteria; those historical criteria are retained below, not scheduled for implementation.

Depends on: AGT-19.

As the platform, I want every run to work in its own copy of the repository on its own branch, so that generated work never touches the controller's checkout or another run.

- AC-AGT-20.1 When a run reaches its first writing stage, a workspace is created: a fresh clone of the configured source at a path derived from the run id under a configured base directory, on a new branch named `run/<run-id>`.
- AC-AGT-20.2 Creating the workspace again for the same run reuses it and does not clone again.
- AC-AGT-20.3 Nothing is written outside the base directory. The controller's own checkout is never changed.
- AC-AGT-20.4 Commits are made only on the run branch, never on the main branch.
- AC-AGT-20.5 A run's workspace can be removed when the run ends. Removal deletes only that run's folder.

### AGT-22 Let the development agent propose file changes

Status: stale

Depends on: AGT-18, AGT-20.

As the platform, I want the development agent to return file changes for one task as structured data, so that the controller, not the agent, decides whether and how they are applied.

- AC-AGT-22.1 For one task, the development node is given the task, the criteria it serves, the relevant part of the design, the summaries of the tasks it depends on, and the current content of the files the task lists, sized by AGT-4. A listed file that does not fit is left out, last listed first. A file the task cannot do without that does not fit makes the call fail with CONTEXT_TOO_LARGE. It returns a change set: operations (path; create, replace or delete; the full new content for create and replace), a one-line commit subject and notes.
- AC-AGT-22.2 Plain-code checks make a change set invalid when: it is empty; a path repeats; a path is absolute, contains `..` or is outside the allowed write area (configuration; by default only `runs/<run-id>/`); an operation touches a protected path; a replace or delete names a file that does not exist; a create names one that does; or content matches a configured secret pattern.
- AC-AGT-22.3 Protected paths are the test files that exist (only the QA role changes tests) and the paths listed in configuration.
- AC-AGT-22.4 An invalid change set is repaired like any invalid answer, and the refusal is recorded. A change set is never partly applied.
- AC-AGT-22.5 The stage has the same agent or dummy switch as AGT-12.

### AGT-23 Apply and commit a change set

Status: stale

Depends on: AGT-22.

As the platform, I want the controller to apply a valid change set to the run's workspace and commit it with the agent as author, so that the history shows which agent did what for which task.

- AC-AGT-23.1 A valid change set is applied to the workspace all or nothing.
- AC-AGT-23.2 One commit is made on the run branch for each change set. The author is the role, and the message holds the agent's subject with the run id and the task id.
- AC-AGT-23.3 Applying the same change set again, as a resumed step does, makes no second commit. An operation id derived from the run, the task and the input artifact versions identifies it.
- AC-AGT-23.4 The commit id is recorded with the task and in the artifact lineage.
- AC-AGT-23.5 The controller runs nothing from the workspace in this story.

### AGT-26 Review the work and report findings

Status: stale

Depends on: AGT-24.

As a reviewer, I want a review of the code with each issue ranked and located, so that I can see what was found and how it was settled.

- AC-AGT-26.1 The review node reads the approved spec, the design and the files at the development head. It returns findings (id, severity blocking, should-fix or note, file, line, description, suggested fix) and a summary. It cannot change any file.
- AC-AGT-26.2 Plain-code reference check: a finding whose file does not exist at the reviewed commit, or whose line is outside the file, makes the answer invalid and goes to repair.
- AC-AGT-26.3 The review artifact names the commit it reviewed. The number of blocking findings is available to the gates.
- AC-AGT-26.4 The stage has the same agent or dummy switch as AGT-12.

### AGT-27 Document the work

Status: stale

Depends on: AGT-24.

As a reader of the result, I want documentation that matches the code, so that I can run and understand what was built.

- AC-AGT-27.1 The documentation node reads the approved spec, the design and the files at the development head. It returns a change set limited to the documentation area (configured paths) and a list of the files and commands its text mentions. The node does not apply it.
- AC-AGT-27.2 Plain-code reference check: every file in the mentioned list exists at the development head or is created by the change set, or the answer is invalid and goes to repair.
- AC-AGT-27.3 The stage has the same agent or dummy switch as AGT-12.

### AGT-29 Build and test the candidate through an execution profile

Status: stale

Depends on: AGT-28.

As the platform, I want builds and tests to run through a profile that returns one common result, so that the gates read facts and not model opinions.

- AC-AGT-29.1 An execution profile is chosen by name per run in configuration. The first profile builds and tests a Python project. A profile turns its tool's output into one common result: the build outcome, the tests run, passed, failed and errored with the names of the failing tests, the line and branch coverage when the tool gives them, and a bounded excerpt of the raw report.
- AC-AGT-29.2 A missing, unreadable or timed-out result is NO_RESULT. The gates treat it as a failure and never as a pass.
- AC-AGT-29.3 The result is stored with the candidate's commit id. A fake runner stands in for the real one in tests.
- AC-AGT-29.4 The verification stage also runs the review role on the candidate, as a final review, beside the profile run, and ends only when both have finished.

### AGT-30 Decide with plain-code gates

Status: stale

Depends on: AGT-29.

As the platform, I want the decision to accept, repair or stop to come from plain code that reads the evidence, so that no model ever decides that work is good enough.

- AC-AGT-30.1 The gate checks, in plain code, on the candidate's evidence: the build passed; all tests passed; coverage is at or above the configured threshold (default 100%); every criterion is proved by a passing test whose name contains the criterion's id; the final review has no blocking finding; no protected file changed; no file holds a secret pattern; and every path named by an artifact exists.
- AC-AGT-30.2 The result lists each check with pass or fail and its evidence. The decision is ACCEPT only when every check passes.
- AC-AGT-30.3 A check with no evidence fails. No model takes part in the decision.

### AGT-31 Repair within limits, then ask a person

Status: stale

Depends on: AGT-30.

As a reviewer, I want failing checks to go back to the development agent a limited number of times and then to me, so that work is neither abandoned nor accepted by default.

- AC-AGT-31.1 When the decision is not ACCEPT and repair attempts remain (default 3 per run), the failing evidence goes back to the development role. Development proposes a change, the controller commits it, and the work is assembled, frozen and verified again. Each repair makes a new candidate and the earlier results become stale.
- AC-AGT-31.2 Repair attempts are counted separately from retries and count toward the run budget.
- AC-AGT-31.3 When the repair attempts are used up, the run waits (WAITING_FOR_INPUT). The reviewer sees the failing checks and the evidence of every attempt, and chooses to stop (the run is FAILED with the reason STOPPED_BY_REVIEWER) or to accept a coverage shortfall. A shortfall can be accepted only when every other check passes and coverage is at least 95%, and the reviewer supplies a reason for each uncovered item.
- AC-AGT-31.4 The choice is recorded with the approver's name. Accepting a shortfall does not change the configured threshold.

### AGT-32 Approve the merge of the exact commit

Scope update (user decision, 2026-10-05): AGT-68 enables automatic delivery after the existing pipeline checks and design/plan approvals, without a separate final approval. The historical final-approval criteria below are not requirements of the current automatic delivery policy; a future final-approval feature would require a separate decision.

Status: stale

Depends on: AGT-31.

As a reviewer, I want to approve one exact commit with its evidence in front of me, so that what I approve is what was checked.

- AC-AGT-32.1 When the gate accepts, or a shortfall was accepted, the run waits for merge approval. The request shows the candidate's commit id, a summary of the diff, the evidence, the review findings with how each was settled, and the coverage gaps with their reasons.
- AC-AGT-32.2 The approval names the commit id. It is refused if the candidate has changed.
- AC-AGT-32.3 Only after approval is the run SUCCEEDED. A rejection ends the run FAILED with the reason REJECTED and keeps the comment.
- AC-AGT-32.4 The controller publishes and merges nothing. The result is the run branch in the run's workspace. Getting it to the reviewer belongs to the reviewer interface.

### AGT-33 Run builds and tests in a sandbox container

Status: stale

Depends on: AGT-29. The user approved (2026-10-04) that the controller starts containers by calling the `docker` command line behind a small port, with no Docker client library. The design step names the image and its contents.

As the platform owner, I want builds and tests to run in a disposable container with no secrets, so that generated code never runs inside the controller.

- AC-AGT-33.1 Each verification runs in a new container that is removed afterwards. The controller starts it through a port that calls the `docker` command line, and a fake stands in for the port in tests (AC-AGT-29.3). The container carries a label that names the platform (AC-AGT-39.3).
- AC-AGT-33.2 The container runs as a non-root user with limits on CPU, memory, processes, disk and time. It has no host credentials, no access to the controller's database and no access to the container runtime socket.
- AC-AGT-33.3 The workspace is copied in and only the reports are copied out. No folder of the host is mounted into the container.
- AC-AGT-33.4 The build and test steps run without open network access. Dependencies arrive through a separate, controlled step or are already in the image.
- AC-AGT-33.5 A container that passes a limit is stopped and reported as NO_RESULT (AC-AGT-29.2).
- AC-AGT-33.6 Only the controller can start containers. This is a documented limitation.

## Not built

No code meets these criteria and no other story replaced them; the table says what exists nearby. Their status is `stale`: they need reconciling with the implementation and the scope before anyone starts them.

| Story | What exists today |
| --- | --- |
| AGT-24 Work through the tasks in dependency order | Development writes all the files of a run in one answer; the task dependencies of the plan do not schedule it. |
| AGT-28 Assemble and freeze one candidate | There is no candidate artifact. The controller exports the documents after the parallel stages join (AGT-21). |
| AGT-39 End runs left behind by a crash | Nothing ends a RUNNING run at startup and leftover containers are not removed. A crash can leave a run RUNNING. |
| AGT-40 Show how every review finding was settled and that all changed code was reviewed | There is no list of reviewed files and no resolution of findings. |
| AGT-41 Report unit and functional coverage and name every gap | The verification result holds no coverage figures, and no per-run functional coverage report is written. |
| AGT-44 Keep an append-only audit trail | There is no event trail and no restricted database role. Stored model calls, artifacts, approval decisions and verification results are a partial record, and the transitions are logged. |
| AGT-51 Change the requirement of a running run | A requirement cannot be changed while a run is in progress. A change request on a finished run is a new run (AGT-49, AGT-50). |
| AGT-52 Refuse stale results | Nothing exists for this story. |
| AGT-53 Re-plan after a change | Nothing exists for this story. |
| AGT-54 Run the greenfield scenario for the product | `scenarios/greenfield.md` is a walkthrough, and one run's artifacts and lineage are committed under `runs/<run-id>/` (AC-AGT-67.3). The pinned decisions, the coverage reports, the audit and the commit history of one commit per task that this story asks for are not proved. |
| AGT-55 Run the brownfield scenario | `scenarios/brownfield.md` holds the requirement, and AGT-49 proves a change run with a scripted model. A recorded run with the impact approval and the exact expiry boundary, and the requirement change in mid-run (AGT-51 to 53), are not proved. |
| AGT-56 Run the ambiguous scenario | No recorded run of a vague requirement exists, and no recording shows a failed primary-model call followed by the fallback. |
| AGT-57 Run the failure drill | Nothing exists for this story. |
| AGT-58 Replay a recorded run with everything else real | Replay mode exists (AGT-10). There are no scenario recordings and no replay regression check. |
| AGT-60 Start the database with `docker compose up` and run the controller locally | `docker-compose.yml` starts PostgreSQL only (AC-AGT-60.1) and the README explains the local start. The model mode defaults to live, not replay (AC-AGT-60.3), and the fresh-clone check of AC-AGT-60.4 was not recorded. |

### AGT-24 Work through the tasks in dependency order

Status: stale

Depends on: AGT-18, AGT-23.

As the platform, I want the development stage to work through the plan's tasks in dependency order, so that each task is done after the ones it depends on and the stage ends only when all are done.

- AC-AGT-24.1 Each task has a stored state: pending, ready, running, done or failed. A task is ready only when all the tasks it depends on are done.
- AC-AGT-24.2 The stage runs one ready task at a time in the plan's order. Development proposes, the controller applies and commits, and the task becomes done. Running tasks side by side is a later story.
- AC-AGT-24.3 A task that cannot be completed (the answer stays invalid after repair, the budget is used up, the model is unavailable) is marked failed. Tasks that depend on it stay pending and never run. The stage ends FAILED with the reason of the first failure.
- AC-AGT-24.4 After a restart or a resume, finished tasks are not run again and their commits stay single. The stage goes on with the rest.
- AC-AGT-24.5 The stage ends when every task is done, with a development artifact that lists the tasks, their commit ids and the head commit.
- AC-AGT-24.6 Task records, not the graph checkpoint, are the source of truth for progress. With the checkpoint discarded and the stage started again, finished tasks are not run again.

### AGT-28 Assemble and freeze one candidate

Status: stale

Depends on: AGT-25 to AGT-27.

As the platform, I want the reviewed work, the tests and the documentation combined into one frozen revision, so that every later check and approval applies to exactly the same commit.

- AC-AGT-28.1 After the parallel stages join, the controller applies the QA change set and then the documentation change set to the run branch, each all or nothing and each as one commit. Two change sets that write the same path end the run FAILED with the reason ASSEMBLY_CONFLICT, and nothing is merged silently.
- AC-AGT-28.2 The head commit after assembly is recorded as the candidate: an artifact holds its commit id and the artifact versions it contains.
- AC-AGT-28.3 Nothing changes the candidate. A result tied to another commit (a build, a test run, a review, an approval) is refused as stale.
- AC-AGT-28.4 Assembling twice, as a resumed step does, makes no second set of commits.

### AGT-39 End runs left behind by a crash

Status: stale

Depends on: AGT-35, AGT-33.

As a reviewer, I want a run that was running when the controller died to end clearly as failed, so that no run stays RUNNING forever. Work in progress is not resumed: a new run is started. This replaces the recovery story that `run-execution.md` deferred.

- AC-AGT-39.1 At startup, every run that is RUNNING is ended FAILED with the reason RECOVERY_FAILED, in the same transaction as its status (AC-AGT-9.3).
- AC-AGT-39.2 A run that is waiting for input or already finished is not touched. A waiting run still waits with the same questions after the restart (AC-AGT-34.4).
- AC-AGT-39.3 A sandbox container left over from the crash is removed at startup. Containers are found by a label that names the platform (AGT-33).
- AC-AGT-39.4 The documentation states the limitation: one controller instance, and a crash during code generation or a build ends the run.

### AGT-40 Show how every review finding was settled and that all changed code was reviewed

Status: stale

Depends on: AGT-26, AGT-28, AGT-31.

As a reviewer, I want every changed file reviewed and every finding settled in the open, so that I can see that all submitted code was reviewed and how each issue ended.

- AC-AGT-40.1 The final review (AC-AGT-29.4) lists as reviewed every file that differs between the base and the candidate. The gate fails when a changed file is not listed.
- AC-AGT-40.2 Each finding of the first review gets a resolution in the final review: fixed (naming a later commit that changed the file), no longer applicable, accepted with the reviewer's reason, or open. A claimed fix with no later commit on that file is refused as invalid.
- AC-AGT-40.3 An open blocking finding fails the gate. Findings of other severities may stay open and are listed with their resolution.
- AC-AGT-40.4 The findings and their resolutions are written to the run's folder (AGT-21) for the candidate commit.

### AGT-41 Report unit and functional coverage and name every gap

Status: stale

Depends on: AGT-25, AGT-29.

As a reviewer, I want the unit and functional coverage reports with every shortfall named and explained, so that I can see exactly what is and is not proved.

- AC-AGT-41.1 The unit coverage report that the profile produced is stored in the run's folder with the candidate's commit id.
- AC-AGT-41.2 A functional coverage report is generated by plain code: a table of every acceptance criterion with the tests that prove it and their results. When the candidate has an OpenAPI document, a second table lists every endpoint and status code with the tests that call it.
- AC-AGT-41.3 Every uncovered item reported (a line or branch, a criterion, an endpoint or a status code) needs a reason written by the QA role. An item without a reason fails the gate. A reason for an item that is not uncovered is ignored.
- AC-AGT-41.4 The reports state the real numbers. A named gap explains a shortfall and never turns it into a pass.

### AGT-44 Keep an append-only audit trail

Status: stale

Depends on: AGT-28.

As a reviewer, I want every action of a run recorded in an append-only trail, so that I can read the history of what the agents and people did.

- AC-AGT-44.1 Every node execution, gate decision, approval, retry, fallback, policy finding and change of run status is stored as an audit event: the run, the actor (an agent role, an approver name or the system), the type, ids and codes as details, and the time. Details never hold requirement, answer or model text.
- AC-AGT-44.2 The database role the application uses can add events but cannot change or delete them. A test using that role tries both and fails.
- AC-AGT-44.3 The documentation states the limit: the trail is append-only for the application, but a database administrator can still change it, so it is a record and not proof against tampering.

### AGT-51 Change the requirement of a running run

Status: stale

Depends on: AGT-11, AGT-15.

As a requester, I want to change the requirement of a run in progress, so that the work follows what I now want and nothing built on the old text is trusted.

- AC-AGT-51.1 A requester can submit a revised requirement for a run that is not finished. It becomes a new version of the requirement input, kept with a fingerprint.
- AC-AGT-51.2 Every artifact that consumed the old version, directly or through its lineage, and every approval of those artifacts, is marked stale.
- AC-AGT-51.3 The run restarts from the requirements stage with the revised text. The budget carries over and is not reset.
- AC-AGT-51.4 A revision for a finished run is refused with 409 in the RUN-4 format.

### AGT-52 Refuse stale results

Status: stale

Depends on: AGT-36, AGT-51.

As the platform, I want results built on old versions refused, so that late or slow work can never overwrite newer decisions.

- AC-AGT-52.1 A result built from an artifact version that is now stale is refused, even if it arrives late, and the refusal is recorded.
- AC-AGT-52.2 Work in progress whose inputs became stale is cancelled (the mechanism of AGT-36) and its change sets are never applied.
- AC-AGT-52.3 A result tied to a commit that is not the current candidate is refused (AC-AGT-28.3).

### AGT-53 Re-plan after a change

Status: stale

Depends on: AGT-24, AGT-52.

As a reviewer, I want the plan redone after a change, with the difference shown, so that finished work is kept, outdated work is replaced and the gates open again.

- AC-AGT-53.1 After the revised requirements and design are approved again, the planner runs again and a new plan version is stored. The difference from the old plan is shown: tasks added, removed and changed.
- AC-AGT-53.2 Done tasks whose inputs did not change are kept. Pending tasks are replaced by the new plan. Running tasks whose inputs changed are cancelled and their results refused. New tasks are scheduled by the usual rule.
- AC-AGT-53.3 Approvals tied to old versions no longer apply, and the gates concerned open again.
- AC-AGT-53.4 The run budget carries over, so a run cannot loop forever by re-planning.

### AGT-54 Run the greenfield scenario for the product

Status: stale

Depends on: AGT-32, AGT-41, AGT-44, AGT-46.

As the platform owner, I want one complete run that builds the product from a written requirement, so that the platform is proved on the product it was made for.

- AC-AGT-54.1 `scenarios/greenfield.md` states a request to build a link-shortening service with link creation, redirects and click statistics. It pins, as decisions that must appear as numbered criteria in the approved spec: where data is kept, the code format, collision handling, concurrent creation, idempotency, URL validation, the redirect status, unknown and expired codes, atomic click counting, the error format and audit content.
- AC-AGT-54.2 A run of the scenario ends SUCCEEDED with the product under `apps/url-shortener/` and the evidence under `runs/<run-id>/`: requirements, design, plan, review, QA (unit and functional coverage with gaps), audit and metrics.
- AC-AGT-54.3 The product builds and its tests pass in the sandbox through the execution profile, and its coverage and gaps are reported as in AGT-41.
- AC-AGT-54.4 The commit history has one commit per task, with the agent as author and the run and task in the message.
- AC-AGT-54.5 Each approval in the run was made with the evidence in front of the approver (AGT-15, AGT-32).

### AGT-55 Run the brownfield scenario

Status: stale

Depends on: AGT-49, AGT-50, AGT-53, AGT-54.

As the platform owner, I want a run that changes the existing product, so that brownfield work, impact analysis and re-planning are shown on a real change.

- AC-AGT-55.1 `scenarios/brownfield.md` asks to add an expiry to the links of the product that the greenfield run built.
- AC-AGT-55.2 The run includes the impact report and its approval, then design, plan and code, and ends SUCCEEDED.
- AC-AGT-55.3 A requirement change made while the run is in progress triggers detection, refusal of stale results, a re-plan with its difference shown, and new approvals, and the run still ends SUCCEEDED.
- AC-AGT-55.4 The approved criteria state the expiry boundary exactly: a link works strictly before the instant and is expired at or after it, in UTC. The product's tests prove it with a clock they can set.

### AGT-56 Run the ambiguous scenario

Status: stale

Depends on: AGT-6, AGT-15, AGT-54.

As the platform owner, I want a run that starts from a vague requirement, so that clarification, narrowing the scope and the model fallback are shown.

- AC-AGT-56.1 `scenarios/ambiguous.md` holds a deliberately vague request about making the product more dependable.
- AC-AGT-56.2 The run produces questions and assumptions before anything else, the reviewer narrows the scope at the spec approval, and the run ends SUCCEEDED on the narrowed scope.
- AC-AGT-56.3 The recording of this run includes a failed primary-model call followed by the fallback model's answer, and the call records show both.

### AGT-57 Run the failure drill

Status: stale

Depends on: AGT-31, AGT-43, AGT-54.

As the platform owner, I want a run that cannot succeed to end safely, so that the repair limit, the human choice, the budget and the rollback are shown.

- AC-AGT-57.1 `scenarios/failure-drill.md` holds a requirement with an acceptance criterion that cannot be met.
- AC-AGT-57.2 The repair attempts run out, the run waits for the reviewer, and the reviewer chooses to stop. The run ends FAILED with the reason STOPPED_BY_REVIEWER.
- AC-AGT-57.3 The workspace is removed, the main branch is untouched, the budget was respected, and the stop is counted in the rollback metric.

### AGT-58 Replay a recorded run with everything else real

Status: stale

Depends on: AGT-10, AGT-54 to AGT-57.

As a reviewer without a model, I want to replay a recorded scenario with everything but the model real, so that I can check the platform's behaviour myself.

- AC-AGT-58.1 Each scenario has a recording under `recordings/`. Replay of a scenario completes without contacting any model.
- AC-AGT-58.2 In replay, change sets are applied, builds run, tests run and coverage is measured for real. A recording whose answer holds a patch that breaks a test fails its gate in replay.
- AC-AGT-58.3 A replay regression check replays a recording after a platform change and fails when the controller's decisions differ from the recorded ones.

### AGT-60 Start the database with `docker compose up` and run the controller locally

Status: stale

Depends on: AGT-10.

As a reviewer, I want a short setup, so that I need only Java 21 and Docker to try the platform.

- AC-AGT-60.1 The Compose file starts PostgreSQL only, with settings from environment variables and no secret in a committed file.
- AC-AGT-60.2 The controller, started locally with the Maven wrapper against that database, reports healthy at `/actuator/health`, and a run can be created against it.
- AC-AGT-60.3 The model mode is replay unless a live model is configured. The local model server is reached at a configured address.
- AC-AGT-60.4 From a fresh clone, following only the README, a reviewer starts the database and the controller and replays a scenario. The check is made by hand and its result is recorded in the steps log.

## Cut from scope

User decisions, 2026-10-04: the Java profile (AGT-42), operation identities (AGT-38), the audit anchor and hash chain (AGT-45, and the chain of AGT-44), the evaluation framework (AGT-59), lease-based crash recovery (replaced by AGT-39's restart rule), account management and role hierarchies (AGT-48), and distributed scale (one controller instance, documented). The stories AGT-38, AGT-42, AGT-45 and AGT-59 are no longer written down anywhere in the requirements.

Not planned: training or fine-tuning models, and a real cloud deployment. Expiry of runs that wait too long for a person is not built either.

Stretch, only after everything else: retrieval over standards and code, tool calling and an MCP connection to an outside tool, a second execution profile, and a run on an unrelated requirement.

## Open questions kept with the backlog

- How the default of one call at a time on a local model interacts with the three parallel branches and their timeouts (AGT-36).
- How the application's database role is split from the migration role for the audit trail (AGT-44).
