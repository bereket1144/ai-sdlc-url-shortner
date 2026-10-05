# 0003. Clarify requirements before generating stories

Date: 2026-10-05

Status: accepted for AGT-64; implementation written, test execution pending.

## Context

The skeleton generates stories alongside unanswered questions and then proceeds using assumptions. Downstream design and code can therefore depend on choices the requester has not accepted.

## Decision

The requirements stage presents at most four questions in one batch, with no assumptions to confirm. All questions need replies together before the stories. The agent's remaining assumptions are written into the stories as plain text. There is no second batch. Unanswered questions never accept defaults.

Amended on 2026-10-05 after the first live clarification attempt: requiring replies to every assumption made the batch burdensome. Replies now return `202` with a submission message. Empty fields from the other model-output phase are accepted and removed because their presence carries no conflicting content.

Python, the standard library, SQLite and pytest are fixed execution choices for this version. They are supplied to the agent as context. Clarification is separate from later approval of the resulting stories.

## Consequences

The run persists clarification and waits without holding a thread when questions exist. Assumptions alone do not pause it. Later stages can use unconfirmed proposals, while saved replies distinguish human decisions from assumptions. AGT-64 defines the observable behaviour and its precedence over the skeleton; other roles and languages remain outside this step.

## Implementation boundary

Clarification runs before the downstream graph starts. Immutable requirements artifacts hold the original batches; a separate table holds replies against their versions. A complete reply queues the run through the existing CREATED claim path. A waiting run needs no live graph or thread, and a new application instance can read its saved decisions before starting downstream work. General graph interrupts and recovery of partially completed downstream stages remain outside this story.

Amended on 2026-10-05 (step 103): the optional assumptions, the `required` flag, the `ASSUMED` status and the second batch were removed; the flow is question, answer, stories.
