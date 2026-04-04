#!/usr/bin/env bash
# Forge Maven cache resolver — two modes:
#   shared: forge submodule is clean → symlink forge/.m2-local to
#           ~/.cache/leyline/forge-m2/<commit>/ (safe for worktrees to share)
#   local:  forge has uncommitted changes → real dir, so other worktrees
#           don't pick up in-progress work
#
# Outputs (eval'd by justfile): current_forge, forge_cache_mode, forge_m2
set -euo pipefail

project_dir="${1:?usage: forge-m2.sh <project-dir>}"

delete_path() {
  local path="$1"
  if [ ! -e "$path" ] && [ ! -L "$path" ]; then
    return
  fi
  if command -v trash >/dev/null 2>&1; then
    trash "$path"
    return
  fi
  python3 - "$path" <<'PY'
import os
import shutil
import sys

path = sys.argv[1]
if os.path.islink(path):
    os.unlink(path)
elif os.path.isdir(path):
    shutil.rmtree(path)
elif os.path.exists(path):
    os.unlink(path)
PY
}

cd "$project_dir"
local_repo="$project_dir/forge/.m2-local"
backup_repo="$project_dir/forge/.m2-local.worktree"
current_forge="$(cd forge && git log -1 --format=%H -- forge-core/src forge-game/src forge-ai/src forge-gui/src pom.xml)"

if [ -n "$(cd forge && git status --porcelain | grep -v '\.m2-local')" ]; then
  forge_cache_mode="local"
  forge_m2="$local_repo"
  if [ -L "$local_repo" ]; then
    delete_path "$local_repo"
  fi
  mkdir -p "$forge_m2"
else
  forge_cache_mode="shared"
  forge_m2="${LEYLINE_FORGE_M2_ROOT:-$HOME/.cache/leyline/forge-m2}/$current_forge"
  mkdir -p "$forge_m2"
  if [ -e "$local_repo" ] && [ ! -L "$local_repo" ]; then
    if [ -e "$backup_repo" ] || [ -L "$backup_repo" ]; then
      delete_path "$backup_repo"
    fi
    mv "$local_repo" "$backup_repo"
  fi
  ln -sfn "$forge_m2" "$local_repo"
fi

printf 'current_forge=%q\n' "$current_forge"
printf 'forge_cache_mode=%q\n' "$forge_cache_mode"
printf 'forge_m2=%q\n' "$forge_m2"
