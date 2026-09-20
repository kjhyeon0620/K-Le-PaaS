#!/usr/bin/env bash
set -euo pipefail
repo_root=$(cd "$(dirname "$0")/../.." && pwd)
archive=${1:?usage: test-package-release.sh SAMPLE_TAR_GZ}
scratch=$(mktemp -d)
trap 'rm -rf -- "$scratch"' EXIT
mkdir -p "$scratch/source/backend/build/libs" "$scratch/source/frontend/.next/standalone/.next/server" "$scratch/source/frontend/.next/static" "$scratch/source/frontend/public" "$scratch/extracted"
printf 'jar' > "$scratch/source/backend/build/libs/backend.jar"
printf 'server' > "$scratch/source/frontend/.next/standalone/server.js"
printf 'runtime' > "$scratch/source/frontend/.next/standalone/.next/server/app.js"
printf 'static' > "$scratch/source/frontend/.next/static/app.js"
printf 'public' > "$scratch/source/frontend/public/index.txt"
revision=0123456789abcdef0123456789abcdef01234567
bash "$repo_root/scripts/ci/package-release.sh" "$scratch/source" "$revision" "$archive"
tar -xzf "$archive" -C "$scratch/extracted"
(
  cd "$scratch/extracted"
  sha256sum -c SHA256SUMS
  test "$(cat REVISION)" = "$revision"
  test "$(cat frontend/public/release.txt)" = "$revision"
  test -f frontend/server.js
  test -f frontend/.next/server/app.js
  test -f frontend/.next/static/app.js
  test -f frontend/public/index.txt
  test -z "$(find . -type l -print -quit)"
)
ln -s /etc/passwd "$scratch/source/frontend/.next/standalone/untrusted"
if bash "$repo_root/scripts/ci/package-release.sh" "$scratch/source" "$revision" "$scratch/forbidden.tar.gz"; then
  echo 'Symlink escaped the packaging guard' >&2
  exit 1
fi
unlink "$scratch/source/frontend/.next/standalone/untrusted"
printf 'secret' > "$scratch/source/frontend/.next/standalone/.env.production"
if bash "$repo_root/scripts/ci/package-release.sh" "$scratch/source" "$revision" "$scratch/forbidden.tar.gz"; then
  echo 'Environment file escaped the packaging guard' >&2
  exit 1
fi
