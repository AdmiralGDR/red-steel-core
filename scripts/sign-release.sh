#!/usr/bin/env bash
# Release integrity chain: hash every shipped artifact, sign the manifest, sign each jar.
# Fulfils the mandate: SHA-256 generation + digital signatures automated for every release.
set -euo pipefail

KEY="2D7A0B299B4D264836B7ABCF66A1060E1E5A8F91"   # RedstoneTeam <admiralgdr@gmail.com>
PKG="${1:-build/game-package}"

cd "$(dirname "$0")/.."
[ -d "$PKG" ] || { echo "no package dir: $PKG (run ./gradlew packageForGame first)" >&2; exit 1; }
cd "$PKG"

echo ">> hashing artifacts in $PKG"
sha256sum ./*.jar > SHA256SUMS
cat SHA256SUMS

echo ">> signing the manifest"
gpg --batch --yes --pinentry-mode loopback --local-user "$KEY" \
    --armor --detach-sign --output SHA256SUMS.asc SHA256SUMS

echo ">> signing each artifact"
for jar in ./*.jar; do
    gpg --batch --yes --pinentry-mode loopback --local-user "$KEY" \
        --armor --detach-sign --output "${jar}.asc" "$jar"
done

echo ">> verifying"
gpg --verify SHA256SUMS.asc SHA256SUMS
sha256sum -c SHA256SUMS
echo "OK — signed release ready in $PKG"
