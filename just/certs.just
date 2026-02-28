# TLS cert management + Tailscale remote setup
# Imported by forge-nexus/justfile — all variables available from parent.

# auto-detect Tailscale IPv4
_ts_ip := `tailscale ip -4 2>/dev/null || echo ""`

# regenerate TLS certs signed by mitmproxy CA (run after Arena patch)
# extra_san: additional IP for SAN (e.g. Tailscale IP for remote client)
gen-certs fd_host="" md_host="" extra_san="":
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
    for door in frontdoor matchdoor; do
        host="$fd"; [ "$door" = "matchdoor" ] && host="$md"
        openssl req -new -newkey rsa:2048 -nodes \
            -keyout "$out/$door.key" -out "$out/$door.csr" \
            -subj "/CN=$host" 2>/dev/null
        openssl x509 -req -in "$out/$door.csr" \
            -CA "$ca_cert" -CAkey "$ca_key" -CAcreateserial \
            -out "$out/$door.crt" -days 365 \
            -extfile <(
                san="DNS:$host,DNS:localhost,IP:127.0.0.1"
                [ -n "{{extra_san}}" ] && san="$san,IP:{{extra_san}}"
                echo "subjectAltName=$san"
            ) 2>/dev/null
        cat "$out/$door.crt" "$out/$door.key" > "$out/$door-combined.pem"
        rm -f "$out/$door.csr"
        echo "  ✓ $door-combined.pem"
    done
    echo "Done. Certs valid for 365 days."

# --- Remote (Tailscale) ---

# server-side: regenerate certs with Tailscale IP in SAN (run on Mac mini)
remote-certs:
    #!/usr/bin/env bash
    set -euo pipefail
    ts_ip="{{_ts_ip}}"
    if [ -z "$ts_ip" ]; then echo "Tailscale not running." >&2; exit 1; fi
    echo "==> Regenerating certs with Tailscale IP ($ts_ip) in SAN..."
    just -f "{{nexus_dir}}/justfile" gen-certs "" "" "$ts_ip"

# client-side: fetch CA + hostnames from server, install locally (run on laptop)
# server: Tailscale hostname or IP of the Mac mini running nexus
remote-install server:
    #!/usr/bin/env bash
    set -euo pipefail
    server="{{server}}"
    certs_dir="$HOME/.local/share/forge-nexus/certs"
    echo "==> Fetching mitmproxy CA from $server..."
    scp "$server:~/.mitmproxy/mitmproxy-ca-cert.pem" /tmp/nexus-ca.pem
    remote_certs=".local/share/forge-nexus/certs"
    echo "==> Fetching cert hostnames from $server..."
    fd_host=$(ssh "$server" "openssl x509 -in ~/$remote_certs/frontdoor.crt -noout -subject" 2>/dev/null | sed 's/.*CN *= *//')
    md_host=$(ssh "$server" "openssl x509 -in ~/$remote_certs/matchdoor.crt -noout -subject" 2>/dev/null | sed 's/.*CN *= *//')
    server_ip=$(ssh "$server" "/usr/local/bin/tailscale ip -4")
    if [ -z "$fd_host" ] || [ -z "$md_host" ]; then
        echo "Cannot read certs on $server. Run 'just remote-certs' there first." >&2; exit 1
    fi
    # trust CA
    echo "==> Trusting CA in Keychain (will prompt for sudo)..."
    sudo security add-trusted-cert -d -r trustRoot -k /Library/Keychains/System.keychain /tmp/nexus-ca.pem
    echo "  ✓ CA trusted"
    # update /etc/hosts
    marker="# forge-nexus remote"
    hosts_block="$marker ($server)\n$server_ip  $fd_host\n$server_ip  $md_host"
    if grep -q "$marker" /etc/hosts; then
        echo "==> Updating existing forge-nexus entries in /etc/hosts..."
        sudo sed -i '' "/$marker/,+2d" /etc/hosts
    fi
    echo "==> Adding to /etc/hosts..."
    echo -e "$hosts_block" | sudo tee -a /etc/hosts >/dev/null
    echo "  ✓ /etc/hosts updated"
    echo ""
    echo "Done! Launch Arena — it will connect to nexus at $server_ip"
    echo "Debug panel: http://$server_ip:8090"

# show what remote-install would do (dry run, no sudo)
remote-status server:
    #!/usr/bin/env bash
    set -euo pipefail
    server="{{server}}"
    remote_certs=".local/share/forge-nexus/certs"
    fd_host=$(ssh "$server" "openssl x509 -in ~/$remote_certs/frontdoor.crt -noout -subject" 2>/dev/null | sed 's/.*CN *= *//')
    md_host=$(ssh "$server" "openssl x509 -in ~/$remote_certs/matchdoor.crt -noout -subject" 2>/dev/null | sed 's/.*CN *= *//')
    server_ip=$(ssh "$server" "/usr/local/bin/tailscale ip -4")
    echo "Server:     $server ($server_ip)"
    echo "FD host:    $fd_host"
    echo "MD host:    $md_host"
    echo ""
    echo "/etc/hosts entries:"
    grep "forge-nexus" /etc/hosts 2>/dev/null || echo "  (none)"
    echo ""
    echo "CA trusted:"
    security find-certificate -c mitmproxy /Library/Keychains/System.keychain >/dev/null 2>&1 \
        && echo "  ✓ mitmproxy CA found in System keychain" \
        || echo "  ✗ mitmproxy CA not in System keychain"
