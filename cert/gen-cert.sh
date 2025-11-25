#!/bin/bash

set -e

rm -f server.*
rm -f keystore.*

echo "Generating certificate..."

openssl req -x509 -new -nodes -newkey rsa:4096 -keyout server.key -out server.crt -days 365 -subj "/CN=api.beatport.com" -extensions SAN -config <(cat << EOF
[req]
distinguished_name=req
[SAN]
subjectAltName=DNS:api.beatport.com
EOF
)

openssl pkcs12 -export -in server.crt -inkey server.key -out keystore.p12 -name "foo" -password pass:changeit
keytool -importkeystore -srckeystore keystore.p12 -srcstoretype pkcs12 -destkeystore keystore.jks -srcstorepass changeit -deststorepass changeit

echo -e "\nDone! Add 'server.crt' to your system's trusted root certificates."
