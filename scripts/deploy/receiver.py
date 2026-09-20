#!/usr/bin/env python3
import fcntl
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import tarfile
import tempfile
import time
import uuid
from urllib.request import urlopen


CONFIG = Path('/etc/klepaas/deploy.json')
SHA = re.compile(r'[0-9a-f]{40}\Z')
UNIT = re.compile(r'[a-zA-Z0-9_.@-]+\.service\Z')
MAX_ARCHIVE = 500 * 1024 * 1024
MAX_FILE = 512 * 1024 * 1024
MAX_CONTENT = 2 * 1024 * 1024 * 1024
MAX_ENTRIES = 100000


def command(value):
    match = re.fullmatch(r'deploy ([0-9a-f]{40})', value)
    if not match:
        raise ValueError('expected: deploy <40-character lowercase SHA>')
    return match.group(1)


def trusted_config(path=CONFIG):
    stat = path.stat()
    if path.is_symlink() or stat.st_uid != 0 or stat.st_mode & 0o022:
        raise ValueError('deployment config must be root-owned and not writable by others')
    cfg = json.loads(path.read_text())
    if set(cfg) != {'release_base', 'backend_unit', 'frontend_unit', 'backend_url', 'frontend_url'}:
        raise ValueError('unexpected deployment config fields')
    base = Path(cfg['release_base'])
    if not base.is_absolute() or base.is_symlink() or not base.is_dir():
        raise ValueError('release_base must be an existing real directory')
    stat = base.stat()
    if stat.st_uid != 0 or stat.st_mode & 0o022:
        raise ValueError('release_base must be root-owned and not writable by others')
    for key in ('backend_unit', 'frontend_unit'):
        if not UNIT.fullmatch(cfg[key]):
            raise ValueError('invalid systemd unit')
    if cfg['backend_unit'] == cfg['frontend_unit']:
        raise ValueError('distinct systemd units required')
    for key in ('backend_url', 'frontend_url'):
        from urllib.parse import urlsplit
        url = urlsplit(cfg[key])
        if url.scheme != 'http' or url.hostname not in ('localhost', '127.0.0.1', '::1') or not url.port or url.username or url.password or url.query or url.fragment or url.path not in ('', '/'):
            raise ValueError('health URLs must be loopback HTTP origins')
    return cfg


def valid_name(name):
    parts = name.rstrip('/').split('/')
    if (not name or len(name) > 1024 or name.startswith('/') or
            any(p in ('', '.', '..') or p.startswith('.env') for p in parts) or
            any(ord(c) < 32 or c == '\\' for c in name)):
        raise ValueError('unsafe archive path')
    if parts[0] not in ('backend.jar', 'REVISION', 'SHA256SUMS', 'frontend'):
        raise ValueError('unexpected archive path')
    if parts[0] != 'frontend' and len(parts) != 1:
        raise ValueError('unexpected archive path')
    return '/'.join(parts)


