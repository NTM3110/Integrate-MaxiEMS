#!/usr/bin/env bash
# Tạo một thư mục phát hành có CLI, payload, hướng dẫn và checksum.
set -euo pipefail
here=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
if [[ $# -lt 4 || $# -gt 5 || ! "${3:-}" =~ ^(amd64|arm64)$ || ( $# == 5 && "$5" != '--reuse-backend' ) ]]; then
    echo "Usage: $0 UI_DIST OFFLINE_PACKAGES_ROOT amd64|arm64 NEW_RELEASE_DIRECTORY [--reuse-backend]" >&2
    exit 64
fi
ui=$(realpath -- "$1")
packages=$(realpath -- "$2")
arch=$3
output=$(realpath -m -- "$4")
[[ ! -e "$output" ]] || { echo 'Release directory already exists.' >&2; exit 65; }
backend_args=(--build-backend)
if [[ "${5:-}" == '--reuse-backend' ]]; then
    backend_jar="$here/../../platform/applications/io.openems.edge.application/generated/distributions/executable/BMSApp.jar"
    [[ -f "$backend_jar" && ! -L "$backend_jar" ]] || { echo "Missing reusable backend JAR: $backend_jar" >&2; exit 66; }
    backend_args=()
    printf 'Reusing existing backend: %s\n' "$backend_jar"
fi
"$here/publish-linux.sh" "$output"
python3 "$here/build-bms-payload.py" "${backend_args[@]}" --ui-dist "$ui" \
    --packages "$packages" --architecture "$arch" --output "$output/payload.zip"
jq -r '"releaseId=\(.releaseId)\nbuildTimestampUtc=\(.buildTimestampUtc)\ngitRevision=\(.gitRevision)\npayloadHash=\(.payloadHash)"' \
    "$output/payload.zip.manifest.json" > "$output/release.txt"
cp -- "$here/BMS_DEPLOY.md" "$output/"
/usr/bin/install --mode=0755 -- "$here/release/install.sh" "$output/install.sh"
/usr/bin/install --mode=0755 -- "$here/remote/usr/local/libexec/maxiems-bootstrap-helper" \
    "$output/maxiems-local-bootstrap-helper"
(
    cd -- "$output"
    payload_hash=$(jq -er '.payloadHash | select(test("^[0-9a-f]{64}$"))' payload.zip.manifest.json)
    printf '# payloadHash: %s\n' "$payload_hash"
    find . -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 sha256sum
) > "$output/SHA256SUMS"
"$here/verify-linux-release.sh" "$output"
printf 'Release ready: %s\n' "$output"
