#!/usr/bin/env bash
set -euo pipefail

release_root=''
payload=''
deployer=''
temporary_root=''
temporary_user=''
temporary_home=''
temporary_user_created=0
temporary_sudoers=''
temporary_sudoers_created=0
temporary_sshd_config=''
temporary_sshd_config_created=0
temporary_helper=''
temporary_helper_created=0
deploy_authorized_keys='/var/lib/maxiems-deploy/.ssh/authorized_keys'
deploy_authorized_keys_backup=''
deploy_authorized_keys_existed=0
deploy_authorized_keys_touched=0

die() {
	printf '%s\n' "$*" >&2
	exit 64
}

reject_payload_override() {
	local argument
	for argument in "$@"; do
		case "$argument" in
		--payload|--payload=*) die 'install.sh owns the verified sibling payload.zip; do not pass --payload.' ;;
		esac
	done
}

reject_local_connection_override() {
	local argument
	for argument in "$@"; do
		case "$argument" in
		--host|--host=*|--port|--port=*|--user|--user=*|--identity|--identity=*|--local-bootstrap)
			die 'local mode owns its loopback host, user, port and temporary identity.' ;;
		--require-reboot)
			die 'local mode cannot use --require-reboot because the installer must clean its temporary identity.' ;;
		esac
	done
}

verify_release() {
	release_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
	payload="$release_root/payload.zip"
	deployer="$release_root/maxiems-deployer"
	local checksums="$release_root/SHA256SUMS"
	local bootstrap_helper="$release_root/maxiems-local-bootstrap-helper"
	[[ -f "$payload" && ! -L "$payload" ]] || die "Missing regular sibling payload: $payload"
	[[ -x "$deployer" && ! -L "$deployer" ]] || die "Missing executable sibling deployer: $deployer"
	[[ -f "$checksums" && ! -L "$checksums" ]] || die "Missing regular sibling checksums: $checksums"
	[[ -x "$bootstrap_helper" && ! -L "$bootstrap_helper" ]] \
		|| die "Missing executable local bootstrap helper: $bootstrap_helper"
	(cd -- "$release_root" && /usr/bin/sha256sum --check --strict SHA256SUMS)
}

cleanup_local_bootstrap() {
	local status=$?
	set +e
	if (( deploy_authorized_keys_touched )); then
		if (( deploy_authorized_keys_existed )) && [[ -f "$deploy_authorized_keys_backup" ]]; then
			/usr/bin/rm -f -- "$deploy_authorized_keys"
			/usr/bin/cp --preserve=all -- "$deploy_authorized_keys_backup" "$deploy_authorized_keys"
		else
			/usr/bin/rm -f -- "$deploy_authorized_keys"
		fi
	fi
	(( temporary_sudoers_created == 0 )) || /usr/bin/rm -f -- "$temporary_sudoers"
	if (( temporary_sshd_config_created )); then
		/usr/bin/rm -f -- "$temporary_sshd_config"
		if /usr/bin/systemctl is-active --quiet ssh.service; then
			/usr/bin/systemctl reload ssh.service >/dev/null 2>&1 || true
		fi
	fi
	(( temporary_helper_created == 0 )) || /usr/bin/rm -f -- "$temporary_helper"
	if (( temporary_user_created )) && [[ -n "$temporary_user" && -n "$temporary_home" ]]; then
		local record
		record=$(/usr/bin/getent passwd "$temporary_user" 2>/dev/null || true)
		if [[ "$record" == "$temporary_user:"*":$temporary_home:"* ]]; then
			/usr/sbin/userdel --remove "$temporary_user" >/dev/null 2>&1 || true
		fi
	fi
	[[ -z "$temporary_root" ]] || /usr/bin/rm -rf -- "$temporary_root"
	return "$status"
}

