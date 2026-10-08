#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIR
# shellcheck source=/dev/null
source "${SCRIPT_DIR}/../usr/local/libexec/maxiems-helper-common"

fixture="$(mktemp -d)"
readonly fixture
trap '/usr/bin/rm -rf -- "$fixture"' EXIT
/usr/bin/printf 'sentinel\n' > "$fixture/state"
before="$(sha256sum "$fixture/state")"
readonly before

expect_rejected() {
	local validator="$1"
	local candidate="$2"
	if ("$validator" "$candidate") >/dev/null 2>&1; then
		/usr/bin/printf 'expected rejection: %s %s\n' "$validator" "$candidate" >&2
		exit 1
	fi
}

for candidate in '../../etc' '--help' '20260719010000-0123456789ag' '20260719010000-0123456789ab/next' \
	'20260719010000-0123456789ab;id'; do
	expect_rejected validate_release_id "$candidate"
done

for candidate in '../../etc' '--help' 'eth0;id' 'eth0 id' 'eth0/../../x' 'abcdefghijklmnop'; do
	expect_rejected validate_interface "$candidate"
done

for candidate in '../../etc' '--help' 'not-a-uuid' '00000000-0000-0000-0000-000000000000' \
	'550e8400-e29b-41d4-a716-446655440000;id'; do
	expect_rejected validate_uuid "$candidate"
done

expect_rejected validate_sha256 '--help'
expect_rejected validate_sha256 'abc'

readonly deploy_helper="${SCRIPT_DIR}/../usr/local/libexec/maxiems-deploy-helper"
readonly deploy_ops="${SCRIPT_DIR}/../usr/local/libexec/maxiems-deploy-ops"
readonly sudoers="${SCRIPT_DIR}/../etc/sudoers.d/maxiems-deployer"
/usr/bin/grep -Fq '/usr/local/libexec/maxiems-deploy-helper bms-setup real,' "$sudoers"
/usr/bin/grep -Fq '/usr/local/libexec/maxiems-deploy-helper bms-setup demo,' "$sudoers"
! /usr/bin/grep -Fq '/usr/local/libexec/maxiems-deploy-helper bms-setup *' "$sudoers"
! /usr/bin/grep -Fxq ' /usr/local/libexec/maxiems-deploy-helper bms-setup, \' "$sudoers"
/usr/bin/grep -Fq 'require_arg_count 2 "$#"' "$deploy_helper"
/usr/bin/grep -Fq '[[ "$2" == '\''real'\'' || "$2" == '\''demo'\'' ]]' "$deploy_helper"
/usr/bin/grep -Fq 'bms-setup) /usr/local/libexec/maxiems-bms-setup setup "$2" ;;' "$deploy_ops"

after="$(sha256sum "$fixture/state")"
readonly after
[[ "$before" == "$after" ]]
/usr/bin/printf 'helper validation and profile protocol corpus passed; fixture state unchanged\n'
