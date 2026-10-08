#!/usr/bin/env python3
"""Đóng gói BMSApp + UI có sẵn + dependency Ubuntu offline; không giải nén JAR."""
import argparse
import datetime as dt
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parent.parent
BMS_JAR = REPO / 'platform/applications/io.openems.edge.application/generated/distributions/executable/BMSApp.jar'
REQUIRED_PACKAGES = {'openjdk-21-jre-headless', 'nginx', 'postgresql', 'python3',
                     'network-manager', 'jq', 'unzip', 'curl', 'ca-certificates', 'sudo',
                     'openssh-server', 'iproute2', 'procps', 'util-linux'}


def sha256(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def tree(root, prefix):
    if not root.is_dir() or root.is_symlink():
        raise ValueError(f'Missing or symlink directory: {root}')
    result = {}
    for path in sorted(root.rglob('*')):
        if path.is_symlink():
            raise ValueError(f'Symlink forbidden: {path}')
        if path.is_file():
            if '__pycache__' in path.parts or path.suffix == '.pyc':
                continue
            if not path.stat().st_size:
                raise ValueError(f'Empty file: {path}')
            result[f'{prefix}/{path.relative_to(root).as_posix()}'] = path
    return result


def package_inventory(directory, architecture):
    """Check actual deb control metadata, not filenames supplied by operator."""
    names = set()
    files = sorted(directory.glob('*.deb'))
    if not files:
        raise ValueError(f'Missing offline .deb packages: {directory}')
    for path in files:
        fields = subprocess.check_output(['dpkg-deb', '-f', str(path), 'Package', 'Architecture'], text=True)
        values = dict(line.split(': ', 1) for line in fields.splitlines())
        if values['Architecture'] not in ('all', architecture):
            raise ValueError(f'Wrong architecture: {path.name}')
        if values['Package'] in names:
            raise ValueError(f'Multiple versions of package: {values["Package"]}')
        names.add(values['Package'])
    missing = REQUIRED_PACKAGES - names
    if missing:
        raise ValueError(f'Incomplete offline packages for {architecture}: {sorted(missing)}')
    return files


def bms_ui_profile(ui):
    """Return and validate the profile that becomes the active appliance config."""
    profile = ui / 'config/device-config.bms.example.json'
    if not profile.is_file() or profile.is_symlink():
        raise ValueError('UI dist must contain config/device-config.bms.example.json')
    config = json.loads(profile.read_text())
    if config.get('device', {}).get('product') != 'bms':
        raise ValueError('BMS UI profile must set device.product to bms')
    enabled = config.get('navigation', {}).get('enabledItems', [])
    if 'ntp-server' not in enabled or 'serial-tcp' not in enabled or 'modbus-server' not in enabled:
        raise ValueError('BMS UI profile must enable NTP, Serial & TCP and Modbus Server pages')
    if config.get('connectivity', {}).get('tcp') is not True:
        raise ValueError('BMS UI profile must enable TCP configuration')
    return profile


def utc_timestamp(value, label):
    stamp = dt.datetime.fromisoformat(value.replace('Z', '+00:00'))
    if stamp.tzinfo is None:
        raise ValueError(f'{label} must include timezone')
    return stamp.astimezone(dt.timezone.utc)


def build(jar, ui, packages, architectures, output, revision, source_timestamp, build_timestamp):
    if not re.fullmatch(r'[0-9a-f]{40}', revision):
        raise ValueError('Git revision must be a full SHA-1 commit ID')
    if jar.is_symlink() or not jar.is_file():
        raise ValueError('BMSApp JAR must be a regular file')
    # Only the ZIP directory is read. Embedded bundles are never extracted.
    with zipfile.ZipFile(jar) as archive:
        required = {'jar/io.openems.edge.bms.service.jar', 'jar/io.openems.edge.bms.api.jar',
                    'jar/io.openems.edge.shared.postgresql.jar', 'jar/io.openems.edge.controller.api.rest.jar'}
        if not required.issubset(archive.namelist()):
            raise ValueError('Export BMSApp again: required BMS/database/management REST bundles missing')
        with zipfile.ZipFile(io.BytesIO(archive.read('jar/io.openems.edge.bms.api.jar'))) as api:
            api_classes = {'io/openems/edge/bms/endpoints/time/BmsTimeJsonApi.class',
                           'io/openems/edge/bms/endpoints/tcp/BmsTcpBridgeJsonApi.class',
                           'io/openems/edge/bms/endpoints/modbus/BmsModbusServerJsonApi.class'}
            if not api_classes.issubset(api.namelist()):
                raise ValueError('Stale BMS API bundle: build service/API jars before exporting BMSApp')
    if not (ui / 'index.html').is_file():
        raise ValueError('UI dist must contain index.html')
    files = {'edge/openems.jar': jar}
    for directory, prefix in [(ui, 'ui'), (ROOT / 'payload/bootstrap', 'bootstrap'),
                              (ROOT / 'payload/components', 'components'),
                              (ROOT / 'remote/etc', 'remote/etc'), (ROOT / 'remote/usr', 'remote/usr')]:
        files.update(tree(directory, prefix))
    # The source dist may retain unified config for local development. Every BMS
    # appliance payload activates the reviewed BMS-only profile deterministically.
    files['ui/config/device-config.json'] = bms_ui_profile(ui)
    for architecture in architectures:
        directory = packages / architecture
        package_inventory(directory, architecture)
        files.update(tree(directory, f'prerequisites/ubuntu-24.04/{architecture}'))
    metadata = [{'path': name, 'length': path.stat().st_size, 'sha256': sha256(path)}
                for name, path in sorted(files.items())]
    if len(metadata) > 10000 or sum(f['length'] for f in metadata) > 4 * 1024**3:
        raise ValueError('Payload exceeds deployer limits')
    canonical = ''.join(f'{f["path"]}\0{f["length"]}\0{f["sha256"]}\n' for f in metadata)
    digest = hashlib.sha256(canonical.encode()).hexdigest()
    source_stamp = utc_timestamp(source_timestamp, 'Source timestamp')
    build_stamp = utc_timestamp(build_timestamp, 'Build timestamp')
    build_timestamp_utc = build_stamp.strftime('%Y-%m-%dT%H:%M:%SZ')
    release_id = build_stamp.strftime('%Y%m%d%H%M%S') + '-' + digest[:12]
    manifest = {'formatVersion': 2, 'releaseId': release_id, 'gitRevision': revision,
                'sourceTimestampUtc': source_stamp.strftime('%Y-%m-%dT%H:%M:%SZ'),
                'buildTimestampUtc': build_timestamp_utc,
                'sourcePaths': [{'kind': 'edge', 'path': str(BMS_JAR.relative_to(REPO))},
                                {'kind': 'ui', 'path': 'operator-supplied UI dist'}],
                'supportedTargets': {'ubuntuVersions': ['24.04'], 'architectures': architectures},
                'payloadHash': digest,
                'files': metadata}
    output.parent.mkdir(parents=True, exist_ok=True)
    checksum_output = output.with_suffix(output.suffix + '.sha256')
    release_info_output = output.with_suffix(output.suffix + '.manifest.json')
    for candidate in (output, checksum_output, release_info_output):
        if candidate.exists():
            raise ValueError(f'Output exists: {candidate}')
    fd, temporary = tempfile.mkstemp(prefix='.payload-', dir=output.parent)
    os.close(fd)
    try:
        with zipfile.ZipFile(temporary, 'w', zipfile.ZIP_DEFLATED) as archive:
            for item in metadata:
                info = zipfile.ZipInfo(item['path'], (1980, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = 0o100644 << 16
                with files[item['path']].open('rb') as source, archive.open(info, 'w') as target:
                    shutil.copyfileobj(source, target, length=1024*1024)
                if sha256(files[item['path']]) != item['sha256']:
                    raise ValueError(f'Source changed while packaging: {item["path"]}')
            archive.writestr(zipfile.ZipInfo('manifest.json', (1980, 1, 1, 0, 0, 0)),
                             json.dumps(manifest, indent=2) + '\n')
        if Path(temporary).stat().st_size > 2 * 1024**3:
            raise ValueError('Payload exceeds 2 GiB archive limit')
        os.replace(temporary, output)
    finally:
        Path(temporary).unlink(missing_ok=True)
    archive_hash = sha256(output)
    checksum_output.write_text(f'{archive_hash}  {output.name}\n')
    release_info = {
        'formatVersion': 2,
        'releaseId': release_id,
        'buildTimestampUtc': build_timestamp_utc,
        'sourceTimestampUtc': manifest['sourceTimestampUtc'],
        'gitRevision': revision,
        'payloadHash': digest,
        'payloadArchiveSha256': archive_hash,
        'payloadFile': output.name,
        'checksumFile': checksum_output.name,
    }
    release_info_output.write_text(json.dumps(release_info, indent=2) + '\n')
    return release_info


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, default=BMS_JAR)
    parser.add_argument('--ui-dist', type=Path, required=True)
    parser.add_argument('--packages', type=Path, required=True, help='Root containing amd64/ and/or arm64/ .deb files')
    parser.add_argument('--architecture', choices=['amd64', 'arm64'], action='append', required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--build-backend', action='store_true')
    args = parser.parse_args()
    if args.build_backend:
        subprocess.run(['bash', './gradlew', ':io.openems.edge.bms.service:jar',
                        ':io.openems.edge.bms.api:jar', '--offline', '--console=plain'], cwd=REPO, check=True)
        for task in ('resolve.BMSApp', 'export.BMSApp'):
            subprocess.run(['bash', './gradlew', task, '--rerun', '--offline', '--console=plain'], cwd=REPO, check=True)
    revision = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=REPO, text=True).strip()
    source_timestamp = subprocess.check_output(
        ['git', 'show', '-s', '--format=%cI', 'HEAD'], cwd=REPO, text=True).strip()
    build_timestamp = dt.datetime.now(dt.timezone.utc).isoformat()
    release_info = build(args.jar, args.ui_dist, args.packages, sorted(set(args.architecture)),
                         args.output, revision, source_timestamp, build_timestamp)
    print(json.dumps({'releaseId': release_info['releaseId'], 'output': str(args.output),
                      'payloadArchiveSha256': release_info['payloadArchiveSha256']}))


if __name__ == '__main__':
    main()
