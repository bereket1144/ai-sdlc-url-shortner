#!/usr/bin/env python3
"""PostToolUse hook: runs after Claude Code edits a file and checks what was written.

Reports back to Claude (exit 2, findings on stderr) when the file contains:
  - something that looks like a secret (API key, private key, access token)
  - a literal password in a .properties or .yml file
  - a private term listed in .claude/private-terms.txt (a local, uncommitted list,
    one term per line, of names that must never appear in the repository)

Files that Git ignores are skipped: they are never committed.
The edit has already happened, so this cannot undo it; it tells Claude to fix it.
"""
import json
import os
import re
import subprocess
import sys

PROJECT = os.path.realpath(os.environ.get("CLAUDE_PROJECT_DIR", os.getcwd()))
MAX_BYTES = 1_000_000

SECRET_PATTERNS = [
    (r"sk-ant-[A-Za-z0-9_\-]{20,}", "an Anthropic API key"),
    (r"sk-(proj-)?[A-Za-z0-9_\-]{32,}", "an API key"),
    (r"AKIA[0-9A-Z]{16}", "an AWS access key"),
    (r"gh[pousr]_[A-Za-z0-9]{36,}", "a GitHub token"),
    (r"github_pat_[A-Za-z0-9_]{40,}", "a GitHub token"),
    (r"-----BEGIN [A-Z ]*PRIVATE KEY-----", "a private key"),
]
# A literal password in a config file. A value read from the environment, ${...}, is fine.
CONFIG_PASSWORD = re.compile(r"(?im)^\s*[\w.\-]*password[\w.\-]*\s*[=:]\s*(?!\$\{)\S+")


def checkout_root(start_dir):
    """The root of the checkout that holds start_dir. Inside a worktree this is the worktree,
    not the main checkout, so the file is judged by its own checkout's ignore rules."""
    try:
        found = subprocess.run(["git", "-C", start_dir, "rev-parse", "--show-toplevel"],
                               capture_output=True, text=True, timeout=5)
        if found.returncode == 0 and found.stdout.strip():
            return os.path.realpath(found.stdout.strip())
    except (OSError, subprocess.SubprocessError):
        pass
    return PROJECT


def is_ignored(root, path):
    try:
        return subprocess.run(["git", "-C", root, "check-ignore", "-q", path],
                              capture_output=True, timeout=5).returncode == 0
    except (OSError, subprocess.SubprocessError):
        return False


def private_terms(root):
    """The list is local and uncommitted. A worktree gets its own copy; fall back to the main checkout."""
    for base in (root, PROJECT):
        try:
            with open(os.path.join(base, ".claude", "private-terms.txt"), encoding="utf-8") as f:
                return [line.strip() for line in f if line.strip() and not line.startswith("#")]
        except OSError:
            continue
    return []


def main():
    event = json.load(sys.stdin)
    file_path = (event.get("tool_input") or {}).get("file_path")
    if not file_path:
        sys.exit(0)
    cwd = event.get("cwd") or PROJECT
    absolute = os.path.realpath(file_path if os.path.isabs(file_path) else os.path.join(cwd, file_path))
    if not os.path.isfile(absolute):
        sys.exit(0)
    root = checkout_root(os.path.dirname(absolute))
    if not absolute.startswith(root + os.sep):
        sys.exit(0)
    if os.path.getsize(absolute) > MAX_BYTES or is_ignored(root, absolute):
        sys.exit(0)
    try:
        with open(absolute, encoding="utf-8") as f:
            text = f.read()
    except (OSError, UnicodeDecodeError):
        sys.exit(0)

    shown = os.path.relpath(absolute, root)
    findings = [f"looks like {label}" for pattern, label in SECRET_PATTERNS if re.search(pattern, text)]
    if shown.endswith((".properties", ".yml", ".yaml")) and CONFIG_PASSWORD.search(text):
        findings.append("a literal password in a config file; read it from an environment variable instead")
    lowered = text.lower()
    findings += [f"the private term '{term}'" for term in private_terms(root) if term.lower() in lowered]

    if findings:
        print(f"{shown} must be fixed before continuing. It contains: " + "; ".join(findings) + ".", file=sys.stderr)
        sys.exit(2)
    sys.exit(0)


if __name__ == "__main__":
    main()
