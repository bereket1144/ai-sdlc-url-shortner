# 0010. Work in a git worktree of this repository

Date: 2026-10-05

Status: accepted. Supersedes [0006](0006-give-each-run-its-own-git-repository.md).

Delivery update (2026-10-05): [ADR 0013](0013-automatically-merge-completed-work.md) supersedes the manual-only merge policy when automatic delivery is enabled. The workspace and branch arrangement below remains in effect.

## Context

ADR 0006 gave each run a new, empty repository made with `git init`, outside this repository. It kept the generated product self-contained, but a reviewer then had two things to read and join: this repository and a folder of separate repositories. Getting a run's work into this repository needed a script (`scripts/deliver.sh`) that fetched the history and copied the files, and a change run needed a second way to make a workspace (a clone of the earlier run's repository). The submission is meant to be one repository that holds the controller, the product and both sets of history (AGENTS.md decision 2).

## Decision

Step 116, story AGT-67.

- A run's workspace is a git worktree of this repository, added by the controller with `git worktree add --no-checkout -b run/<run-id> <workspace.base>/<run-id> <start>`. The start is `workspace.base-ref` (default `HEAD`, the commit that is checked out). `workspace.repository` names this repository and is required when the development agent is on; the controller checks at startup that it holds a `.git`, so that git cannot find some repository above it.
- Only the product folder (`workspace.product-folder`, default `apps/url-shortener`, relative, validated at startup) and the run's own `runs/<run-id>/` are checked out, by a sparse checkout (`git sparse-checkout set --no-cone`). `--no-checkout` leaves the index empty, so `git read-tree -mu HEAD` then fills the index with every file of the branch and checks out the files that the sparse patterns keep. Before every commit the controller checks that the index drops no file of the branch, so a commit cannot delete the files that are not checked out.
- The agents' files keep flat paths. The controller writes them under the product folder, with the same path rules as before. The sandbox is given the product folder only and runs its command from that folder's root.
- The commits stay by `development` and by `controller`. The document export also writes the evidence under `runs/<run-id>/`: the latest version of each artifact as JSON and the lineage as JSON.
- A change run is cut from the branch `run/<baseRunId>`, so its history extends the earlier run's. When that branch does not exist the run fails before anything is created.
- The controller never changes the branch that is checked out here, its index or its working files, and never merges, pushes, prunes or deletes a branch or a worktree. The human merges with `git merge --no-ff run/<run-id>` and pushes the branches.
- `scripts/deliver.sh` stays, only for runs made before this change.

## Consequences

- One repository holds the platform, the product and the runs' history. A merge of `run/<run-id>` puts the product at `apps/url-shortener/` beside `agent-controller/`, with the agents' commits visible.
- The path rule names a product folder again, but as a setting: platform code stays generic.
- The first sparse checkout turns on `extensions.worktreeConfig` in this repository's config, so that the sparse settings belong to the worktree and not to the checkout.
- The branches and the worktree administration live in this repository's `.git`, so a run's history is shared with the platform's object store. Old worktrees and branches are not removed by the controller; the human prunes them.
- Two runs at the same time both change this repository's `.git` (a branch and a worktree entry each). The scheduler runs one run at a time, so this is not guarded.
- Runs made before this change are separate repositories. `scripts/deliver.sh` imports them.
- A worktree that was added but not checked out would delete files on its first commit; the check before each commit turns that into a failure that names the cause.

## Alternatives considered and rejected

- Keep a fresh `git init` repository per run (ADR 0006) and deliver it by script. Two repositories to read, and a copy step that rewrites history.
- A clone or a sparse clone of this repository per run (the first forms of decision 4). A clone copies the whole history, and a change run needs the earlier run's branch, which is only a ref in the same repository.
- A worktree with a full checkout. Every run would hold a copy of the platform, and an agent patch could in principle reach it; the sparse checkout and the product-folder path rule keep the writes inside the product folder.
- Merging or pushing from the controller. A merge changes the checked-out branch, and a push needs credentials the controller must not hold; both stay with the human.
