#!/usr/bin/env bash
set -euo pipefail

usage() {
    cat <<'EOF'
Usage: verify-deployer-change.sh core|infrastructure|cli TEST_FILTER [--restore]

Compile current source incrementally and run only the selected tests (Release).
Choose a Linux-compatible test class/method filter; no full solution build runs.
Existing NuGet assets are reused. After dependency changes or on a fresh checkout,
add --restore for a locked restore with normal NuGet auditing.

Example:
  ./verify-deployer-change.sh core 'FullyQualifiedName~DeploymentEngineTests'
EOF
}

if [[ ${1:-} == --help || ${1:-} == -h ]]; then
    usage
    exit 0
fi
if [[ $# -lt 2 || $# -gt 3 || -z ${2//[[:space:]]/} ]]; then
    usage >&2
    exit 2
fi
restore=false
if [[ $# -eq 3 ]]; then
    if [[ $3 != --restore ]]; then
        printf 'Unknown option: %s\n' "$3" >&2
        usage >&2
        exit 2
    fi
    restore=true
fi

dependencies=(Core)
case "$1" in
    core) scope=Core ;;
    infrastructure) scope=Infrastructure; dependencies+=(Infrastructure) ;;
    cli) scope=Cli; dependencies+=(Infrastructure Cli) ;;
    *) printf 'Unknown project scope: %s\n' "$1" >&2; usage >&2; exit 2 ;;
esac
filter=$2
here=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
cd -- "$here"
project="tests/Maxiems.Deployer.$scope.Tests/Maxiems.Deployer.$scope.Tests.csproj"

command -v dotnet >/dev/null || {
    printf 'Install the SDK specified in %s/global.json.\n' "$here" >&2
    exit 2
}

run_timed() {
    local started=$SECONDS result=0
    printf '\nRunning:'
    printf ' %q' "$@"
    printf '\n'
    "$@" || result=$?
    printf 'Finished in %s seconds (exit %s).\n' "$((SECONDS - started))" "$result"
    return "$result"
}

if "$restore"; then
    run_timed dotnet restore "$project" --locked-mode
else
    assets=("tests/Maxiems.Deployer.$scope.Tests/obj/project.assets.json")
    for dependency in "${dependencies[@]}"; do
        assets+=("src/Maxiems.Deployer.$dependency/obj/project.assets.json")
    done
    missing=false
    for asset in "${assets[@]}"; do
        if [[ ! -f $asset ]]; then
            printf 'Missing NuGet assets: %s/%s\n' "$here" "$asset" >&2
            missing=true
        fi
    done
    if "$missing"; then
        printf 'Run again with --restore to restore locked dependencies.\n' >&2
        exit 2
    fi
fi

run_timed dotnet test "$project" --configuration Release --no-restore \
    --filter "$filter" --logger 'console;verbosity=normal'
