# 0013. Automatically merge completed work into the controller checkout

Date: 2026-10-05

Status: accepted. Supersedes the manual-only delivery rule in [ADR 0010](0010-work-in-a-git-worktree-of-this-repository.md); the per-run worktrees and retained history remain.

## Context

Run branches already share the platform repository's Git history, but their files remain in separate worktree folders. The user wants completed product files to appear under `agentic-sdlc/apps/url-shortener/` automatically, without manually merging a branch.

## Decision

Story AGT-68, step 123.

- `workspace.auto-merge=true` enables automatic delivery; the default is false and the live profile sets it to true. It applies to runs completing under that configuration, without backfilling existing successful runs.
- After the workflow and document export finish, `RunDelivery` holds the run's database row lock, checks that no stop was requested and verification passed, then calls `WorkspaceMerge`. It records SUCCEEDED through the existing state service after the merge. Stop cannot be accepted during this final operation; after completion it receives the existing finished-run refusal.
- Delivery updates the currently checked-out branch of `workspace.repository`. It preserves the run's commits and authors, creates a merge commit by `controller` with the run ID, and retains the run branch and worktree. It never switches branches or pushes.
- The checkout must be attached to a branch, clean including untracked files, and free of an existing merge, rebase or other Git operation. Only the configured product folder and run evidence may differ on the run branch from the common ancestor. Evidence inherited from a base run is allowed.
- Git's `merge-tree --write-tree` detects conflicts before the working files or index are changed. This requires Git 2.38 or newer. Delivery merges the inspected commit ID, checks the checkout again immediately before merging, and disables interactive signing and hooks for the controller's merge commit.
- An unsafe delivery records FAILED with DELIVERY_BLOCKED and retains the generated work. The reviewer prepares the checkout and uses the existing Retry operation. No stash, forced checkout or reset is used. An already merged branch is a no-op.

## Consequences

Generated files appear beside the controller automatically after the existing design and plan approvals and the current pipeline checks. There is no additional final merge approval under this user-directed policy. Model QA and review findings still do not act as deterministic release gates.

The current scheduler runs sequentially, and merges within this controller are serialized. A human or another process must not perform Git operations in the checkout during delivery. Distinct greenfield runs targeting the same product folder can conflict; the controller does not choose a winner.

Git and PostgreSQL cannot commit atomically. If a merge succeeds but the database commit fails, the merge remains; repeated delivery detects that the run is already merged. This does not add crash recovery. The existing first retry resumes the parallel QA, review and documentation group when every stage already finished, which can spend more model calls before attempting delivery again.
