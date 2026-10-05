# 0003. Clarify requirements before generating stories

Date: 2026-10-05

Status: accepted for AGT-64; implementation pending.

## Context

The skeleton generates stories alongside unanswered questions and then proceeds using assumptions. Downstream design and code can therefore depend on choices the requester has not accepted.

## Decision

The requirements stage first presents questions and product assumptions together. Every item requires an explicit answer or confirmation in one complete batch before stories are generated. Further material ambiguity produces another batch; omitted answers never mean acceptance of a default.

Python, the standard library, SQLite and pytest are fixed execution choices for this version. They are supplied to the agent as context. Clarification is separate from later approval of the resulting stories.

## Consequences

The run must persist its clarification items and replies and wait without holding a thread. This adds a human interaction before story generation while preventing unconfirmed assumptions from governing implementation. AGT-64 defines the observable behaviour and its precedence over the skeleton; other roles and languages remain outside this step.
