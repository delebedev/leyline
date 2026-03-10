---
name: arena-update
description: Handle Arena patch updates — discover new hostname via DNS, update justfile IPs, regen certs, tell user what to put in /etc/hosts. Run when Arena gets a client patch and connections break.
---

## What I do

After an Arena patch changes server hostnames/IPs, I discover the new endpoints and update everything except `/etc/hosts` (user does that — needs sudo).

## Steps

### 1. Discover new hostname (DNS brute-force)

Read current version from justfile (`fd_ip` line comment or `/etc/hosts`) to get baseline build number. Then sweep both build (AA) and patch (BB) segments:

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

### 3. Update justfile IPs

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

### 5. Tell user to update /etc/hosts

Print the exact lines they need:

```
Update /etc/hosts (sudo required):

127.0.0.1 frontdoor-mtga-production-<new-version>.w2.mtgarena.com
127.0.0.1 matchdoor-mtga-production-<new-version>.w2.mtgarena.com
```

### 6. Restart if server was running

If a leyline process is running (`pgrep -f LeylineMainKt`), stop it and tell user to restart in their preferred mode.

## Notes

- Doorbell API needs client auth — can't curl it. DNS brute-force or Player.log are the only discovery methods.
- If DNS sweep finds nothing new, fallback: ask user to remove `services.conf`, launch Arena, then grep Player.log for `Doorbell response`.
- Old hostname may keep resolving for a while (DNS TTL). Always prefer the highest version that resolves.
