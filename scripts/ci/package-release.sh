#!/usr/bin/env bash
set -euo pipefail

source_root=${1:?usage: package-release.sh SOURCE_ROOT REVISION OUTPUT_TAR_GZ}
revision=${2:?usage: package-release.sh SOURCE_ROOT REVISION OUTPUT_TAR_GZ}
archive=${3:?usage: package-release.sh SOURCE_ROOT REVISION OUTPUT_TAR_GZ}
archive=$(realpath -m -- "$archive")
[[ $revision =~ ^[0-9a-f]{40}$ ]] || { echo 'Expected a full commit SHA' >&2; exit 1; }

shopt -s nullglob
jars=("$source_root"/backend/build/libs/*.jar)
runtime_jars=()
for jar in "${jars[@]}"; do
  [[ $jar == *-plain.jar ]] || runtime_jars+=("$jar")
done
[[ ${#runtime_jars[@]} -eq 1 && -f ${runtime_jars[0]} ]] || { echo 'Expected exactly one bootJar' >&2; exit 1; }
[[ -f "$source_root/frontend/.next/standalone/server.js" && -d "$source_root/frontend/.next/static" ]] || {
  echo 'Missing standalone frontend build or static assets' >&2; exit 1;
}
source_paths=("$source_root/frontend/.next/standalone" "$source_root/frontend/.next/static")
[[ ! -e "$source_root/frontend/public" ]] || source_paths+=("$source_root/frontend/public")
if [[ -n $(find "${source_paths[@]}" -type l -print -quit) ]]; then
  echo 'Refusing to package symlinks' >&2
  exit 1
fi

stage=$(mktemp -d)
trap 'rm -rf -- "$stage"' EXIT
cp -- "${runtime_jars[0]}" "$stage/backend.jar"
cp -a -- "$source_root/frontend/.next/standalone" "$stage/frontend"
mkdir -p -- "$stage/frontend/.next"
cp -a -- "$source_root/frontend/.next/static" "$stage/frontend/.next/static"
if [[ -d "$source_root/frontend/public" ]]; then
  mkdir -p -- "$stage/frontend/public"
  cp -a -- "$source_root/frontend/public/." "$stage/frontend/public/"
else
  mkdir -p -- "$stage/frontend/public"
fi
printf '%s\n' "$revision" > "$stage/REVISION"
printf '%s\n' "$revision" > "$stage/frontend/public/release.txt"

if [[ -n $(find "$stage" -type l -print -quit) || -n $(find "$stage" -type f -name '.env*' -print -quit) ]]; then
  echo 'Refusing to package symlinks or environment files' >&2
  exit 1
fi
(
  cd "$stage"
  find backend.jar REVISION frontend -type f -print0 | LC_ALL=C sort -z | xargs -0 sha256sum > SHA256SUMS
  tar -czf "$archive" backend.jar frontend REVISION SHA256SUMS
)
