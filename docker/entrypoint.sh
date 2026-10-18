#!/bin/sh
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Starts mihon-source with a temporary directory that allows running code.
#
# Extensions' HTTP clients decode zstd with a native library that is unpacked into
# java.io.tmpdir and loaded from there. On a /tmp mounted noexec, as hardened containers
# often have it, loading fails, and so does every request to a site that answers with
# zstd. So the temporary directory is, in order:
#   1. MIHON_TMP_DIR, when set;
#   2. /tmp, when code can run from it;
#   3. $MIHON_DATA_DIR/tmp, emptied first.
# The JVM fixes java.io.tmpdir when it starts, so this can't wait until the service runs.
set -eu

# runs_code DIR: whether a file in DIR may be executed (false on a noexec mount).
runs_code() {
	mkdir -p "$1" 2>/dev/null || return 1
	probe=$(mktemp "$1/.exec-probe.XXXXXX" 2>/dev/null) || return 1
	chmod 700 "$probe"
	if [ -x "$probe" ]; then ok=0; else ok=1; fi
	rm -f "$probe"
	return "$ok"
}

data="${MIHON_DATA_DIR:-/data}"
if [ -n "${MIHON_TMP_DIR:-}" ]; then
	tmp="$MIHON_TMP_DIR"
	mkdir -p "$tmp"
elif runs_code /tmp; then
	tmp=/tmp
else
	tmp="$data/tmp"
	mkdir -p "$tmp"
	find "$tmp" -mindepth 1 -delete 2>/dev/null || true
	echo "mihon-source: /tmp doesn't allow running code (noexec); using $tmp for temporary files" >&2
fi

JAVA_OPTS="${JAVA_OPTS:-} -Djava.io.tmpdir=$tmp"
export JAVA_OPTS
exec /opt/mihon-source/bin/mihon-source "$@"
