#!/usr/bin/env bash
# Creates the scanner link's private PKI (used for this link only): a CA, the scanner's server
# certificate, one client certificate per API app, and the server key's SPKI pin.
#
#   deploy/scanner/pki/make-link-pki.sh [out-dir] [scanner-ipv6]
#
# out-dir defaults to ~/kybr-scan-pki-<date> and must be OUTSIDE the repository (key material is also
# gitignored). Keep ca.key offline (password manager / encrypted disk); it is only needed to issue or
# rotate certificates. Keys: EC P-256, PKCS#8 PEM (what the API's client loads). Validity: CA 5 years,
# leaf certificates 1 year (rotate before expiry; see the private rotation runbook).
set -euo pipefail

out=${1:-"$HOME/kybr-scan-pki-$(date +%Y%m%d)"}
ipv6=${2:-}
host=kybr-clamav.fly.dev

repo=$(cd "$(dirname "$0")/../../.." && pwd)
case "$(cd "$(dirname "$out")" 2>/dev/null && pwd)/" in "$repo"/*) echo "refusing: $out is inside the repository" >&2; exit 1 ;; esac
[ ! -e "$out" ] || { echo "refusing: $out already exists" >&2; exit 1; }
command -v openssl >/dev/null || { echo "openssl is required" >&2; exit 1; }

umask 077
mkdir -p "$out"
cd "$out"

key() { openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$1"; }

# CA (link only)
key ca.key
openssl req -x509 -new -key ca.key -sha256 -days 1826 -subj "/CN=Kybr scan link CA" \
  -addext "basicConstraints=critical,CA:TRUE,pathlen:0" -addext "keyUsage=critical,keyCertSign,cRLSign" \
  -out ca.crt

leaf() { # $1=name $2=CN $3=SAN $4=extendedKeyUsage
  key "$1.key"
  openssl req -new -key "$1.key" -subj "/CN=$2" -out "$1.csr"
  printf 'basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature\nextendedKeyUsage=%s\nsubjectAltName=%s\n' \
    "$4" "$3" > "$1.ext"
  openssl x509 -req -in "$1.csr" -CA ca.crt -CAkey ca.key -CAcreateserial -sha256 -days 365 \
    -extfile "$1.ext" -out "$1.crt"
  rm -f "$1.csr" "$1.ext"
}

san="DNS:$host"
[ -n "$ipv6" ] && san="$san,IP:$ipv6"
leaf server "$host" "$san" serverAuth
leaf client-kybr-api kybr-api "DNS:kybr-api.scan-link.internal" clientAuth
leaf client-kybr-api-staging kybr-api-staging "DNS:kybr-api-staging.scan-link.internal" clientAuth

# The pin the API checks (base64 SHA-256 of the server certificate's SubjectPublicKeyInfo).
openssl x509 -in server.crt -pubkey -noout | openssl pkey -pubin -outform DER | openssl dgst -sha256 -binary \
  | base64 > server.spki-sha256

openssl verify -CAfile ca.crt server.crt client-kybr-api.crt client-kybr-api-staging.crt
cat <<EOF

Created in $out (files are 0600):
  ca.key / ca.crt                         link CA (keep ca.key offline)
  server.key / server.crt                 -> kybr-scan org: SCAN_TLS_KEY / SCAN_TLS_CERT, plus SCAN_TLS_CA = ca.crt
  client-kybr-api.key / .crt              -> kybr-api: KYBR_SCANNER_CLIENT_KEY / KYBR_SCANNER_CLIENT_CERT
  client-kybr-api-staging.key / .crt      -> kybr-api-staging: same names
  ca.crt                                  -> both API apps: KYBR_SCANNER_CA
  server.spki-sha256                      -> both API apps: KYBR_SCANNER_SPKI_SHA256
Server SAN: $san
EOF