def stage(archive, sha, directory):
    names, files = set(), set()
    total = 0
    class Limited:
        def __init__(self, source):
            self.source = source
            self.count = 0

        def read(self, size):
            data = self.source.read(size)
            self.count += len(data)
            if self.count > MAX_CONTENT + 128 * 1024 * 1024:
                raise ValueError('archive expands beyond limit')
            return data

    with gzip.GzipFile(fileobj=archive) as source, tarfile.open(fileobj=Limited(source), mode='r|') as tar:
        for count, member in enumerate(tar, 1):
            if count > MAX_ENTRIES:
                raise ValueError('too many archive entries')
            name = valid_name(member.name)
            if name in names:
                raise ValueError('duplicate archive target')
            names.add(name)
            target = directory / name
            if member.isdir():
                if name != 'frontend' and not name.startswith('frontend/'):
                    raise ValueError('unexpected directory')
                target.mkdir(parents=True, exist_ok=True)
                continue
            if not member.isfile():
                raise ValueError('links and special files are forbidden')
            if name == 'frontend' or member.size < 0 or member.size > MAX_FILE or (name in ('REVISION', 'frontend/public/release.txt') and member.size > 100) or (name == 'SHA256SUMS' and member.size > 12 * 1024 * 1024):
                raise ValueError('invalid archive file')
            total += member.size
            if total > MAX_CONTENT:
                raise ValueError('archive expands beyond limit')
            target.parent.mkdir(parents=True, exist_ok=True)
            stream = tar.extractfile(member)
            if stream is None:
                raise ValueError('missing archive file body')
            with target.open('xb') as output:
                shutil.copyfileobj(stream, output)
            target.chmod(0o644)
            files.add(name)
    required = {'backend.jar', 'frontend/server.js', 'frontend/public/release.txt', 'REVISION', 'SHA256SUMS'}
    if not required <= files:
        raise ValueError('missing required release files')
    if (directory / 'REVISION').read_bytes() != (sha + '\n').encode():
        raise ValueError('REVISION does not match requested SHA')
    if (directory / 'frontend/public/release.txt').read_bytes() != (sha + '\n').encode():
        raise ValueError('frontend release marker does not match requested SHA')
    manifest = (directory / 'SHA256SUMS').read_text(encoding='ascii').splitlines()
    expected = {}
    for line in manifest:
        match = re.fullmatch(r'([0-9a-f]{64})  (.+)', line)
        if not match or match.group(2) in expected:
            raise ValueError('invalid or duplicate checksum entry')
        expected[match.group(2)] = match.group(1)
    if set(expected) != files - {'SHA256SUMS'}:
        raise ValueError('checksum manifest does not cover exactly the archive files')
    for name, digest in expected.items():
        hasher = hashlib.sha256()
        with (directory / name).open('rb') as source:
            for block in iter(lambda: source.read(1024 * 1024), b''):
                hasher.update(block)
        if hasher.hexdigest() != digest:
            raise ValueError('checksum mismatch: ' + name)


