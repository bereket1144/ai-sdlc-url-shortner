# Engineering summary

## Outcome as of 2026-10-05

A thin agentic SDLC skeleton has run on a real model. The platform accepted a requirement, generated versioned requirements, design, plan and development artifacts, applied code and tests in a separate Git workspace, committed them, verified them in a disposable container, and collected QA, review and documentation artifacts.

The baseline is `feat/agent-pipeline` at `91be0a3`. The handoff records a successful controller build with 417 tests, including 4 skipped live checks. The manual live run used OpenRouter with the configured `deepseek/deepseek-v4.1-flash` model and reasoning disabled. It completed in about 100 seconds, used seven model calls with no repairs, generated `app.py` and `test_app.py`, and passed 17 pytest tests. These numbers describe that trial only; this document does not rerun it or claim independently archived run evidence.

## Rationale

The platform is the principal deliverable. A generated link-shortening service is the demonstration product. A single Java controller keeps the flow, persistence, model boundary and execution policy easy to inspect. LangGraph4j supplies the fixed stage graph and parallel join; PostgreSQL stores lifecycle and artifacts; Flyway owns database changes. Models return structured data and file contents, while plain controller code decides whether outputs are valid and whether verification passed.

The work moved from foundation proofs to a thin full flow before implementing every governance story. This exposed real output-size and hidden-reasoning problems early. Prompts now state answer size and require complete files; truncated-output repair asks for shorter answers. A sparse run clone separates generated working files from the controller checkout. A prebuilt Python container makes product testing possible without network access during execution.

## Artifacts and ownership

| Evidence | Location or read operation |
| --- | --- |
| Platform stories and acceptance criteria | [Agent pipeline](requirements/agent-pipeline.md), with other feature files in `requirements/` |
| Architecture and diagrams | [Architecture overview](architecture.md) |
| Decisions | [ADR directory](../adr/) |
| AI-assisted development history | [Steps log](../steps.md), repository commits, and coding-agent role files in `.claude/agents/` |
| Generated role outputs | PostgreSQL artifacts; `GET /v1/runs/{id}/artifacts/{kind}/{version}` |
| Generated code and tests | The run's separate workspace, branch `run/<run-id>`, under `apps/url-shortener/` |
| Run progress, commit and verification counts | `GET /v1/runs/{id}` |
| Controller test and coverage evidence | Maven Surefire and JaCoCo reports; [testing approach](testing.md) |
| Feature review and functional coverage | Existing reports under `reports/`; currently stale for recent pipeline work |

The controller owns application of generated changes, commits and container execution. Agents do not directly edit the host or decide automated verification outcomes. The human runs the controller and supplies the model configuration. Final approval is still manual outside the platform because live approval gates are unfinished.

## Remaining delivery work

AGT-62 remains open because its opt-in live end-to-end test is not written. Beyond that skeleton, the product still needs complete scenario evidence, including analytics and reliability behaviour, brownfield impact analysis, and ambiguity handling with human clarification. Task-driven development, plain-code QA/review gates with repair, approvals and pauses, stop/rollback, and current review and coverage evidence are also unfinished.

The successful small run does not prove the full product or governed lifecycle. The complete [limitations and trade-offs](limitations.md) identify these gaps and the accepted scope cuts. [README](../../README.md) provides setup and an observable API walkthrough. The next suggested implementation step is to make run kind available through the API for the brownfield route, before adding its real codebase analysis.
