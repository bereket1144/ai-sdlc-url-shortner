# 0011. Stop cooperatively and retain work for resume

Date: 2026-10-05

Status: accepted for AGT-43; implementation written, test execution pending.

## Decision

Store a durable stop request on a running run and check it before every model call, stage attempt and build, and between stages. A model call already in flight may finish and its result is recorded. Poll the stop request while a build runs and remove its container when cancellation ends the attached command.

STOPPED is separate from failure. It keeps the workspace, branch, artifacts and decisions. Resume uses the existing first-retry state reconstruction without spending an attempt; a pending question or approval is restored instead of bypassed. No rollback or workspace deletion is part of this operation.

## Consequences

Stopping is cooperative, so an in-flight model call can delay it until the call returns or times out. The final success/failure transition locks the run against stop requests. Interrupted stages are reopened only after execution and its parallel branches have ended. The existing first-retry rule restarts QA, review and documentation as a group. General process-crash recovery and a separate discard operation remain outside this decision.
