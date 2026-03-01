#!/bin/sh
# Convert PKCS#1 (BEGIN RSA PRIVATE KEY) → PKCS#8 (BEGIN PRIVATE KEY)
# Netty's JDK SSL provider only handles PKCS#8.
# traefik-certs-dumper outputs PKCS#1 from Let's Encrypt.

for key in "$LEYLINE_KEY_PATH"; do
    if [ -f "$key" ] && head -1 "$key" | grep -q "BEGIN RSA PRIVATE KEY"; then
        converted="/tmp/$(basename "$key")"
        openssl pkcs8 -topk8 -nocrypt -in "$key" -out "$converted"
        # Point env var at converted key
        export LEYLINE_KEY_PATH="$converted"
        echo "Converted $key → PKCS#8 at $converted"
    fi
done

exec "$@"
