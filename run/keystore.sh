#!/bin/bash

F=$1 # basefolder
D=$2 # domain

# Initialize a keystore with an ephemeral entry
keytool -genkeypair\
 -alias boguscert\
 -storepass changeit\
 -keypass changeit\
 -keystore $F/keystore\
 -dname "CN=Developer, OU=Department, O=Company, L=LC, ST=ST, C=ZZ"

# Remove ephemeral entry
keytool -delete\
 -alias boguscert\
 -storepass changeit\
 -keystore $F/keystore

## https://docs.oracle.com/en/database/other-databases/nosql-database/21.2/security/import-key-pair-java-keystore.html#GUID-92AC18F5-C49E-49A6-B31D-8A2739DD0FD7

# Build the certificate chain
cat\
 $F/cacerts/application/$D/certs/${D}.cert.pem\
 $F/cacerts/intermediateCA/certs/ca-chain.cert.pem > $F/full-chain.cert.pem

# convert the private key and certificate files into a PKCS12 file.
openssl pkcs12\
 -export\
 -in $F/full-chain.cert.pem\
 -inkey $F/cacerts/application/$D/private/${D}.key.pem\
 -name server\
 -passout pass:changeit > $F/server.p12

# Import the PKCS12 file into Java keystore:
keytool\
 -importkeystore\
 -srckeystore $F/server.p12\
 -srcstoretype pkcs12\
 -srcstorepass changeit\
 -destkeystore $F/keystore\
 -deststorepass changeit\
 -alias server
