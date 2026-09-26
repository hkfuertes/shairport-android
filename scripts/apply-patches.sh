#!/bin/sh
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
shairport="$root/third_party/shairport-sync"
nqptp="$root/third_party/nqptp"
[ -d "$shairport" ] && [ -d "$nqptp" ] || {
  echo "missing sources; run make fetch first" >&2
  exit 1
}

apply() {
  tree=$1 patch_file=$2
  printf 'apply %s\n' "$(basename "$patch_file")"
  patch -d "$tree" -p1 --forward < "$patch_file"
}

for patch_file in \
  "$root"/patches/shairport-sync/android/*.patch \
  "$root"/patches/shairport-sync/0001-*.patch \
  "$root"/patches/shairport-sync/0002-*.patch \
  "$root"/patches/shairport-sync/0003-*.patch \
  "$root"/patches/shairport-sync/0004-*.patch; do
  apply "$shairport" "$patch_file"
done
for patch_file in "$root"/patches/nqptp/*.patch; do
  apply "$nqptp" "$patch_file"
done