def atomic_file(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(prefix='.deploy-', dir=path.parent)
    try:
        with os.fdopen(fd, 'w') as output:
            output.write(data)
        os.chmod(tmp, 0o644)
        os.replace(tmp, path)
    finally:
        if os.path.lexists(tmp):
            os.unlink(tmp)


def atomic_link(path, target):
    temp = path.parent / ('.current-' + uuid.uuid4().hex)
    try:
        os.symlink(target, temp)
        os.replace(temp, path)
    finally:
        if os.path.lexists(temp):
            os.unlink(temp)


def systemctl(*args):
    subprocess.run(('systemctl', *args), check=True, timeout=45, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def probe(url):
    with urlopen(url, timeout=3) as response:
        if response.status != 200:
            raise ValueError('health endpoint returned non-200')
        return response.headers.get('Content-Type', ''), response.read(1024 * 1024)


def healthy(cfg, sha):
    backend, frontend = cfg['backend_url'].rstrip('/'), cfg['frontend_url'].rstrip('/')
    if sha:
        probe(backend + '/api/v1/system/ready')
        _, version = probe(backend + '/api/v1/system/version')
        if json.loads(version)['data']['revision'] != sha:
            raise ValueError('backend revision mismatch')
        if probe(frontend + '/console/release.txt')[1] != (sha + '\n').encode():
            raise ValueError('frontend revision mismatch')
    else:
        probe(backend + '/api/v1/system/health')
    content_type, html = probe(frontend + '/console')
    if 'text/html' not in content_type.lower() or b'<html' not in html.lower():
        raise ValueError('frontend is not serving HTML')


def await_healthy(cfg, sha, seconds=60):
    deadline = time.monotonic() + seconds
    while True:
        try:
            healthy(cfg, sha)
            return
        except (OSError, ValueError, KeyError, json.JSONDecodeError) as exc:
            if time.monotonic() >= deadline:
                raise RuntimeError('release health check failed') from exc
            time.sleep(1)


def overrides(cfg, base):
    systemd = Path(cfg.get('systemd_dir', '/etc/systemd/system'))
    backend = systemd / (cfg['backend_unit'] + '.d/90-klepaas-release.conf')
    frontend = systemd / (cfg['frontend_unit'] + '.d/90-klepaas-release.conf')
    return {
        backend: '[Service]\nNoNewPrivileges=true\nExecStart=\nExecStart=/usr/bin/java -jar ' + str(base / 'current/backend.jar') + ' --spring.jpa.hibernate.ddl-auto=validate\n',
        frontend: '[Service]\nNoNewPrivileges=true\nExecStart=\nExecStart=/usr/bin/node ' + str(base / 'current/frontend/server.js') + '\nWorkingDirectory=' + str(base / 'current/frontend') + '\n',
    }


def deploy(cfg, sha, archive):
    base = Path(cfg['release_base'])
    releases = base / 'releases'
    releases.mkdir(mode=0o755, exist_ok=True)
    with (base / '.deploy.lock').open('a') as lock:
        deadline = time.monotonic() + 120
        while True:
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                break
            except BlockingIOError:
                if time.monotonic() >= deadline:
                    raise TimeoutError('deployment lock timed out')
                time.sleep(0.25)
        with tempfile.TemporaryDirectory(prefix='.stage-', dir=releases) as staging:
            staged = Path(staging)
            stage(archive, sha, staged)
            release = releases / sha
            if release.exists():
                if release.is_symlink() or (release / 'SHA256SUMS').read_bytes() != (staged / 'SHA256SUMS').read_bytes():
                    raise ValueError('release SHA already exists with different contents')
            else:
                os.chmod(staged, 0o755)
                os.replace(staged, release)
        current = base / 'current'
        if os.path.lexists(current) and not current.is_symlink():
            raise ValueError('current must be a managed symlink')
        previous = os.readlink(current) if current.is_symlink() else None
        if previous and (not SHA.fullmatch(Path(previous).name) or Path(previous) != releases / Path(previous).name or not Path(previous).is_dir()):
            raise ValueError('current points outside managed releases')
        target = str(release)
        if previous == target:
            await_healthy(cfg, sha)
            print('already deployed ' + sha)
            return
        paths = overrides(cfg, base)
        originals = {path: path.read_text() if path.exists() else None for path in paths}
        if previous and any(originals[path] != contents for path, contents in paths.items()):
            raise ValueError('existing service overrides are not managed by this receiver')
        if not previous and any(value is not None for value in originals.values()):
            raise ValueError('legacy bootstrap requires clean service overrides')
        try:
            for path, contents in paths.items():
                atomic_file(path, contents)
            atomic_link(current, target)
            systemctl('daemon-reload')
            systemctl('restart', cfg['backend_unit'])
            systemctl('restart', cfg['frontend_unit'])
            await_healthy(cfg, sha)
        except Exception:
            for path, contents in originals.items():
                if contents is None:
                    path.unlink(missing_ok=True)
                else:
                    atomic_file(path, contents)
            if previous:
                atomic_link(current, previous)
            else:
                current.unlink(missing_ok=True)
            systemctl('daemon-reload')
            systemctl('restart', cfg['backend_unit'])
            systemctl('restart', cfg['frontend_unit'])
            await_healthy(cfg, Path(previous).name if previous else None)
            raise
        print('deployed ' + sha)


def main():
    try:
        def interrupted(signum, frame):
            raise TimeoutError('deployment interrupted or upload timed out')

        signal.signal(signal.SIGTERM, interrupted)
        signal.signal(signal.SIGHUP, interrupted)
        signal.signal(signal.SIGALRM, interrupted)
        if os.geteuid() != 0:
            raise ValueError('receiver must run as root via restricted sudo')
        if len(sys.argv) != 2:
            raise ValueError('receiver takes one forced-command argument')
        sha = command(sys.argv[1])
        cfg = trusted_config()
        os.umask(0o022)
        with tempfile.TemporaryFile() as archive:
            size = 0
            signal.alarm(600)
            try:
                while chunk := sys.stdin.buffer.read(1024 * 1024):
                    size += len(chunk)
                    if size > MAX_ARCHIVE:
                        raise ValueError('compressed archive exceeds limit')
                    archive.write(chunk)
            finally:
                signal.alarm(0)
            archive.seek(0)
            deploy(cfg, sha, archive)
    except Exception as exc:
        print('deploy failed: ' + str(exc), file=sys.stderr)
        sys.exit(1)


if __name__ == '__main__':
    main()
