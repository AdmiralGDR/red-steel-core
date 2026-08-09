# Authenticity & Provenance

RedSteelCore is authored and owned by **RedstoneTeam <admiralgdr@gmail.com>** and released
exclusively under the GNU AGPL-3.0-or-later. Every genuine release is cryptographically
bound to a single OpenPGP key.

## Signing key

```
RedstoneTeam <admiralgdr@gmail.com>
fingerprint: 2D7A 0B29 9B4D 2648 36B7  ABCF 66A1 060E 1E5A 8F91
```

The public key is published in this repository as [`KEYS.asc`](KEYS.asc).

## Verifying a release

```sh
# 1. import the signing key
gpg --import KEYS.asc

# 2. verify the signed release tag
git verify-tag v1.0.0

# 3. verify the signed commit
git verify-commit HEAD

# 4. verify the artifact checksum manifest
gpg --verify SHA256SUMS.asc SHA256SUMS
sha256sum -c SHA256SUMS
```

A copy that does not verify against the fingerprint above is **not** an authorized release,
regardless of where it is hosted. Compiled artifacts additionally carry an in-jar integrity
manifest and are watermarked; a redistributed binary that omits or alters these has been
tampered with.

## Reporting

Suspected unauthorized redistribution or licence violations: **admiralgdr@gmail.com**.

## Independent timestamp (OpenTimestamps)

`PROVENANCE.asc` is anchored to the Bitcoin blockchain. Anyone can verify — no trust in GitHub or any
local clock — that the signed provenance existed no later than its confirming block:

```sh
pip install opentimestamps-client
ots verify PROVENANCE.asc.ots            # verifies against PROVENANCE.asc
ots upgrade PROVENANCE.asc.ots           # pulls the confirmed Bitcoin attestation
```

This proves existence as of the anchor date. It does not, and cannot, retroactively prove an earlier
date — no timestamping system can. Continuous development is evidenced separately by the signed commit
history and the sealed, hashed source snapshot retained by the author.
