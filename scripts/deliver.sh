#!/usr/bin/env bash
# Deliver one finished run into this repository.
#
# Only for runs made before runs became worktrees of this repository (ADR 0010): those runs are separate repositories.
# A run made since then is already the branch run/<run-id> of this repository; merge it with
# "git merge --no-ff run/<run-id>" (see "Review and take a run's work" in the README).
#
#   scripts/deliver.sh <run-id> [<name>]
#
# 1. fetches the run's whole git history into this repository as the branch run/<run-id>
# 2. replaces apps/<name>/ (default: url-shortener) with the files of that run's last commit
# 3. saves the run's evidence under runs/<run-id>/ (needs the controller on $API; skipped when it is not running)
# 4. stages apps/<name>/ and runs/<run-id>/ and commits nothing: you review and commit.
#
# Settings (environment): WORKSPACES, the folder that holds one repository per run (default: ../agentic-sdlc-workspaces,
# next to this repository); API, the controller's address (default: http://localhost:8080).
set -euo pipefail

run_id="${1:?usage: scripts/deliver.sh <run-id> [<name>]}"
name="${2:-url-shortener}"
root="$(git -C "$(dirname "$0")/.." rev-parse --show-toplevel)"
workspaces="${WORKSPACES:-$root/../agentic-sdlc-workspaces}"
api="${API:-http://localhost:8080}"
run_repo="$workspaces/$run_id"

[[ "$run_id" =~ ^[0-9a-fA-F-]{36}$ ]] || { echo "not a run id: $run_id" >&2; exit 1; }
[[ "$name" =~ ^[A-Za-z0-9._-]+$ ]] || { echo "not a folder name: $name" >&2; exit 1; }
[[ -d "$run_repo/.git" ]] || { echo "no repository for run $run_id in $workspaces" >&2; exit 1; }
cd "$root"

# 1. The history, as a branch of this repository. The run repository shares no commit with this one.
git fetch --quiet --force "$run_repo" "main:refs/heads/run/$run_id"
echo "branch run/$run_id: $(git rev-list --count "run/$run_id") commits"

# 2. The product: exactly the files of the run's last commit, without any .git.
rm -rf "apps/$name"
mkdir -p "apps/$name"
git archive "run/$run_id" | tar -x -C "apps/$name"
echo "apps/$name: $(git ls-tree -r --name-only "run/$run_id" | wc -l | tr -d ' ') files"

# 3. The evidence.
evidence="runs/$run_id"
rm -rf "$evidence"
mkdir -p "$evidence/artifacts"
git log --reverse --date=iso --format='%h  %ad  %an  %s' "run/$run_id" > "$evidence/commits.txt"
git log --stat --reverse "run/$run_id" > "$evidence/history.txt"
if curl -fsS --max-time 10 "$api/v1/runs/$run_id" -o "$evidence/run.json" 2>/dev/null; then
  curl -fsS --max-time 10 "$api/v1/runs/$run_id/lineage" -o "$evidence/lineage.json" 2>/dev/null || true
  curl -fsS --max-time 10 "$api/v1/metrics" -o "$evidence/metrics.json" 2>/dev/null || true
  # The latest version of every artifact kind of the run.
  python3 - "$evidence/run.json" <<'PY' | while read -r kind version; do
import json, sys
latest = {}
for artifact in json.load(open(sys.argv[1])).get("artifacts", []):
    latest[artifact["kind"]] = max(latest.get(artifact["kind"], 0), artifact["version"])
for kind, version in latest.items():
    print(kind, version)
PY
    curl -fsS --max-time 10 "$api/v1/runs/$run_id/artifacts/$kind/$version" \
      -o "$evidence/artifacts/$kind-v$version.json" 2>/dev/null || true
  done
  echo "$evidence: run, lineage, metrics, $(ls "$evidence/artifacts" | wc -l | tr -d ' ') artifacts, commits"
else
  echo "$evidence: commit list and history only (the controller is not reachable at $api)"
fi

# 4. Stage, never commit. Publishing the branch is yours to do.
git add "apps/$name" "$evidence"
echo
echo "Staged apps/$name and $evidence. Review with: git status, then commit."
echo "The run's history is on the local branch run/$run_id; publish it yourself when you are ready."
