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

   Provide your own trusted cert/key for `localhost`. By default, Leyline reads
   `server-chain.pem` and `server.key` from
   `~/Library/Application Support/dev.leyline/tls/`. Set `LEYLINE_CERTS` to use
   another directory.

3. Start Leyline.

   Run `just serve`.

   Pass `--cert` / `--key` or set `LEYLINE_CERT_PATH` / `LEYLINE_KEY_PATH` to
   override the default pair.

## Notes

Scripted local acceptance can inspect `GET :8090/api/response-acceptance`.
The read-only diagnostic returns the offered prompt identity, recent accepted
response identities, and the server's build revisions. Handled defaults do not
count as accepted responses. Check the expected state transition separately.
Build source digests are unavailable when the build has untracked files.

- Local-only.
- No client binaries are distributed by this repo.
- Restore the client's stock config when finished.
