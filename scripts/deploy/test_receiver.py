import hashlib
from io import BytesIO
from pathlib import Path
import os
import tarfile
import tempfile
import unittest
from unittest.mock import patch

import receiver


def artifact(sha, extra=None, altered=None, corrupt=False):
    files = {
        'backend.jar': b'jar',
        'frontend/server.js': b'node server',
        'frontend/.next/BUILD_ID': b'build',
        'frontend/public/release.txt': (sha + '\n').encode(),
        'REVISION': (sha + '\n').encode(),
    }
    if altered:
        files.update(altered)
    files['SHA256SUMS'] = ''.join(
        hashlib.sha256(data).hexdigest() + '  ' + name + '\n'
        for name, data in sorted(files.items())
    ).encode()
    if corrupt:
        files['backend.jar'] = b'corrupted after checksum'
    output = BytesIO()
    with tarfile.open(fileobj=output, mode='w:gz') as tar:
        for name, data in files.items():
            info = tarfile.TarInfo(name)
            info.size = len(data)
            tar.addfile(info, BytesIO(data))
        if extra:
            name, kind = extra
            info = tarfile.TarInfo(name)
            info.type = kind
            if kind == tarfile.SYMTYPE:
                info.linkname = '/etc/passwd'
            tar.addfile(info)
    output.seek(0)
    return output


class Checks(unittest.TestCase):
    A = 'a' * 40
    B = 'b' * 40

    def test_rejects_untrusted_commands_and_archive(self):
        self.assertEqual(receiver.command('deploy ' + self.A), self.A)
        for value in ('deploy ' + self.A + '; id', 'deploy ' + self.A.upper(), 'deploy ' + self.A + '\n'):
            with self.subTest(command=value), self.assertRaises(ValueError):
                receiver.command(value)
        with tempfile.TemporaryDirectory() as temp:
            for extra in (('../outside', tarfile.REGTYPE), ('frontend/link', tarfile.SYMTYPE), ('frontend/.env.local', tarfile.REGTYPE), ('backend.jar', tarfile.REGTYPE)):
                with self.subTest(extra=extra), tempfile.TemporaryDirectory(dir=temp) as staging, self.assertRaises(ValueError):
                    receiver.stage(artifact(self.A, extra=extra), self.A, Path(staging))
            self.assertFalse((Path(temp).parent / 'outside').exists())
            for altered in ({'REVISION': (self.B + '\n').encode()}, {'SHA256SUMS': b''}, {'frontend/public/release.txt': b'wrong\n'}):
                with self.subTest(altered=altered), tempfile.TemporaryDirectory(dir=temp) as staging, self.assertRaises(ValueError):
                    receiver.stage(artifact(self.A, altered=altered), self.A, Path(staging))
            with tempfile.TemporaryDirectory(dir=temp) as staging, self.assertRaisesRegex(ValueError, 'checksum mismatch'):
                receiver.stage(artifact(self.A, corrupt=True), self.A, Path(staging))
            with patch.object(receiver, 'MAX_CONTENT', 10), tempfile.TemporaryDirectory(dir=temp) as staging, self.assertRaisesRegex(ValueError, 'expands beyond limit'):
                receiver.stage(artifact(self.A), self.A, Path(staging))

    def test_switch_noop_and_failed_health_restore_prior_release(self):
        with tempfile.TemporaryDirectory() as temp:
            base = Path(temp)
            unhealthy = set()
            failure = {'mode': None}
            class Response:
                status = 200

                def __init__(self, data, mime='text/plain'):
                    self.data = data
                    self.headers = {'Content-Type': mime}

                def __enter__(self):
                    return self

                def __exit__(self, *args):
                    pass

                def read(self, maximum):
                    return self.data[:maximum]

            def http(url, timeout):
                current = base / 'current'
                revision = Path(os.readlink(current)).name if current.is_symlink() else None
                if revision in unhealthy or (revision == Checks.B and failure['mode'] == 'ready'):
                    raise OSError('unhealthy revision')
                if url.endswith('/api/v1/system/version'):
                    if revision == Checks.B and failure['mode'] == 'version':
                        revision = Checks.A
                    return Response(('{"data":{"revision":"' + revision + '"}}').encode(), 'application/json')
                if url.endswith('/console/release.txt'):
                    if revision == Checks.B and failure['mode'] == 'frontend':
                        revision = Checks.A
                    return Response(((revision or '') + '\n').encode())
                if url.endswith('/console'):
                    if revision == Checks.B and failure['mode'] == 'html':
                        return Response(b'not html')
                    return Response(b'<html>ready</html>', 'text/html')
                return Response(b'ok')
            cfg = {
                'release_base': str(base), 'backend_unit': 'backend.service',
                'frontend_unit': 'frontend.service', 'systemd_dir': str(base / 'systemd'),
                'backend_url': 'http://127.0.0.1:8080',
                'frontend_url': 'http://127.0.0.1:3000',
            }
            calls = []
            with patch.object(receiver, 'systemctl', side_effect=lambda *args: calls.append(args)), patch.object(receiver, 'urlopen', side_effect=http), patch.object(receiver, 'await_healthy', side_effect=lambda c, sha: receiver.healthy(c, sha)):
                receiver.deploy(cfg, self.A, artifact(self.A))
                self.assertEqual(Path(os.readlink(base / 'current')).name, self.A)
                prior_calls = len(calls)
                receiver.deploy(cfg, self.A, artifact(self.A))
                self.assertEqual(len(calls), prior_calls)
                unhealthy.add(self.A)
                with self.assertRaises(OSError):
                    receiver.deploy(cfg, self.A, artifact(self.A))
                self.assertEqual(len(calls), prior_calls)
                unhealthy.remove(self.A)
                for mode in ('ready', 'version', 'frontend', 'html'):
                    with self.subTest(failure=mode):
                        failure['mode'] = mode
                        with self.assertRaises(Exception):
                            receiver.deploy(cfg, self.B, artifact(self.B))
                        self.assertEqual(Path(os.readlink(base / 'current')).name, self.A)
                        prior_calls += 6
                        self.assertEqual(len(calls), prior_calls)
                        receiver.healthy(cfg, self.A)
                        self.assertTrue(all(path.exists() for path in receiver.overrides(cfg, base)))

    def test_first_failed_release_restores_legacy_units(self):
        with tempfile.TemporaryDirectory() as temp:
            base = Path(temp)
            cfg = {
                'release_base': str(base), 'backend_unit': 'backend.service',
                'frontend_unit': 'frontend.service', 'systemd_dir': str(base / 'systemd'),
            }
            calls = []
            def check(_cfg, sha):
                calls.append(sha)
                if sha:
                    raise ValueError('unhealthy')
            with patch.object(receiver, 'systemctl'), patch.object(receiver, 'await_healthy', side_effect=check):
                with self.assertRaises(ValueError):
                    receiver.deploy(cfg, self.A, artifact(self.A))
            self.assertEqual(calls, [self.A, None])
            self.assertFalse((base / 'current').exists())
            self.assertTrue(all(not path.exists() for path in receiver.overrides(cfg, base)))


if __name__ == '__main__':
    unittest.main()
