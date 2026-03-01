#!/usr/bin/env bash
# Verify upstream JARs are installed and current.
# Usage: check-upstream.sh <root-dir>
set -euo pipefail
root="$1"
stamp="$root/.upstream-installed"
upstream_hash=$(cd "$root" && git log -1 --format=%H -- forge-core/src forge-game/src forge-ai/src forge-gui/src pom.xml)
if [ ! -f "$stamp" ]; then
    echo "Upstream JARs not installed. Run: just install-upstream" >&2; exit 1
fi
stamp_hash=$(cat "$stamp")
if [ "$stamp_hash" != "$upstream_hash" ]; then
    echo "Upstream sources changed. Run: just install-upstream" >&2; exit 1
fi