extract_offline_packages() {
	local architecture="$1"
	local destination="$2"
	local prefix="prerequisites/ubuntu-24.04/$architecture/"
	if [[ -x /usr/bin/python3 ]]; then
		/usr/bin/python3 - "$payload" "$prefix" "$destination" <<'PY'
import pathlib
import sys
import zipfile

archive_path, prefix, destination = sys.argv[1:]
root = pathlib.Path(destination)
with zipfile.ZipFile(archive_path) as archive:
    names = [name for name in archive.namelist()
             if name.startswith(prefix) and name.endswith('.deb') and '/' not in name[len(prefix):]]
    if not names:
        raise SystemExit('Verified payload has no offline packages for this architecture.')
    for name in names:
        target = root / pathlib.PurePosixPath(name).name
        target.write_bytes(archive.read(name))
PY
	elif [[ -x /usr/bin/unzip ]]; then
		/usr/bin/unzip -qq -j -- "$payload" "${prefix}*.deb" -d "$destination"
	else
		die 'Local bootstrap needs python3 or unzip to read verified offline packages.'
	fi
}

install_local_ssh_prerequisites() {
	if [[ -x /usr/bin/sudo && -x /usr/sbin/sshd && -x /usr/bin/ssh-keygen && -x /usr/sbin/visudo ]]; then
		return
	fi
	[[ -x /usr/bin/apt-get && -x /usr/bin/dpkg-deb && -x /usr/bin/dpkg ]] \
		|| die 'Local bootstrap requires apt-get and dpkg.'
	local architecture
	architecture=$(/usr/bin/dpkg --print-architecture)
	[[ "$architecture" == amd64 || "$architecture" == arm64 ]] || die "Unsupported architecture: $architecture"
	local repository="$temporary_root/packages"
	/usr/bin/install -d -m 0700 -- "$repository/lists/partial" "$repository/cache/partial"
	extract_offline_packages "$architecture" "$repository"
	local package
	: > "$repository/Packages"
	for package in "$repository"/*.deb; do
		/usr/bin/dpkg-deb -f "$package" >> "$repository/Packages"
		printf 'Filename: %s\nSize: %s\nSHA256: %s\n\n' \
			"${package##*/}" "$(/usr/bin/stat -c %s "$package")" \
			"$(/usr/bin/sha256sum "$package" | /usr/bin/cut -d' ' -f1)" >> "$repository/Packages"
	done
	printf 'deb [trusted=yes] file:%s ./\n' "$repository" > "$repository/offline.list"
	local apt_options=(-o DPkg::Lock::Timeout=120 -o APT::Sandbox::User=root
		-o "Dir::Etc::sourcelist=$repository/offline.list" -o Dir::Etc::sourceparts=-
		-o "Dir::State::lists=$repository/lists" -o "Dir::Cache::archives=$repository/cache")
	/usr/bin/apt-get "${apt_options[@]}" update
	DEBIAN_FRONTEND=noninteractive /usr/bin/apt-get "${apt_options[@]}" --yes --no-upgrade \
		-o Dpkg::Options::=--force-confold install sudo openssh-server openssh-client
	[[ -x /usr/bin/sudo && -x /usr/sbin/sshd && -x /usr/bin/ssh-keygen && -x /usr/sbin/visudo ]] \
		|| die 'Offline bootstrap did not install sudo and OpenSSH successfully.'
}

