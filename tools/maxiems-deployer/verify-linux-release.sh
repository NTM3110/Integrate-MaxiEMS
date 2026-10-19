#!/usr/bin/env bash
set -euo pipefail
if [[ $# -lt 1 || $# -gt 2 || ( $# == 2 && "$2" != '--cli-only' ) ]]; then
    echo 'Usage: verify-linux-release.sh RELEASE_DIRECTORY [--cli-only]' >&2
    exit 64
fi
release=$1
mode=${2:-full-release}
cd -- "$release"
[[ -f SHA256SUMS && -x maxiems-deployer ]] \
    || { echo 'Missing release executable or checksums.' >&2; exit 2; }
if [[ "$mode" == full-release ]]; then
    [[ -f install.sh && ! -L install.sh && -x install.sh \
        && -f maxiems-local-bootstrap-helper && ! -L maxiems-local-bootstrap-helper \
        && -x maxiems-local-bootstrap-helper ]] \
        || { echo 'Missing local installer assets.' >&2; exit 2; }
fi
sha256sum --check --strict SHA256SUMS
if [[ -f payload.zip.manifest.json ]]; then
    [[ -f release.txt ]] || { echo 'Missing release.txt.' >&2; exit 2; }
    cmp --silent release.txt <(jq -r '"releaseId=\(.releaseId)\nbuildTimestampUtc=\(.buildTimestampUtc)\ngitRevision=\(.gitRevision)\npayloadHash=\(.payloadHash)"' payload.zip.manifest.json) \
        || { echo 'release.txt does not match payload manifest.' >&2; exit 2; }
fi
# Empty runtime location proves the self-contained executable does not use a system SDK/runtime.
DOTNET_ROOT=/nonexistent DOTNET_ROOT_X64=/nonexistent DOTNET_MULTILEVEL_LOOKUP=0 ./maxiems-deployer --help
