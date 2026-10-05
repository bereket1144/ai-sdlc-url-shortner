# 0006. Give each run its own fresh git repository

Date: 2026-10-05

Status: accepted

## Context

AGENTS.md first said that each run works in a clone of this repository, in a Docker volume, on a branch `run/<run-id>`. When the controller moved to run locally (step 86) the volume went away, and on 2026-10-05 (step 96) the clone became a sparse checkout of two folders, with a rule that agent patches may only write there. Three problems remained. A sparse checkout still keeps the whole history of the source repository in `.git`. The path rule named a product folder, which tied platform code to one product. And the generated product, which a reviewer wants to read as a repository of its own, sat inside a copy of the platform.

## Decision

On 2026-10-05 (step 102) a run's workspace became a new, empty repository. `RunWorkspace.create` makes `<workspace.base>/<run-id>` and runs `git init --initial-branch=main`. There is no clone and no source setting. The base folder is configured with `workspace.base` and lies outside the controller's checkout; the controller writes nowhere else.

The product sits at the root of the repository, in a flat layout that the development task asks for. A change set may write any relative path inside the repository except under `.git`: an absolute path, a path with `..`, a path through a symbolic link and a path under `.git` are refused, and nothing is written unless every file in the set is allowed. The controller makes the commits on `main`: the development change set as one commit by `development`, the documents later as one commit by `controller`, each with the run in the message. The sandbox runs from the repository root.

A brownfield run is expected to clone the product's own repository. That is not built.

## Consequences

- The generated product is self-contained and its history holds only generated commits. Nothing of the platform's history is copied.
- The path rule no longer names a product folder, so platform code stays generic.
- The rule for where a change may write is wider than before (the whole repository except `.git`). The guard is the path check, the link check and the fact that agents never write files themselves.
- A reviewer opens `<workspace.base>/<run-id>` to see the product; it is not inside this repository. The `git` command line must be installed where the controller runs.
- The repository and the database are not written in one transaction, and no retention policy removes old workspaces.
- A brownfield flow needs a second way to create a workspace.

## Alternatives considered and rejected

- A clone of this repository, in a volume (the first form of decision 4). It needs the volume, copies the platform's history and puts the product inside the platform.
- A sparse clone of this repository (step 96). The history still comes along and the path rule still names a product folder.
- A `git worktree` of this repository. It shares the platform's history and object store.
- A plain folder without git. The commit history of the generated work is part of what a reviewer inspects.
