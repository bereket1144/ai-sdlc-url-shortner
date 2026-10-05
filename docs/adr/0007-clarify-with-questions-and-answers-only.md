# 0007. Clarify with questions and answers only

Date: 2026-10-05

Status: accepted. It narrows [ADR 0003](0003-clarify-requirements-before-generating-stories.md), which keeps the history of how this shape was reached.

## Context

ADR 0003 made the requirements stage clarify before it writes stories. Its first form also asked the person to confirm every assumption. The first live attempt on 2026-10-05 showed two problems. A batch with many items is heavy to answer. And a model that sent empty `stories` and `openQuestions` next to its questions was taken to be in the wrong phase and failed with OUTPUT_INVALID.

## Decision

The requirements agent works in two phases, both before the graph starts (`RequirementsAgentNode.prepare`).

1. Clarification. The agent returns at most four questions, ordered by importance, each with a text, why it matters and, optionally, a proposed default. The controller keeps the first four and numbers them Q-1 to Q-4. No assumption is put to the person for confirmation. The questions are stored as requirements artifact version 1 with a `clarification_batch` row, the run becomes WAITING_FOR_INPUT and the graph is not started. A run with no questions stores no batch and goes straight to the stories.
2. Stories. One `POST /v1/runs/{id}/replies` must answer every question exactly once, each answer not blank and at most 5,000 characters; anything else is refused with 400 and changes nothing. An accepted reply returns 202 and queues the run again. The agent then writes the stories (artifact version 2) from the original requirement and the answers, and writes every choice nobody decided into the `assumptions` of that artifact as plain text. There is no second batch.

A proposed default is only a suggestion; it counts when the person types it as the answer. An unanswered question never falls back to a default, and the run waits. Python, the standard library, SQLite and pytest are given to the agent as fixed context, so it does not ask about them. The phase checks ignore empty fields of the other phase and reject non-empty ones.

## Consequences

- The person answers one short list, once. The stories separate what a human decided (the answers) from what the agent assumed (the assumptions).
- A waiting run needs no thread and no graph checkpoint. The wait and the answers are rows in PostgreSQL, so a restarted controller continues after the reply.
- The assumptions in the stories are confirmed by nobody. Approval of the stories is not built.
- The run waits with no time limit.
- Only the requirements stage asks questions. The other stages turn their open questions into assumptions ("Unanswered question: ...") and carry on.
- The pause is outside the graph, so a pause and resume inside the graph (approval gates) will need another mechanism.

## Alternatives considered and rejected

- Confirm every assumption (the first form in ADR 0003). Too heavy for the person, and the first live batch showed it.
- Several rounds of questions. The effort is not bounded and a model can keep asking.
- A graph interrupt before the requirements node. It needs a checkpoint saver and a resume path in the live graph, which are not built; a wait before the graph needs neither.
- Turn every question into an assumption and never pause, as the other stages do. The design would then depend on choices the requester never saw.
