#!/bin/bash

set -e

openssl genrsa -out ca.key.pem 4096
openssl req -x509 -new -nodes -key ca.key.pem -sha256 -days 1825 -out ca.pem -subj "/CN=Root CA"
openssl req -new -sha256 -nodes -out server.csr -newkey rsa:4096 -keyout server.key -config <(cat <<EOF
[req]
prompt = no
distinguished_name = dn

[dn]
CN = api.beatport.com
EOF
)
openssl x509 -req -in server.csr -CA ca.pem -CAkey ca.key.pem -CAcreateserial -out server.crt -days 365 -sha256 -extfile <(cat << EOF
subjectAltName = @alt_names

[alt_names]
DNS.1 = api.beatport.com
EOF
)