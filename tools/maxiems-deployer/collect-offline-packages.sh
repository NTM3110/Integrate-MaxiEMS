#!/usr/bin/env bash
# Tải dependency trong container Ubuntu sạch; không cài package trên máy build.
set -euo pipefail
if [[ $# != 2 || ! "$1" =~ ^(amd64|arm64)$ ]]; then
    echo "Usage: $0 amd64|arm64 OUTPUT_DIRECTORY" >&2
    exit 64
fi
arch="$1"
output="$(realpath -m -- "$2")"
mkdir -p -- "$output/$arch"
if find "$output/$arch" -mindepth 1 -print -quit | grep -q .; then
    echo 'Output architecture directory must be empty (avoid mixed package versions).' >&2
    exit 65
fi
# Chỉ tải .deb, không chạy binary target: builder amd64 tải được cả arm64.
docker run --rm -i --platform linux/amd64 --env "TARGET_ARCH=$arch" \
    --mount "type=bind,src=$output/$arch,dst=/out" ubuntu:24.04 bash -se <<'CONTAINER'
    export DEBIAN_FRONTEND=noninteractive
    if [[ "$TARGET_ARCH" == arm64 ]]; then
        printf "Types: deb\nURIs: http://ports.ubuntu.com/ubuntu-ports\nSuites: noble noble-updates noble-security\nComponents: main universe restricted multiverse\nArchitectures: arm64\nSigned-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg\n" > /etc/apt/sources.list.d/ubuntu.sources
    fi
    printf 'APT::Architecture "%s";\nAPT::Architectures { "%s"; };\n' "$TARGET_ARCH" "$TARGET_ARCH" > /etc/apt/apt.conf.d/99-target-architecture
    apt-get update
    mkdir -p /cache/partial
    # Empty dpkg status downloads the complete dependency closure, including
    # packages already present in the container but potentially absent on target.
    : > /empty-status
    apt-get -y --download-only --no-install-recommends \
        -o Dir::State::status=/empty-status -o Dir::Cache::archives=/cache \
        install openjdk-21-jre-headless nginx postgresql python3 network-manager \
        jq unzip curl ca-certificates sudo openssh-server iproute2 procps util-linux
    cp /cache/*.deb /out/
    cd /out
    sha256sum -- *.deb > SHA256SUMS
    chmod 644 -- *.deb SHA256SUMS
CONTAINER
printf 'Offline packages: %s/%s\n' "$output" "$arch"
