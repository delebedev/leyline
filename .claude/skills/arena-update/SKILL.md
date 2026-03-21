---
name: arena-update
description: Handle Arena patch updates — discover new hostname via DNS, update justfile IPs, regen certs, update /etc/hosts, let Arena update against real servers, then verify proxy lobby. Run when Arena gets a client patch and connections break.
---

## What I do

After an Arena patch changes server hostnames/IPs, I discover the new endpoints and update everything. Two modes:

**Ask the user:** "Want the fully automated update loop? I'll handle everything — you just approve sudo prompts (TouchID)."

- **Automated (default):** I do all steps including `/etc/hosts` edits (user approves sudo via TouchID), launch Arena against real servers for update, then switch back to proxy and verify lobby.
- **Manual:** I discover + update code, print `/etc/hosts` lines for user to apply.

## Paths

- **services.conf (Arena's):** `/Users/Shared/Epic Games/MagicTheGathering/MTGA.app/Contents/Resources/Data/StreamingAssets/services.conf`
- **services.conf (leyline proxy):** `deploy/services-proxy.conf`
- **Player.log:** `~/Library/Logs/Wizards Of The Coast/MTGA/Player.log`

## Automated flow

### 1. Discover new hostname (DNS brute-force)

Read current version from `/etc/hosts` entries to get baseline build number. Then sweep both build (AA) and patch (BB) segments:

```bash
# Version format: frontdoor-mtga-production-YYYY-AA-BB-CC.w2.mtgarena.com
# Patches bump AA (build) or BB (patch) independently
for a in $(seq <baseline-5> <baseline+10>); do
  for b in 0 1 2 5 10 15 20 25 30; do
    h="frontdoor-mtga-production-2026-${a}-${b}-1.w2.mtgarena.com"
    ip=$(dig +short "$h" A 2>/dev/null | head -1)
    [ -n "$ip" ] && echo "$h -> $ip"
  done
done
```

If multiple resolve, pick the highest version. If none resolve beyond current, servers haven't changed — stop and tell user.

### 2. Resolve both IPs

```bash
dig +short frontdoor-mtga-production-<new-version>.w2.mtgarena.com A
dig +short matchdoor-mtga-production-<new-version>.w2.mtgarena.com A
```

MD always uses the same version string as FD.

### 3. Update justfile IPs (if changed)

Edit `justfile` lines:
```
fd_ip        := env("LEYLINE_FD_IP", "<new-fd-ip>")
md_ip        := env("LEYLINE_MD_IP", "<new-md-ip>")
```

### 4. Regen certs

```bash
just gen-certs "frontdoor-mtga-production-<new-version>.w2.mtgarena.com" \
               "matchdoor-mtga-production-<new-version>.w2.mtgarena.com"
```

### 5. Comment out /etc/hosts + remove services.conf (let Arena talk to real servers)

```bash
# Comment out leyline hosts entries
sudo sed -i '' \
  -e 's|^127.0.0.1 frontdoor-mtga-production|#127.0.0.1 frontdoor-mtga-production|' \
  -e 's|^127.0.0.1 matchdoor-mtga-production|#127.0.0.1 matchdoor-mtga-production|' \
  /etc/hosts
sudo dscacheutil -flushcache && sudo killall -HUP mDNSResponder 2>/dev/null

# Backup and remove patched services.conf so Arena uses built-in defaults
cp "<Arena services.conf path>" /tmp/services-leyline-backup.conf
trash "<Arena services.conf path>"
```

### 6. Launch Arena, wait for update + lobby

```bash
pkill -f MTGA 2>/dev/null; sleep 2; open -a MTGA
```

- Wait ~15-30s for Arena to start
- Check Player.log for `Doorbell response` to confirm version
- OCR for "Home" / "Play" to confirm lobby reached
- If Arena downloads an update, wait for it to complete (monitor Player.log for `SceneChange` or `Download` activity)

### 7. Kill Arena, restore proxy config

```bash
pkill -f MTGA 2>/dev/null

# Restore services.conf
cp /tmp/services-leyline-backup.conf "<Arena services.conf path>"

# Uncomment /etc/hosts with NEW version hostnames
sudo sed -i '' \
  -e 's|^#127.0.0.1 frontdoor-mtga-production-<new-version>|127.0.0.1 frontdoor-mtga-production-<new-version>|' \
  -e 's|^#127.0.0.1 matchdoor-mtga-production-<new-version>|127.0.0.1 matchdoor-mtga-production-<new-version>|' \
  /etc/hosts
sudo dscacheutil -flushcache && sudo killall -HUP mDNSResponder 2>/dev/null
```

Also update any old version references in `/etc/hosts` (both `127.0.0.1` and any commented-out remote IP lines) to the new version string.

### 8. Build, start serve-proxy, verify lobby

```bash
just build
# Start in tmux
tmux kill-session -t leyline 2>/dev/null
tmux new-session -d -s leyline 'just serve-proxy'
sleep 5
# Verify server is up
tmux capture-pane -t leyline -p | tail -10

# Launch Arena
open -a MTGA
# Wait ~15s then OCR for lobby
just arena ocr  # expect "Home", "Play", etc.
```

**Done when:** OCR shows lobby through proxy (Home, Decks, Play visible).

## Notes

- Doorbell API needs client auth — can't curl it. DNS brute-force or Player.log are the only discovery methods.
- If DNS sweep finds nothing new, fallback: remove `services.conf`, launch Arena, grep Player.log for `Doorbell response` to get current hostname.
- Old hostname may keep resolving for a while (DNS TTL). Always prefer the highest version that resolves.
- After mode switch (real → proxy): always kill + relaunch Arena. Ghost matches otherwise.
- The user has TouchID sudo — `sudo` commands will prompt for fingerprint approval.
