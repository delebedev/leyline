# Netplay: 2nd Client Setup (Laptop)

Instructions for the agent on the 2nd laptop to connect to leyline running on mini (`100.73.207.103` via Tailscale).

## Prerequisites

- MTGA installed (Epic Games)
- Tailscale connected (can ping `100.73.207.103`)
- mitmproxy installed (`brew install mitmproxy`) — needed for CA cert

## 1. Server side (mini via SSH)

```bash
ssh mini
cd ~/src/leyline
```

Edit `leyline.toml` — set `synthetic_opponent = false` so the queue waits for a real 2nd player:

```toml
[game]
synthetic_opponent = false
```

Rebuild + start:

```bash
just stop; just build && just serve
```

Verify server is up:

```bash
curl -s http://localhost:8090/api/state
```

## 2. Laptop side

### Install mitmproxy CA cert

The server uses TLS certs signed by mitmproxy's CA. The client validates them.

```bash
# Generate mitmproxy CA if not already present
[ -f ~/.mitmproxy/mitmproxy-ca-cert.pem ] || mitmproxy --version

# Install into macOS system keychain
sudo security add-trusted-cert -d -r trustRoot \
  -k /Library/Keychains/System.keychain \
  ~/.mitmproxy/mitmproxy-ca-cert.pem
```

### Copy services.conf into Arena

Create a `services.conf` pointing FD/MD/WAS at mini's Tailscale IP:

```bash
# Find Arena streaming dir
STREAMING="/Applications/MTGA.app/Contents/Resources/Data/StreamingAssets"

cat > "$STREAMING/services.conf" <<'EOF'
{
  "environments": {
    "Forge": {
      "name": "Forge",
      "fdHost": "100.73.207.103",
      "fdPort": 30010,
      "ecoUri": "",
      "accountSystemBaseUri": "https://100.73.207.103:9443/",
      "accountSystemId": "leyline",
      "accountSystemSecret": "forge-secret",
      "accountSystemEnvironment": 1,
      "epicWASClientId": "",
      "epicWASClientSecret": "",
      "steamClientId": "",
      "steamClientSecret": "",
      "xsollaTransactionType": "",
      "doorbellUri": "https://100.73.207.103:9443/api/doorbell",
      "HostPlatform": 1,
      "mdHost": "100.73.207.103",
      "mdPort": 30003,
      "bikeUri": ""
    }
  },
  "$environments": ["Forge"]
}
EOF
```

### /etc/hosts entries

Arena resolves FD/MD hostnames, but `services.conf` uses IPs directly — so `/etc/hosts` is **not needed** on the laptop when using IP-based `services.conf`.

### macOS defaults (skip cert/hash checks)

```bash
defaults write com.Wizards.MtGA CheckSC -integer 0
defaults write com.Wizards.MtGA HashFilesOnStartup -integer 0
```

### Stub NPE_VO.bnk (prevents crash)

```bash
AUDIO="/Applications/MTGA.app/Contents/Resources/Data/StreamingAssets/Audio/GeneratedSoundBanks/Mac"
mkdir -p "$AUDIO"
[ -f "$AUDIO/NPE_VO.bnk" ] || echo "QktIRDAAAACWAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=" | base64 -d > "$AUDIO/NPE_VO.bnk"
```

## 3. Launch + Play

1. Launch Arena on laptop — it should connect to mini's leyline server and reach the lobby
2. On mini's machine (your main), also launch Arena (already configured with `just dev-setup`)
3. Both clients: select a deck, queue into any event
4. Matchmaking queue pairs them — PvP match starts

## Teardown (laptop)

```bash
rm "$STREAMING/services.conf"
defaults delete com.Wizards.MtGA CheckSC
defaults delete com.Wizards.MtGA HashFilesOnStartup
```

## Troubleshooting

- **Connection refused**: check `tailscale ping 100.73.207.103`, verify server is running on mini
- **TLS errors**: mitmproxy CA cert not trusted — re-run the security add-trusted-cert command
- **Stuck in queue**: both clients must hit "Play" — queue pairs first two arrivals
- **Server logs**: `ssh mini 'tail -100 ~/src/leyline/logs/leyline.log'`
