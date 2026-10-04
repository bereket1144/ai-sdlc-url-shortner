#!/usr/bin/env python3
"""PreToolUse hook: runs before Claude Code uses a tool and can stop it.

Blocks (exit 2, reason on stderr):
  - git push in any form: the human pushes
  - git commit --no-verify, git add --force, git reset --hard, git clean -f
  - edits to private files, to .env files, and to these guardrails themselves

Asks the user first (permission decision "ask"):
  - recursive rm
  - edits to .gitignore, because it is what keeps the private files out of Git
"""
import json
import os
import re
import sys

PROJECT = os.path.realpath(os.environ.get("CLAUDE_PROJECT_DIR", os.getcwd()))

# "git", then any global options, then the subcommand. Anchoring on the subcommand keeps a
# commit message that merely mentions a word like "push" from being blocked.
GIT = r"\bgit\b(?:\s+(?:-C\s+\S+|-c\s+\S+|--[\w\-]+(?:=\S+)?))*\s+"

BLOCKED_COMMANDS = [
    (GIT + r"push\b", "git push is blocked: the human pushes."),
    (GIT + r"commit\b[^|;&]*--no-verify", "git commit --no-verify is blocked: checks must not be skipped."),
    (GIT + r"add\b[^|;&]*(\s-f\b|--force)", "git add --force is blocked: ignored files are private and must stay out of Git."),
    (GIT + r"reset\b[^|;&]*--hard", "git reset --hard is blocked: it discards work. Ask the user."),
    (GIT + r"clean\b[^|;&]*\s-[a-zA-Z]*f", "git clean -f is blocked: it deletes untracked files. Ask the user."),
]
ASK_COMMANDS = [
    (r"(?:^|[|;&]\s*|\s)rm\s+(?:\S+\s+)*-[a-zA-Z]*[rR]", "Recursive rm needs the user's approval."),
]

PROTECTED_FILES = {"PLAN.md", "Requirments.md", ".claude/settings.json", ".claude/settings.local.json"}
PROTECTED_DIRS = (".claude/hooks/",)
ASK_FILES = {".gitignore"}


def block(reason):
    print(reason, file=sys.stderr)
    sys.exit(2)


def ask(reason):
    print(json.dumps({"hookSpecificOutput": {
        "hookEventName": "PreToolUse",
        "permissionDecision": "ask",
        "permissionDecisionReason": reason,
    }}))
    sys.exit(0)


def relative_path(file_path):
    absolute = os.path.realpath(file_path if os.path.isabs(file_path) else os.path.join(PROJECT, file_path))
    return os.path.relpath(absolute, PROJECT).replace(os.sep, "/")


def check_command(command):
    for pattern, reason in BLOCKED_COMMANDS:
        if re.search(pattern, command):
            block(reason)
    for pattern, reason in ASK_COMMANDS:
        if re.search(pattern, command):
            ask(reason)


def check_file(file_path):
    path = relative_path(file_path)
    name = os.path.basename(path)
    if path in PROTECTED_FILES or path.startswith(PROTECTED_DIRS):
        block(f"{path} is protected: only the user changes it.")
    if name == ".env" or name.startswith(".env."):
        block(f"{path} is protected: environment files may hold secrets.")
    if path in ASK_FILES:
        ask(f"{path} keeps the private files out of Git. Changing it needs the user's approval.")


def main():
    event = json.load(sys.stdin)
    tool = event.get("tool_name", "")
    tool_input = event.get("tool_input") or {}
    if tool == "Bash":
        check_command(tool_input.get("command", ""))
    elif tool in ("Edit", "Write", "MultiEdit", "NotebookEdit"):
        file_path = tool_input.get("file_path") or tool_input.get("notebook_path")
        if file_path:
            check_file(file_path)
    sys.exit(0)


if __name__ == "__main__":
    main()
