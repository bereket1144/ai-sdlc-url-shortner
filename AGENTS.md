# AGENTS.md

Instructions for AI coding assistants working in this repository. This file is the single source of truth; `CLAUDE.md` imports it.

Note on naming: "agents" in this file means the coding assistants helping to build the platform. The SDLC agents that the platform itself runs are described under "What this project is".


# One logical step at a time (this rule comes before everything else)

- **Do one logical step per turn:** one coherent piece of work that delivers one working behaviour, with everything it needs to work. That includes every layer it touches (state, service, repository, API) and its tests. Example: adding a failed state to a run, with the service change, the repository query, the API exposure, and their tests, is one step.
- **Then stop.** Say what was done in a few lines, name the single next step, and wait for the user.
- **Never give the user more than one thing** to decide, review, or approve. If several things need a decision, ask about the first one only. Ask about the next after the answer.
- **Never chain steps.** Do not continue to the next logical step because it seems obvious or related.
- **Keep a step reviewable.** If the work holds two independent behaviours, or cannot be described as one outcome in a short paragraph, do the first, stop, and say what remains.
- **When the user says "step by step"** (or asks for smaller steps), go back to the smallest piece: one class, one test, one fix, one setting, or one document per turn. This stays in force until the user says otherwise.
- **This holds for direct work and for subagents alike.** One subagent per turn, given one logical step. When it returns, stop and report. If you are a subagent, do the one step you were given and return; do not start another.

In the steps log (see "Steps log" and "User stories"), a step is one user story, or one QA and review of a feature, and it takes many such logical steps. Each turn does one.

---

# Timeless constraints (not a checklist)

**How to work with the user (comes before everything below):** Find the simplest solution the user can fully understand and grow it one logical step at a time; never jump to the final design. Change one logical thing per step and always say WHY (the problem it solves) before WHAT, in plain words.

When two principles collide, pick the one that cuts future cost in THIS codebase.

HARD RULE: refactor to the principle FIRST, then change behavior.

1. Separation of Concerns — one kind of work per part (UI / domain / persistence / infra). Root principle.
2. Encapsulation / Information Hiding — small stable contract; hide internals.
3. High Cohesion + Loose Coupling — change-together lives together; independents talk narrow.
4. DRY — one authoritative representation of each piece of *knowledge* (not every similar line). Avoid over-DRY.
5. KISS — simplest design that works; complexity is the long-term tax.
6. Single Responsibility — one reason to change.
7. Depend on Abstractions — policy doesn’t depend on details; both depend on contracts.
8. YAGNI — no speculative features, frameworks, or “later” hooks.
9. Composition over Inheritance — assemble pieces; don’t grow fragile hierarchies.
10. Open/Closed (with discipline) — extend at stable boundaries; only where change showed up twice.

Honorable: Law of Demeter · fail fast / illegal states unrepresentable · optimize for deletion · Unix do-one-thing + compose.

Treat as constraints. Violate slogans when judgment says so.

---

# What this project is

A platform where AI agents take a written requirement through the software life cycle (requirements, design, implementation, review, testing, documentation) under human approval and automated checks. The platform is the main deliverable. A small URL shortener is the product the platform builds, to prove it works.

The platform must stay generic. The words "URL shortener" belong in requirement files and in `apps/url-shortener/`, never in platform code.

# Stack (pinned)

| Piece | Version |
| --- | --- |
| Java | 21 |
| Spring Boot | 4.1.1 |
| Spring AI | 2.0.1 |
| LangGraph4j | 1.8.27 (the stable 1.8.x line; do not move to 1.9.x) |
| Database | PostgreSQL |
| Build | Maven, via `./mvnw` |

- Base package: `io.github.bereket1144.agentcontroller`
- LangGraph4j has few public examples. Check its documentation before using an API; do not guess.

Do not add, unless the user asks: LangChain4j, Temporal, a vector store, Spring Security (it comes later, with approvals), or a Docker client library (the controller starts containers with the `docker` command line instead).

# Decisions already made

