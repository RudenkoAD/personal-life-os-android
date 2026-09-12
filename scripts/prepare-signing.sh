#!/bin/sh
# One local signing identity; never commit the keystore or properties.
set -eu
cd "$(dirname "$0")/.."
if [ -f keystore.properties ]; then
    echo 'Existing signing configuration retained.'
    exit 0
fi
umask 077
mkdir -p .local/signing
if [ -e .local/signing/life-os-release.jks ]; then
    echo 'Keystore exists without configuration; restore keystore.properties.' >&2
    exit 1
fi
SIGNING_PASSWORD=$(openssl rand -hex 32)
export SIGNING_PASSWORD
"${JAVA_HOME:?Set JAVA_HOME to JDK 17}/bin/keytool" -genkeypair -keystore .local/signing/life-os-release.jks -alias life-os -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Life OS Android' -storepass:env SIGNING_PASSWORD -keypass:env SIGNING_PASSWORD >/dev/null 2>&1
{
    printf 'storeFile=.local/signing/life-os-release.jks\nkeyAlias=life-os\n'
    printf 'storePassword=%s\nkeyPassword=%s\n' "$SIGNING_PASSWORD" "$SIGNING_PASSWORD"
} > keystore.properties
unset SIGNING_PASSWORD
echo 'Created local signing identity. Back up .local/signing and keystore.properties securely for future updates.'
