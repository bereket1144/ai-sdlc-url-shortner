# ADR 0012: Reconcile platform backlog statuses

Date: 2026-10-05

Status: accepted

## Context

The platform requirements mix historical criteria, implemented behaviour and postponed work. Their open statuses no longer describe the current backlog accurately. After reviewing the code and tests, the user requested closure of the listed already-built features, stale statuses for the remaining open stories, and done or deferred statuses for removal candidates.

## Decision

Close AGT-15, AGT-34, AGT-49, AGT-50, AGT-65, AGT-66 and UI-2 as done. Close AGT-20 as done because AGT-67 and ADR 0010 supersede its workspace arrangement.

Defer AGT-13, AGT-14, AGT-19, AGT-25, AGT-35, AGT-36, AGT-37, AGT-43, AGT-48 and BUG-1. Mark the other 30 previously open stories stale pending reconciliation. Preserve all acceptance criteria and existing implementation, including the partial Stop/Resume work. Retain brownfield support.

For these explicitly annotated closures, done means user-directed backlog closure. It does not certify a new QA or code review pass or verification of every historical criterion. Stale means the story needs reconciliation with the implementation and scope; deferred means further work is postponed. Pre-existing statuses elsewhere retain their original meaning.

## Consequences

The backlog distinguishes retained implementation from future work without deleting history or pretending unfinished criteria passed. No runtime behaviour, test evidence or historical steps are changed by this reconciliation. Deferred code remains available, and stale stories need review before implementation starts.
