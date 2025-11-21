#!/bin/bash

set -e

rm -f ca.*
rm -f server.*
rm -f keystore.*

echo "Generating certificates..."

openssl genrsa -out ca.key.pem 4096
openssl req -x509 -new -nodes -key ca.key.pem -sha256 -days 1825 -out ca.pem -subj "/CN=Traktor Streaming Proxy Root CA"
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

openssl pkcs12 -export -in server.crt -inkey server.key -out keystore.p12 -name "foo" -password pass:changeit
keytool -importkeystore -srckeystore keystore.p12 -srcstoretype pkcs12 -destkeystore keystore.jks -srcstorepass changeit -deststorepass changeit

echo -e "\nDone! Add 'server.crt' to your system trusted certificates."