#!/bin/bash
# Leyline client setup for macOS.
# Patches the Arena client to connect to the Leyline game server.
#
# Usage:
#   ./setup.sh on    — patch Arena to connect to local leyline server
#   ./setup.sh off   — restore Arena to stock (removes services.conf)
#
# Override MTGA path: MTGA_PATH="/path/to/MTGA.app" ./setup.sh on

set -euo pipefail

MTGA_PATH="${MTGA_PATH:-/Users/Shared/Epic Games/MagicTheGathering/MTGA.app}"
STREAMING="$MTGA_PATH/Contents/Resources/Data/StreamingAssets"
AUDIO_DIR="$STREAMING/Audio/GeneratedSoundBanks/Mac"
SERVICES="$STREAMING/services.conf"

case "${1:-}" in
  on)
    if [ ! -d "$STREAMING" ]; then
      echo "Error: MTGA not found at $MTGA_PATH"
      echo "Set MTGA_PATH to your MTGA.app location."
      exit 1
    fi

    # Write services.conf pointing to local leyline server
    cat > "$SERVICES" << 'CONF'
{
  "environments": {
    "Leyline": {
      "name": "Leyline",
      "fdHost": "localhost",
      "fdPort": 30010,
      "ecoUri": "",
      "accountSystemBaseUri": "https://localhost/",
      "accountSystemId": "leyline",
      "accountSystemSecret": "forge-secret",
      "accountSystemEnvironment": 1,
      "doorbellUri": "https://localhost/api/doorbell",
      "HostPlatform": 1,
      "mdHost": "localhost",
      "mdPort": 30003,
      "bikeUri": ""
    }
  },
  "$environments": ["Leyline"]
}
CONF

    # Ensure NPE_VO.bnk exists (56-byte Wwise stub — prevents client audio crash)
    if [ ! -f "$AUDIO_DIR/NPE_VO.bnk" ]; then
      mkdir -p "$AUDIO_DIR"
      echo "QktIRDAAAACWAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=" \
        | base64 -d > "$AUDIO_DIR/NPE_VO.bnk"
      echo "Created NPE_VO.bnk stub."
    fi

    echo "Leyline enabled. Server must be running on localhost (just serve)."
    echo "Launch MTGA, select the 'Leyline' environment, enter any email/password."
    echo ""
    echo "To undo: $0 off"
    ;;

  off)
    if [ -f "$SERVICES" ]; then
      rm "$SERVICES"
      echo "services.conf removed. Arena restored to stock."
    else
      echo "Nothing to do — services.conf not found."
    fi
    ;;

  *)
    echo "Usage: $0 on|off"
    echo ""
    echo "  on   — patch Arena to connect to local leyline server"
    echo "  off  — restore Arena to stock"
    exit 1
    ;;
esac
