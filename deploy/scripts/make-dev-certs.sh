#!/usr/bin/env bash
# Creates a local certificate authority and a server certificate for NEXORA (HTTPS for the browser, Office and
# WebDAV). Trust deploy/certs/ca.crt once on each test machine; production installs use their own certificates.
#
#   ./scripts/make-dev-certs.sh [extra-host ...]     e.g. ./scripts/make-dev-certs.sh nexora.corp.example
set -euo pipefail
export MSYS_NO_PATHCONV=1   # Git Bash on Windows: keep "/CN=..." as is
cd "$(dirname "$0")/.."
mkdir -p certs && cd certs

if [ ! -f ca.key ]; then
  openssl req -x509 -newkey rsa:3072 -nodes -days 3650 -sha256 \
    -subj "/CN=NEXORA Development CA/O=NEXORA" -keyout ca.key -out ca.crt \
    -addext "basicConstraints=critical,CA:TRUE" -addext "keyUsage=critical,keyCertSign,cRLSign"
fi

SAN="DNS:localhost,IP:127.0.0.1,IP:::1"
for h in "$@"; do SAN="$SAN,DNS:$h"; done

openssl req -newkey rsa:2048 -nodes -sha256 -subj "/CN=${1:-localhost}/O=NEXORA" -keyout tls.key -out tls.csr
printf "subjectAltName=%s\nextendedKeyUsage=serverAuth\nkeyUsage=critical,digitalSignature,keyEncipherment\nbasicConstraints=CA:FALSE\n" "$SAN" > tls.ext
openssl x509 -req -in tls.csr -CA ca.crt -CAkey ca.key -CAcreateserial -days 825 -sha256 -extfile tls.ext -out tls.crt
cat tls.crt ca.crt > tls-chain.crt && mv tls-chain.crt tls.crt
rm -f tls.csr tls.ext
chmod 600 tls.key ca.key 2>/dev/null || true
echo "Created certs/tls.crt for: $SAN"
echo "Trust certs/ca.crt on test machines, e.g. Windows:  certutil -user -addstore Root certs\ca.crt"
