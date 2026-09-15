#!/bin/sh
set -eu

fail() {
    printf '%s\n' 'OpenFlux isolated startup precondition failed.' >&2
    exit 1
}

# A namespace-local rule must never run in the invoking host's namespace.
[ -f /.dockerenv ] || fail
[ -n "${OPENFLUX_HOST_NETNS:-}" ] || fail
namespace=$(readlink /proc/self/ns/net) || fail
[ "$namespace" != "$OPENFLUX_HOST_NETNS" ] || fail
[ -r /run/secrets/server.json ] || fail

iptables -w 5 -N UVPN_OFLUX_RST >/dev/null 2>&1 || fail
iptables -w 5 -A UVPN_OFLUX_RST -p tcp --tcp-flags RST RST -j DROP >/dev/null 2>&1 || fail
iptables -w 5 -A OUTPUT -p tcp --tcp-flags RST RST -j UVPN_OFLUX_RST >/dev/null 2>&1 || fail

# Upstream transport errors may contain document URLs. Do not retain them.
exec /usr/local/bin/openflux --config /run/secrets/server.json >/dev/null 2>&1
