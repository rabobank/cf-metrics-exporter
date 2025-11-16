#!/bin/bash
#
# Copyright (C) 2025 Peter Paul Bakker - Rabobank
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#


# Exit on error
set -e

# Get the directory of the script
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Configuration variables - change as needed
PASSWORD="changeit"
VALIDITY=3650
OUTPUT_DIR="$SCRIPT_DIR/../target/generated-certs"
CA_NAME="Test CA"
SERVER_NAME="localhost"
CLIENT_NAME="client"
ORG="MyOrg"
ORG_UNIT="Development"
LOCALITY="Amsterdam"
STATE="NH"
COUNTRY="NL"

# Check if output directory exists
if [ -d "$OUTPUT_DIR" ]; then
    echo "Informational: output directory $OUTPUT_DIR already exists. Please remove it first to re-generate certs."
    exit 0
fi

# Create output directory
mkdir -p $OUTPUT_DIR
mkdir -p $OUTPUT_DIR/cacerts
cd $OUTPUT_DIR

# Print section header
section() {
  echo ""
  echo "========================================"
  echo "  $1"
  echo "========================================"
}

section "Creating Certificate Authority (CA)"
keytool -genkeypair \
  -alias ca \
  -keyalg RSA \
  -keysize 2048 \
  -storetype PKCS12 \
  -keystore ca.p12 \
  -storepass $PASSWORD \
  -validity $VALIDITY \
  -dname "CN=$CA_NAME,OU=$ORG_UNIT,O=$ORG,L=$LOCALITY,ST=$STATE,C=$COUNTRY" \
  -ext BasicConstraints:critical=ca:true \
  -ext KeyUsage:critical=keyCertSign,cRLSign

# Export the CA certificate
keytool -exportcert \
  -alias ca \
  -file ca.crt \
  -keystore ca.p12 \
  -storepass $PASSWORD \
  -storetype PKCS12

# Export the CA certificate in PEM format in cacerts dir
openssl x509 -inform DER -in ca.crt -out cacerts/ca.crt

section "Creating Server Certificate"
# Generate server keypair
keytool -genkeypair \
  -alias server \
  -keyalg RSA \
  -keysize 2048 \
  -storetype PKCS12 \
  -keystore server.p12 \
  -storepass $PASSWORD \
  -validity $VALIDITY \
  -dname "CN=$SERVER_NAME,OU=Server,O=$ORG,L=$LOCALITY,ST=$STATE,C=$COUNTRY"

# Create Certificate Signing Request (CSR)
keytool -certreq \
  -alias server \
  -keystore server.p12 \
  -storetype PKCS12 \
  -storepass $PASSWORD \
  -file server.csr

# Sign the server certificate with the CA
keytool -gencert \
  -alias ca \
  -keystore ca.p12 \
  -storetype PKCS12 \
  -storepass $PASSWORD \
  -infile server.csr \
  -outfile server.crt \
  -validity $VALIDITY \
  -ext BasicConstraints=ca:false

# Import CA certificate to server keystore
keytool -importcert \
  -alias ca \
  -file ca.crt \
  -keystore server.p12 \
  -storetype PKCS12 \
  -storepass $PASSWORD \
  -noprompt

# Import signed certificate back to server keystore
keytool -importcert \
  -alias server \
  -file server.crt \
  -keystore server.p12 \
  -storetype PKCS12 \
  -storepass $PASSWORD

section "Creating Client Certificate"
# Generate client keypair
keytool -genkeypair \
  -alias client \
  -keyalg RSA \
  -keysize 2048 \
  -storetype PKCS12 \
  -keystore client.p12 \
  -storepass $PASSWORD \
  -validity $VALIDITY \
  -dname "CN=$CLIENT_NAME,OU=Client,O=$ORG,L=$LOCALITY,ST=$STATE,C=$COUNTRY"

# Create Certificate Signing Request (CSR)
keytool -certreq \
  -alias client \
  -keystore client.p12 \
  -storetype PKCS12 \
  -storepass $PASSWORD \
  -file client.csr

# Sign the client certificate with the CA
keytool -gencert \
  -alias ca \
  -keystore ca.p12 \
  -storetype PKCS12 \
  -storepass $PASSWORD \
  -infile client.csr \
  -outfile client.crt \
  -validity $VALIDITY \
  -ext BasicConstraints=ca:false

# Import CA certificate to client keystore
keytool -importcert \
  -alias ca \
  -file ca.crt \
  -keystore client.p12 \
  -storetype PKCS12 \
  -storepass $PASSWORD \
  -noprompt

# Import signed certificate back to client keystore
keytool -importcert \
  -alias client \
  -file client.crt \
  -keystore client.p12 \
  -storetype PKCS12 \
  -storepass $PASSWORD

# Export client certificate as PEM format
openssl x509 -inform DER -in client.crt -out client.pem

# Export the private key from client.p12 without password protection
# Generate both PKCS#8 and PKCS#1 variants explicitly to avoid OpenSSL version differences

# PKCS#8 (BEGIN PRIVATE KEY)
openssl pkcs12 -in client.p12 -nocerts -nodes -passin pass:$PASSWORD | \
  openssl pkey -out client-key.pem

# PKCS#1 traditional RSA (BEGIN RSA PRIVATE KEY)
openssl pkcs12 -in client.p12 -nocerts -nodes -passin pass:$PASSWORD | \
  openssl pkey -traditional -out client-key-pkcs1.pem

section "Creating Server Truststore"
# Create a new truststore and import the CA certificate
keytool -importcert \
  -alias ca \
  -file ca.crt \
  -keystore server-truststore.p12 \
  -storetype PKCS12 \
  -storepass $PASSWORD \
  -noprompt

section "Creating Client Truststore"
# Create a new truststore and import the CA certificate
keytool -importcert \
  -alias ca \
  -file ca.crt \
  -keystore client-truststore.p12 \
  -storetype PKCS12 \
  -storepass $PASSWORD \
  -noprompt

section "Cleaning up temporary files"
# Clean up CSR files
rm -f server.csr client.csr

section "Certificate Generation Complete"
echo "The following files have been created in $OUTPUT_DIR:"
echo "- ca.p12: Certificate Authority keystore"
echo "- ca.crt: Certificate Authority public certificate"
echo "- server.p12: Server keystore with private key and certificate"
echo "- server.crt: Server public certificate"
echo "- client.p12: Client keystore with private key and certificate"
echo "- client.crt: Client public certificate"
echo "- client.pem: Client public certificate (PEM)"
echo "- client-key.pem: Client private key (PKCS#8)"
echo "- client-key-pkcs1.pem: Client private key (traditional PKCS#1 RSA)"
echo "- server-truststore.p12: Server truststore with CA certificate"
echo "- client-truststore.p12: Client truststore with CA certificate"
echo ""
echo "Password for all keystores and truststores: $PASSWORD"
echo ""
echo "Next steps:"
echo "1. Place these files in your application's resources directory"
echo "2. Configure your server and client to use these certificates"
echo "3. Enable mutual TLS in your application"