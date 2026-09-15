# Build only on a disposable builder, never by modifying the shared VPN host.
# Supply an already-reviewed Alpine Linux repository@sha256 digest.
ARG ALPINE_IMAGE
FROM ${ALPINE_IMAGE}
RUN apk add --no-cache ca-certificates iptables
