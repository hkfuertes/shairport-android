#!/bin/sh
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
# shellcheck source=../upstream.env
. "$root/upstream.env"
out="$root/third_party"

[ ! -e "$out/shairport-sync" ] && [ ! -e "$out/nqptp" ] || {
  echo "third_party already exists; run make clean first" >&2
  exit 1
}

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$out"

fetch() {
  name=$1 commit=$2 checksum=$3
  archive="$tmp/$name.tar.gz"
  curl -fsSL "https://github.com/mikebrady/$name/archive/$commit.tar.gz" -o "$archive"
  printf '%s  %s\n' "$checksum" "$archive" | sha256sum -c -
  tar -xzf "$archive" -C "$tmp"
  set -- "$tmp/$name-$commit"
  [ "$#" -eq 1 ] && [ -d "$1" ]
  mv "$1" "$out/$name"
}

fetch shairport-sync "$SHAIRPORT_SYNC_COMMIT" "$SHAIRPORT_SYNC_SHA256"
fetch nqptp "$NQPTP_COMMIT" "$NQPTP_SHA256"