create_local_bootstrap() {
	[[ ${EUID:-$(/usr/bin/id -u)} == 0 ]] || die 'local mode must run as root (use sudo).'
	temporary_root=$(/usr/bin/mktemp -d /run/maxiems-local-install.XXXXXXXX)
	/usr/bin/chmod 0700 "$temporary_root"
	install_local_ssh_prerequisites
	local token
	token=$(/usr/bin/tr -d -- '-' < /proc/sys/kernel/random/uuid)
	temporary_user="mxlocal-${token:0:12}"
	temporary_home="/run/maxiems-local-bootstrap/$temporary_user"
	/usr/bin/getent passwd "$temporary_user" >/dev/null && die 'Temporary bootstrap user collision.'
	/usr/sbin/useradd --system --user-group --home-dir "$temporary_home" --create-home --shell /bin/bash "$temporary_user"
	temporary_user_created=1
	local temporary_password
	temporary_password="$(/usr/bin/tr -d -- '-' < /proc/sys/kernel/random/uuid)$(/usr/bin/tr -d -- '-' < /proc/sys/kernel/random/uuid)"
	printf '%s:%s\n' "$temporary_user" "$temporary_password" | /usr/sbin/chpasswd
	unset temporary_password
	/usr/bin/ssh-keygen -q -t ed25519 -N '' -C maxiems-local-bootstrap -f "$temporary_root/deploy-key"
	/usr/bin/install -d -o "$temporary_user" -g "$temporary_user" -m 0700 "$temporary_home/.ssh"
	{
		printf 'from="127.0.0.1,::1",restrict '
		/usr/bin/cat "$temporary_root/deploy-key.pub"
	} > "$temporary_root/authorized_keys"
	/usr/bin/install -o "$temporary_user" -g "$temporary_user" -m 0600 \
		"$temporary_root/authorized_keys" "$temporary_home/.ssh/authorized_keys"
	temporary_sudoers="/etc/sudoers.d/maxiems-local-${token:0:12}"
	temporary_helper='/run/maxiems-local-bootstrap-helper'
	[[ ! -e "$temporary_helper" ]] || die "Refusing existing local bootstrap helper: $temporary_helper"
	[[ ! -e "$temporary_sudoers" ]] || die "Refusing existing local bootstrap sudoers: $temporary_sudoers"
	/usr/bin/install -o root -g root -m 0700 \
		"$release_root/maxiems-local-bootstrap-helper" "$temporary_helper"
	temporary_helper_created=1
	{
		printf 'Defaults:%s secure_path=/usr/sbin:/usr/bin:/sbin:/bin\n' "$temporary_user"
		printf '%s ALL=(root) NOPASSWD: /usr/bin/true, ' "$temporary_user"
		printf '/run/maxiems-local-bootstrap-helper install-layout '
		printf '/tmp/maxiems-bootstrap-????????????????????????????????\n'
	} > "$temporary_root/sudoers"
	/usr/bin/install -o root -g root -m 0440 "$temporary_root/sudoers" "$temporary_sudoers"
	temporary_sudoers_created=1
	/usr/sbin/visudo -cf "$temporary_sudoers" >/dev/null
	temporary_sshd_config="/etc/ssh/sshd_config.d/99-maxiems-local-${token:0:12}.conf"
	[[ ! -e "$temporary_sshd_config" ]] || die "Refusing existing local bootstrap SSH config: $temporary_sshd_config"
	{
		printf 'Match User %s\n' "$temporary_user"
		printf '    AuthenticationMethods publickey\n'
		printf '    PubkeyAuthentication yes\n'
		printf '    PasswordAuthentication no\n'
		printf '    KbdInteractiveAuthentication no\n'
		printf 'Match all\n'
	} > "$temporary_root/sshd-config"
	/usr/bin/install -o root -g root -m 0600 "$temporary_root/sshd-config" "$temporary_sshd_config"
	temporary_sshd_config_created=1
	/usr/bin/install -d -o root -g root -m 0755 /run/sshd
	/usr/sbin/sshd -t
	/usr/bin/systemctl enable --now ssh.service
	/usr/bin/systemctl reload ssh.service
	deploy_authorized_keys_backup="$temporary_root/maxiems-deploy.authorized_keys.before"
	if [[ -L "$deploy_authorized_keys" ]]; then
		die 'Refusing symbolic maxiems-deploy authorized_keys.'
	elif [[ -f "$deploy_authorized_keys" ]]; then
		/usr/bin/cp --preserve=all -- "$deploy_authorized_keys" "$deploy_authorized_keys_backup"
		deploy_authorized_keys_existed=1
	fi
	deploy_authorized_keys_touched=1
}

run_local() {
	reject_local_connection_override "$@"
	trap cleanup_local_bootstrap EXIT
	trap 'exit 129' HUP
	trap 'exit 130' INT
	trap 'exit 143' TERM
	create_local_bootstrap
	"$deployer" deploy --host 127.0.0.1 --user "$temporary_user" \
		--identity "$temporary_root/deploy-key" --payload "$payload" --local-bootstrap "$@"
}

main() {
	(( $# >= 1 )) || die 'Usage: install.sh local|ssh [deployment options]'
	local mode="$1"
	shift
	reject_payload_override "$@"
	verify_release
	case "$mode" in
	ssh) exec "$deployer" deploy --payload "$payload" "$@" ;;
	local) run_local "$@" ;;
	*) die 'Usage: install.sh local|ssh [deployment options]' ;;
	esac
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
	main "$@"
fi