1. **One Spring Boot app.** `agent-controller` holds the graph, the SDLC agents, gates, approvals, and audit. There is no microservice per agent; an agent is a class with a role, a prompt, allowed tools, and a budget.
2. **One Git repository for the submission**, at the repository root: the platform, the product under `apps/url-shortener/`, each run's history as a branch `run/<run-id>`, and each run's evidence under `runs/<run-id>/`. No nested repositories and no submodules. The controller works in a worktree of this repository for each run (decision 4). With `workspace.auto-merge=true` (enabled in the live profile), it merges completed, verified work into the clean controller checkout before recording success, so the generated product appears beside the controller. Local edits or conflicts block delivery and retain the run branch. The human pushes the branches; the controller never pushes. Older runs are not imported automatically.
3. **`docker compose up` starts PostgreSQL only; the controller runs locally** from the IDE or with `./mvnw spring-boot:run`, against the Compose database. A reviewer needs Java 21 and Docker.
4. **Each run is a git worktree of this repository,** added by the controller with `git worktree add` on the branch `run/<run-id>`, cut from a base ref (`HEAD` by default; for a change run, the base run's branch), in the folder `<workspace.base>/<run-id>`. The folder lies outside the checkout by default and holds a sparse checkout of the product folder `apps/url-shortener/` and of `runs/<run-id>/` only. Workspace creation and development commits leave the controller checkout unchanged. Automatic delivery is the explicit exception: after verification and document export it updates the currently checked-out branch, index and working files with a merge, limited to the product and run evidence. It requires a clean checkout, refuses conflicts and never switches branches, pushes, prunes or deletes a branch or a worktree. See ADR 0013.
5. **SDLC agents never touch files.** The controller gives an agent the file contents it needs, the agent returns a patch, and the controller checks the paths and applies it. Agent patches may only write inside the product folder of that run's workspace, and never under `.git`.
6. **The controller makes the commits**, with the agent as author and the run and task in the message.
7. **Builds and tests run in throwaway sandbox containers,** started by the controller with the `docker` command line. The workspace is copied in and the reports are copied out. The sandbox runs as a non-root user with resource limits, no credentials, and no open network during build and test.
8. **Only the controller can start containers.** This is a documented limitation.
9. **Plain, tested code decides pass or fail.** A model never makes that call.
10. **Replay mode replays model answers only.** Patches are applied and builds, tests, and coverage run for real every time.

# How to work in this repository

- **Logical steps.** One logical change per step (all the layers it needs, with its tests), explained WHY before WHAT. Smaller only when the user says "step by step".
- **No folders or abstractions ahead of need.** Create a package when the code that needs it is written.
- **Tests with every change, kept light for now.** Write the tests alongside the code, not afterwards: one test per acceptance criterion on the basic behaviour, plus the obvious failure the criterion names. Do not add guards or validation that no criterion asks for, and do not write a test, or spend a step, only to cover a branch no criterion needs. Coverage is still measured on every build and the target stays 100%; every gap is named with its reason, and a later coverage sweep closes the gaps or removes the unrequested guards.
- **Never push.** The human pushes. `.claude/settings.json` denies `git push` for Claude Code, and the same rule applies to every other tool.
- **Commit each working piece.** Small commits that say what changed and why, start with the area such as `feat(controller):` or `docs:`, and are made only when the user says so. End the subject line with the name of the agent that did the work in brackets, such as `feat(controller): serve a browsable Swagger UI page for the run API (SWAG-3) [development-agent]`. Use `[main agent]` when the main session did the work directly.
- **Never commit `PLAN.md`** or copy text from it into committed files. If it exists locally it is a private long-form plan; read it for the destination, and build only what the current step needs.
- **No employer, client, or recruiter names** anywhere in the repository.
- **When a real decision is made,** record it as a short decision record under `docs/adr/`.

# Role contracts

The same five roles exist at two levels: as the coding agents in `.claude/agents/` that build this platform with the user, and as the platform's own agents that build the product. Both levels must meet the same contract.

| Role | Must produce |
| --- | --- |
| Requirements | User stories and acceptance criteria |
| Design | A design document, and architecture or design diagrams |
| Development | Code with error handling, logging, and auditing, and a meaningful Git commit history |
| Code review | Review results showing that all submitted code was reviewed, with each issue found and how it was resolved |
| QA | Unit tests, a unit coverage report, a functional coverage report against a 100% target, and a list of every area where the target was not met |

# Working mode

- **By default, do the step yourself** in the main session. Do not use a subagent unless the user says to.
- **When you work directly on code, you are the development agent.** Follow every rule in `.claude/agents/development-agent.md`, including its rules for error handling, logging, auditing, and commits.
- **If the user says "use subagents",** hand the step to the agent in `.claude/agents/` whose role fits it.
- **If the user names an agent,** use that agent for the step.

| Agent | Use it to produce |
| --- | --- |
| `requirements-agent` | `docs/platform/requirements/<area>.md`: user stories and acceptance criteria, one file per area |
| `design-agent` | `docs/platform/design/<name>.md` with diagrams, and `docs/adr/` for real decisions |
| `development-agent` | Code and its tests, fixes for the QA and review findings the user chooses, and commits once the user approves |
| `qa-agent` | A report in chat: JaCoCo totals, criteria without a proving test, and proposed bug stories. It writes no files |
| `code-review-agent` | A report in chat, and one row per feature in `reports/review/index.md` (each finding and how it was resolved) |

For every step that changes code, build files, or configuration:

1. Write the change together with its tests.
2. Run `./mvnw verify` from `agent-controller/`. The build decides whether the step passes; do not call the `qa-agent` for this.
3. If it fails, fix it. If it passes, show the user what changed and the coverage numbers, then stop.
4. When the user approves, commit it and tick it in `docs/steps.md`. Never commit before approval.

- **QA and review run together, as one call, once per feature, and only when the user asks.** They report back; the user chooses which findings the development agent fixes. No agent starts another.
- **Unit coverage is the JaCoCo report** that `./mvnw verify` writes; it is not copied into a hand-written file. **Functional coverage** is a table of acceptance criteria against the tests that prove them, generated from the requirements file and the test results once, at final packaging.
- Before submission, every file in the repository must be covered by a review, and the coverage reports must be current.

# User stories

A feature has one requirements file, such as `docs/platform/requirements/run-api.md`. A new feature gets a new file; a new story for an existing feature goes in that feature's file. The requirements agent drafts the stories, and the user and the assistant talk them through before any code. A story is a small tracked item, like a ticket.

Each story has:

- **An ID and a title,** such as `RUN-3 Bound the size of a requirement`.
- **A status:** open, coded, or done.
- **The story:** "As a ..., I want ..., so that ...".
- **Numbered acceptance criteria,** each one checkable by a test.

Statuses:

- **Open:** not started.
- **Coded:** the code is written and every acceptance criterion has a passing test.
- **Done:** QA has verified and code review has reviewed the entire feature (the whole requirements file), the review findings are fixed or answered in `reports/review/index.md`, and then every story in the file is marked done.

Rules:

- **The criteria are fixed once the story's step starts.** They change only with the user's approval, and the change is noted in the steps log.
- **Who codes it** is decided with the user for each story: the user, or the assistant. Tests are written with the code.
- **Bug stories.** QA may propose a bug story when shipped behaviour is wrong. It is added to the feature's file as `BUG-<n>`, in the same format as any story, only after the user agrees. A missing test is not a bug; the development agent adds it under the story it belongs to.

# Steps log

`docs/steps.md` is the record of the work done in this repository, and the log of the AI-assisted work; there is no separate usage log. Keep it up to date on every step.

A step is one user story, or one QA and review of a feature. The steps log points to the story or the feature; it does not repeat what the requirements file already says.

- **What counts as a step:** a user story being worked, or real work that has no story, such as changing configuration or this file.
- **What does not count:** questions, clarifications, explanations, and discussion. Never log these.
- **Before starting a step,** add it as the next numbered line, unticked, with a `Done when:` line under it. For a story, the `Done when:` line names the story ID and its requirements file, and says it is coded. For a QA and review step, it names the feature file and says every story in it is marked done. For other work, it says in one or two sentences what must be true and checkable. Write it before any code. Do not change it afterwards to fit what was built; if it turns out to be wrong, say so and ask the user.
- **When it is finished,** tick it and add a `Result:` line under it: one line measured against the `Done when:` line, and who did it (the tool, and `direct` or the agent's name). For a story, the `Result:` says what was coded and tested. For a QA and review step, it says what was found and fixed, and that the stories are marked done.
- **Tick a story's step when the story is coded.** Tick a QA and review step when the feature's stories are marked done (see "User stories").
- **Log only the step you are doing.** Never write future steps.
- **Never delete or rewrite earlier entries.** Entries written before this format keep their old shape.

Format:

```
- [x] 21. RUN-6 Record the state of a run.
  Done when: story RUN-6 in docs/platform/requirements/run-api.md is coded: every criterion has a passing test.
  Result: status, created and updated times and version are stored and read back; `./mvnw verify` passes. (Claude Code, direct)
- [ ] 22. QA and review of the run API.
  Done when: QA and code review of docs/platform/requirements/run-api.md are done, their findings are fixed or answered, and every story in the file is marked done.
```

# Design notes

`docs/design-notes.md` is the place for learnings and for things to come back to. It is rough on purpose, it is local, and it is not committed. Read it at the start of a session if it exists.

Add a short entry when one of these comes up during a step:

- **A learning:** something a test or a failure taught us about a library, a tool, or our own code.
- **Something to revisit:** a choice that works for now but should be looked at again, with the reason.
- **An open question:** a decision that has not been made yet.
- **A shortcut taken:** what was skipped or simplified, and what the proper version would be.
- **Something the AI got wrong,** and the rule, test, or hook that was changed because of it.

How to use it:

- Write the entry in one or two sentences, in the section it fits, as part of the step where it came up. Adding a note is not a step of its own.
- Do not put steps there (they go in `docs/steps.md`) or decisions already made (they go in `docs/adr/`).
- Do not delete an entry when it is dealt with. Mark it resolved and say how.
- Do not act on an entry just because you read it. It is a reminder for the user, not a task.

# Commands

```
./mvnw verify              # from agent-controller/: build, tests, coverage
docker compose up          # from the root: PostgreSQL (the controller runs locally)
```
