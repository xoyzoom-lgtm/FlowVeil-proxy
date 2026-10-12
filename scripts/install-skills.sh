#!/usr/bin/env bash
# Installs the owner's chosen third-party Claude Code skills into ~/.claude/skills.
# Nothing from those repos is copied into this repository (two of them have no licence that allows it);
# each is cloned at a pinned commit, so what runs is exactly what was reviewed. Re-run in every new container.
set -euo pipefail
S="${CLAUDE_SKILLS_DIR:-$HOME/.claude/skills}"; T="$(mktemp -d)"; mkdir -p "$S" "$HOME/.claude/threads"
pin() { git clone -q "https://github.com/$1.git" "$T/$2" && git -C "$T/$2" checkout -q "$3"; }
pin ykdojo/claude-code-tips        tips     577ac89252195d61283b334c4b9d292b52c82203
pin nick-vels/skills               nick     d6427a85557c16f1f3204a50923618e645fc7756
pin sergebulaev/threads-skills     threads  42d18f7fd40075c2a7c6710f3bbcf8c1069e6f57
pin sergeyramas/session-handoff-skill handoff 5f2b339edf78484e8b23e0efb3c8c80e50d9d1db
rm -rf "$S"/{session-handoff,gha,review-claudemd,version-check,autopilot}
cp -r "$T/handoff" "$S/session-handoff"; rm -rf "$S/session-handoff/.git"
for s in gha review-claudemd version-check; do cp -r "$T/tips/skills/$s" "$S/$s"; done
cp -r "$T/nick/skills/autopilot" "$S/autopilot"; rm -rf "$S/autopilot/tools/__pycache__"
rm -rf "$HOME/.claude/threads/skills" "$HOME/.claude/threads/references"
cp -r "$T/threads/skills" "$T/threads/references" "$HOME/.claude/threads/"
for d in "$HOME"/.claude/threads/skills/*/; do ln -sfn "$d" "$S/$(basename "$d")"; done
rm -rf "$T"; echo "skills installed into $S"
