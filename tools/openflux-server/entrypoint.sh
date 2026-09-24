#!/bin/sh
set -eu

fail() {
    printf '%s\n' 'OpenFlux isolated startup precondition failed.' >&2
    exit 1
}

# The wrapper-4 L4 exit requires an isolated namespace, but no raw-socket rules.
[ -f /.dockerenv ] || fail
[ -n "${OPENFLUX_HOST_NETNS:-}" ] || fail
namespace=$(readlink /proc/self/ns/net) || fail
[ "$namespace" != "$OPENFLUX_HOST_NETNS" ] || fail
[ -r /run/secrets/server.json ] || fail

# Only the opt-in supervisor consumes stdout; stderr may contain private URLs.
case "${OPENFLUX_BROWSER_BOOTSTRAP:-}" in
    "") ;;
    stdio) exec /usr/local/bin/openflux --config /run/secrets/server.json --bootstrap-stdio 2>/dev/null ;;
    *) fail ;;
esac

# Upstream transport errors may contain document URLs. Do not retain them.
exec /usr/local/bin/openflux --config /run/secrets/server.json >/dev/null 2>&1
