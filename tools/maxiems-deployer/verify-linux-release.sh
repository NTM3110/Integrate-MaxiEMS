#!/usr/bin/env bash
set -euo pipefail
release=${1:?Usage: verify-linux-release.sh RELEASE_DIRECTORY}
cd -- "$release"
[[ -f SHA256SUMS && -x maxiems-deployer ]] || { echo 'Missing release executable or checksums.' >&2; exit 2; }
sha256sum --check --strict SHA256SUMS
# Empty runtime location proves the self-contained executable does not use a system SDK/runtime.
DOTNET_ROOT=/nonexistent DOTNET_ROOT_X64=/nonexistent DOTNET_MULTILEVEL_LOOKUP=0 ./maxiems-deployer --help
