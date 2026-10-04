# AGENTS.md

Instructions for AI coding assistants working in this repository. This file is the single source of truth; `CLAUDE.md` imports it.

Note on naming: "agents" in this file means the coding assistants helping to build the platform. The SDLC agents that the platform itself runs are described under "What this project is".



# Timeless constraints (not a checklist)

**How to work with the user (comes before everything below):** Find the simplest solution the user can fully understand and grow it one small step at a time; never jump to the final design. Change one thing per step and always say WHY (the problem it solves) before WHAT, in plain words.

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

Do not add, unless the user asks: LangChain4j, Temporal, a vector store, Spring Security (it comes later, with approvals), or a Docker client library (it comes later, with the sandbox).

# Decisions already made

1. **One Spring Boot app.** `agent-controller` holds the graph, the SDLC agents, gates, approvals, and audit. There is no microservice per agent; an agent is a class with a role, a prompt, allowed tools, and a budget.
2. **One Git repository**, at the repository root. No nested repositories and no submodules.
3. **`docker compose up` starts PostgreSQL and the controller.** A reviewer needs only Docker. During development the controller may be run from the IDE against the Compose database.
4. **Each run works in a fresh clone** of this repository at `/workspaces/<run-id>` inside a Docker volume, on a branch named `run/<run-id>`. Nothing is written to a user's home folder.
5. **SDLC agents never touch files.** The controller gives an agent the file contents it needs, the agent returns a patch, and the controller checks the paths and applies it. Agent patches may only change `apps/url-shortener/` and that run's folder under `runs/`.
6. **The controller makes the commits**, with the agent as author and the run and task in the message.
7. **Builds and tests run in throwaway sandbox containers.** The workspace is copied in and the reports are copied out. The sandbox runs as a non-root user with resource limits, no credentials, and no open network during build and test.
8. **Only the controller can start containers.** This is a documented limitation.
9. **Plain, tested code decides pass or fail.** A model never makes that call.
10. **Replay mode replays model answers only.** Patches are applied and builds, tests, and coverage run for real every time.

# How to work in this repository

- **Small steps.** One change per step, explained WHY before WHAT.
- **No folders or abstractions ahead of need.** Create a package when the code that needs it is written.
- **Tests with every change.** Write the tests alongside the code, not afterwards. Coverage is measured on every build and the target is 100%; any gap is named with its reason.
- **Never push.** The human pushes.
- **One step, one commit.** Each commit says what changed and why, starts with its area such as `feat(controller):` or `docs:`, and is made only after the user approves the step.
- **Never commit `PLAN.md`** or copy text from it into committed files. If it exists locally it is a private long-form plan; read it for the destination, and build only what the current step needs.
- **No employer, client, or recruiter names** anywhere in the repository.
- **When a real decision is made,** record it as a short decision record under `docs/adr/`.
- **After each working session,** add a few lines to `docs/ai-usage-log.md`: which tool and agents, what was asked, what was accepted, what was rejected and why.

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
| `requirements-agent` | `docs/platform/requirements/<name>.md`: user stories and acceptance criteria |
| `design-agent` | `docs/platform/design/<name>.md` with diagrams, and `docs/adr/` for real decisions |
| `development-agent` | Code with error handling, logging, and auditing, and commits once the user approves |
| `qa-agent` | Tests, `reports/coverage/unit-coverage.md`, `reports/coverage/functional-coverage.md`, `reports/coverage/jacoco.csv` |
| `code-review-agent` | `reports/review/<name>.md` and a row in `reports/review/index.md` |

For every step that changes code, build files, or configuration:

1. Write the change together with its tests.
2. Run `./mvnw verify` from `agent-controller/`. The build decides whether the step passes; do not call the `qa-agent` for this.
3. If it fails, fix it. If it passes, show the user what changed and the coverage numbers, then stop.
4. When the user approves, commit that step and tick it in `docs/steps.md`. One step, one commit. Never commit before approval.

The `qa-agent` and the `code-review-agent` are run when the user asks: usually when a feature is finished, and before submission.

- A document written after the code describes the code as it is. Do not present it as having been written first.
- Before submission, every file in the repository must be covered by a review, and the coverage reports must be current.

# Steps log

`docs/steps.md` is the record of the work done in this repository. Keep it up to date on every step.

- **What counts as a step:** real work, such as building something, writing or running a test, fixing a failure, changing configuration, writing a document, or running an agent.
- **What does not count:** questions, clarifications, explanations, and discussion. Never log these.
- **Before starting a step,** add it as the next numbered line, unticked.
- **When it is finished,** tick it and add its result in a few words, and who did it: `direct` or the agent's name.
- **Log only the step you are doing.** Never write future steps.
- **Never delete or rewrite earlier entries.**

Format:

```
- [x] 1. Add the JaCoCo plugin to measure coverage. Result: verify passes, report generated. (direct)
- [ ] 2. Pin the PostgreSQL test image to a version.
```

# Commands

```
./mvnw verify              # from agent-controller/: build, tests, coverage
docker compose up          # from the root: PostgreSQL (and later the controller)
```
