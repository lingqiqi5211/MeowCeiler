#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
testdir=$(mktemp -d "${TMPDIR:-/tmp}/meow-api-lookup.XXXXXX")
cleanup() {
    rm -f "$testdir/api-lookup" "$testdir/import-lookup"
    rmdir "$testdir"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' HUP TERM
rustc --edition=2024 --deny warnings --test "$root/payload/tests/api_lookup.rs" -o "$testdir/api-lookup"
"$testdir/api-lookup"
rustc --edition=2024 --deny warnings --test "$root/payload/tests/import_lookup.rs" -o "$testdir/import-lookup"
"$testdir/import-lookup"
