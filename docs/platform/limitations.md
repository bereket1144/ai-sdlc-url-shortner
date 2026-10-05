# Limitations and trade-offs

This prototype demonstrates one live end-to-end skeleton. It is not ready to operate as an unattended production development service. These limits describe implemented behaviour at baseline `91be0a3`.

| Area | Current limitation and consequence |
| --- | --- |
| Success | With agents enabled, success means the graph completed after passing generated tests. Review or QA findings do not block it. With dummy stages, success proves routing only. |
| Approvals | No live clarification, reply, approval or stop API exists. Unanswered questions are retained and represented as assumptions. High-impact changes have no enforced human gate yet. |
| Task execution | Planning produces an artifact, but development writes all files in one model answer. Task dependencies do not schedule individual development tasks. Keep trial requirements small. |
| Repair | Invalid model outputs can be repaired and transient calls retried. A failed product test or negative review does not loop back to development. |
| Brownfield | The graph contains a conditional impact-analysis route, but the create-run API always chooses greenfield. No completed brownfield scenario has been demonstrated. |
| Recovery | Stored records survive database persistence, but the live graph has no checkpoint saver or resume API. Automatic startup failure of interrupted runs and leftover-container reconciliation are not implemented. A crash can leave a run RUNNING. |
| Rollback | A separate workspace protects the source checkout, but multi-file writes are not atomic and there is no completed rollback/safe-stop workflow. Partial writes can remain in a failed workspace. |
| Product validation | The Python container runs tests and returns their report. No generated-product coverage gate, independent criterion coverage gate or release-readiness gate exists. |
| Audit and metrics | Model attempts, artifacts, commits, stage progress and verification results provide traceability. A complete append-only event trail and reliability metrics report are unfinished. |
| Access control | APIs are unauthenticated and can expose requirements and artifact contents. Use the prototype in a trusted local environment; do not expose it as a public service. |
| Secrets | The model key comes from the environment and is not deliberately injected into the sandbox. Compose still contains fixed local database credentials. No comprehensive secret scanning or compliance gate is implemented. |
| Sandbox | No network, non-root execution and resource limits reduce exposure. The trusted controller has Docker daemon access and containers share the host kernel; this is not VM isolation. Sparse checkout also does not remove repository history from `.git`. |
| Retention | Containers are normally removed after verification, but run clones and database artifacts remain. No automated retention policy exists. |
| Evidence | One small live trial passed. The three required scenarios and failure drill, current feature review and complete functional coverage are still missing. |

## Deliberate scope reductions

The current requirements use one Python execution profile. There is no Java product profile, RAG, vector store, MCP integration, frontend, cloud deployment, broad identity-management system, operation-identity recovery system, hash chain/external audit anchor, or separate live agent evaluation framework. The controller runs locally while Compose starts PostgreSQL only.

Direct context injection and whole-file outputs keep model integration understandable. The cost is larger prompts and the need for tight output limits. Parallel graph stages may share a one-call model limit, reducing API concurrency. SQLite and the standard library simplify offline product execution; they do not demonstrate distributed product scalability.

Recorded answers and scripted tests make controller checks repeatable. They do not establish the quality of a current live model. Model output remains untrusted, and a model-produced review cannot be treated as an automated pass/fail verdict.

The next implementation work should add the missing behaviours explicitly, rather than expanding the meaning of `SUCCEEDED` without corresponding gates and evidence.
