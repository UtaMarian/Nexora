#!/bin/sh
# Without mounted certificates, start with a self-signed one so HTTPS always works (browsers will warn).
set -e
DIR=/etc/nginx/certs
if [ ! -s "$DIR/tls.crt" ] || [ ! -s "$DIR/tls.key" ]; then
  mkdir -p "$DIR"
  HOST="${NEXORA_PUBLIC_HOST%%:*}"
  openssl req -x509 -newkey rsa:2048 -nodes -days 365 -sha256 -subj "/CN=${HOST:-localhost}" \
    -addext "subjectAltName=DNS:${HOST:-localhost},DNS:localhost,IP:127.0.0.1" \
    -keyout "$DIR/tls.key" -out "$DIR/tls.crt" 2>/dev/null
  echo "nexora: no certificate mounted, generated a self-signed one for ${HOST:-localhost}"
fi
