#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .mrs/tls
chmod 700 .mrs .mrs/tls
if [[ ! -f .mrs/tls/server.crt ]]; then
  cat > .mrs/tls/openssl.cnf <<'EOF'
[req]
distinguished_name = dn
x509_extensions = extensions
prompt = no
[dn]
CN = localhost
[extensions]
subjectAltName = DNS:localhost,DNS:mysql,IP:127.0.0.1
basicConstraints = critical,CA:TRUE
keyUsage = critical,digitalSignature,keyEncipherment,keyCertSign
extendedKeyUsage = serverAuth
EOF
  openssl req -x509 -newkey rsa:2048 -nodes -days 30 \
    -config .mrs/tls/openssl.cnf \
    -keyout .mrs/tls/server.key -out .mrs/tls/server.crt
  chmod 600 .mrs/tls/server.key
fi
