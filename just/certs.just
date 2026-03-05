# TLS cert generation (mitmproxy CA).
# Required because UnityTls validates FD/MD certs (CheckSC only covers HTTP/WAS).
# Imported by leyline/justfile — all variables available from parent.

# generate TLS certs signed by mitmproxy CA (run after Arena patch)
gen-certs fd_host="" md_host="":
    #!/usr/bin/env bash
    set -euo pipefail
    ca_cert="$HOME/.mitmproxy/mitmproxy-ca-cert.pem"
    ca_key="$HOME/.mitmproxy/mitmproxy-ca.pem"
    out="{{certs}}"
    if [ ! -f "$ca_cert" ] || [ ! -f "$ca_key" ]; then
        echo "mitmproxy CA not found. Run: brew install mitmproxy && mitmdump -q & sleep 2 && kill %1" >&2
        exit 1
    fi
    # auto-discover hostnames from Player.log if not provided
    player_log="$HOME/Library/Logs/Wizards Of The Coast/MTGA/Player.log"
    fd="{{fd_host}}"
    md="{{md_host}}"
    if [ -z "$fd" ] && [ -f "$player_log" ]; then
        fd=$(grep -oE "frontdoor-mtga-production-[a-zA-Z0-9._-]+\.w2\.mtgarena\.com" "$player_log" | sort -u | tail -1)
    fi
    if [ -z "$md" ] && [ -f "$player_log" ]; then
        md=$(grep -oE "matchdoor-mtga-production-[a-zA-Z0-9._-]+\.w2\.mtgarena\.com" "$player_log" | sort -u | tail -1)
    fi
    if [ -z "$fd" ]; then echo "Cannot auto-detect FD hostname. Pass fd_host= or launch Arena once." >&2; exit 1; fi
    if [ -z "$md" ]; then echo "Cannot auto-detect MD hostname. Pass md_host= or launch Arena once." >&2; exit 1; fi
    mkdir -p "$out"
    echo "Generating certs in $out"
    echo "  FD: $fd"
    echo "  MD: $md"
    for door in frontdoor matchdoor was; do
        if [ "$door" = "matchdoor" ]; then host="$md"
        elif [ "$door" = "was" ]; then host="localhost"
        else host="$fd"; fi
        openssl req -new -newkey rsa:2048 -nodes \
            -keyout "$out/$door.key" -out "$out/$door.csr" \
            -subj "/CN=$host" 2>/dev/null
        openssl x509 -req -in "$out/$door.csr" \
            -CA "$ca_cert" -CAkey "$ca_key" -CAcreateserial \
            -out "$out/$door.crt" -days 365 \
            -extfile <(echo "subjectAltName=DNS:$host,DNS:localhost,IP:127.0.0.1") 2>/dev/null
        cat "$out/$door.crt" "$out/$door.key" > "$out/$door-combined.pem"
        rm -f "$out/$door.csr"
        echo "  ✓ $door-combined.pem"
    done
    echo "Done. Certs valid for 365 days."
