#!/usr/bin/env python3
"""Reproduce the Linux cloud toolchain using verified upstream archives.
No source files, manifests or lockfiles are changed by this setup script.
"""
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path('/workspace/toolchains')
ROOT.mkdir(parents=True, exist_ok=True)


def fetch(url, path):
    subprocess.run(['curl', '--fail', '--location', '--silent', '--show-error', url, '-o', str(path)], check=True)


def verify(path, digest, algorithm='sha256'):
    h = hashlib.new(algorithm)
    with path.open('rb') as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b''):
            h.update(chunk)
    if h.hexdigest() != digest:
        raise RuntimeError(f'Checksum mismatch: {path.name}')


with tempfile.TemporaryDirectory(prefix='greek-android-') as temporary:
    tmp = Path(temporary)
    gradle = ROOT / 'gradle-8.11.1'
    if not gradle.exists():
        base = 'https://downloads.gradle.org/distributions/gradle-8.11.1-bin.zip'
        archive = tmp / 'gradle.zip'; checksum = tmp / 'gradle.sha256'
        fetch(base, archive); fetch(base + '.sha256', checksum)
        verify(archive, checksum.read_text().strip())
        with zipfile.ZipFile(archive) as z:
            z.extractall(ROOT)
        (gradle / 'bin/gradle').chmod(0o755)
    jdk = ROOT / 'jdk-21.0.13+9'
    if not jdk.exists():
        base = 'https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.13%2B9/OpenJDK21U-jdk_x64_linux_hotspot_21.0.13_9.tar.gz'
        archive = tmp / 'jdk.tar.gz'; checksum = tmp / 'jdk.sha256'
        fetch(base, archive); fetch(base + '.sha256.txt', checksum)
        verify(archive, checksum.read_text().split()[0])
        with tarfile.open(archive) as t:
            t.extractall(ROOT, filter='data')
    sdk = ROOT / 'android'
    sdk.mkdir(exist_ok=True)
    # Checksums are supplied by Google's HTTPS repository manifest, never guessed.
    manifest = tmp / 'repository.xml'
    fetch('https://dl.google.com/android/repository/repository2-1.xml', manifest)
    tree = ET.parse(manifest).getroot()
    packages = {'platforms;android-35': ('platform-35_r02.zip', 'platforms/android-35'),
                'build-tools;35.0.0': ('build-tools_r35_linux.zip', 'build-tools/35.0.0'),
                'platform-tools': (None, 'platform-tools')}
    for identity, (filename, destination) in packages.items():
        target = sdk / destination
        if target.exists():
            continue
        choices = []
        for package in tree.findall('remotePackage'):
            if package.attrib['path'] != identity:
                continue
            for archive in package.findall('./archives/archive'):
                url = archive.findtext('complete/url')
                if archive.findtext('host-os') in (None, 'linux') and (filename is None or url == filename):
                    choices.append((url, archive.findtext('complete/checksum')))
        if not choices:
            raise RuntimeError(f'Upstream package unavailable: {identity}')
        url, checksum = choices[0]
        archive = tmp / (identity.replace(';', '-') + '.zip')
        fetch('https://dl.google.com/android/repository/' + url, archive)
        verify(archive, checksum, 'sha1')
        unpacked = tmp / identity.replace(';', '-')
        with zipfile.ZipFile(archive) as z:
            z.extractall(unpacked)
        contents = list(unpacked.iterdir())
        if len(contents) != 1:
            raise RuntimeError('Unexpected Android archive structure')
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(str(contents[0]), target)
        for file in target.rglob('*'):
            if file.is_file() and (not file.suffix or file.name.endswith('.sh')):
                file.chmod(0o755)
    licenses = sdk / 'licenses'; licenses.mkdir(exist_ok=True)
    for license in tree.findall('license'):
        (licenses / license.attrib['id']).write_text(hashlib.sha1((license.text or '').encode()).hexdigest() + '\n')
    (ROOT / 'android-user').mkdir(exist_ok=True)
    (ROOT / 'java-user').mkdir(exist_ok=True)
print('Android cloud toolchain prepared under /workspace/toolchains')
