#!/bin/bash

F=$1/cacerts # base folder
D=$2 # domain

rm -fr   $F/application/$D
mkdir -p $F/application/$D/{private,csr,certs}

# Create a private key for the application
openssl genpkey -algorithm RSA -out $F/application/$D/private/${D}.key.pem
chmod 400                           $F/application/$D/private/${D}.key.pem

# Create a certificate signing request (CSR) for the application
openssl req\
 -key    $F/application/$D/private/${D}.key.pem\
 -new\
 -sha256\
 -subj "/C=MT/ST=Valeta/L=Valeta/O=Nostr Open Source Protocol/OU=Nostr Relay/CN=${D}/emailAddress=nostr-relay@example.com" \
 -out    $F/application/$D/csr/${D}.csr.pem \
 -batch

# Sign the server CSR with the intermediate CA:
openssl ca\
 -config $F/openssl_intermediate.cnf\
 -extensions server_cert\
 -days 365\
 -notext\
 -md sha256\
 -in     $F/application/$D/csr/${D}.csr.pem\
 -out    $F/application/$D/certs/${D}.cert.pem\
 -batch

# Validate application certificate
openssl verify -CAfile $F/intermediateCA/certs/ca-chain.cert.pem $F/application/$D/certs/${D}.cert.pem
