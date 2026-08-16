---
summary: "Minimal local setup for end-to-end playtesting."
read_when:
  - "running end-to-end local client playtests"
  - "configuring the local client to connect to leyline"
  - "setting up localhost TLS for client-compatible runs"
---
# Local Setup

Needed for end-to-end local playtesting only.

## Requirements

- Compatible client installed separately
- Local connection config installed in the client
- Trusted localhost TLS cert
- Leyline started with the matching cert/key

## Steps

1. Install the local connection config.

   Install `app/main/resources/services.conf` as the client `StreamingAssets/services.conf`.

2. Create and trust a localhost TLS cert.

   Provide your own trusted cert/key for `localhost`.

3. Start Leyline.

   Run `just serve`.

   Pass `--cert` / `--key` or set `LEYLINE_CERT_PATH` / `LEYLINE_KEY_PATH`.

## Notes

- Local-only.
- No client binaries are distributed by this repo.
- Restore the client's stock config when finished.
