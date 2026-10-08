#!/usr/bin/env bash
set -euo pipefail
here=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
output=${1:?Usage: publish-linux.sh OUTPUT_DIRECTORY}
command -v dotnet >/dev/null || { echo 'Install the SDK specified in global.json on the build host.' >&2; exit 2; }
[[ ! -e "$output" ]] || { echo 'Output directory already exists; choose a new directory.' >&2; exit 2; }
mkdir -p -- "$output"
output=$(cd -- "$output" && pwd)
cd -- "$here"
dotnet test tests/Maxiems.Deployer.Cli.Tests/Maxiems.Deployer.Cli.Tests.csproj --configuration Release
dotnet publish src/Maxiems.Deployer.Cli/Maxiems.Deployer.Cli.csproj --configuration Release --runtime linux-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true --output "$output"
cp LINUX.md "$output/"
cd -- "$output"
find . -type f ! -name SHA256SUMS -print0 | sort -z | xargs -0 sha256sum > SHA256SUMS
"$here/verify-linux-release.sh" "$output"
