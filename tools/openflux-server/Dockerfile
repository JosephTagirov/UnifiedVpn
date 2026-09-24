ARG RUNTIME_IMAGE
FROM ${RUNTIME_IMAGE}
ARG EXPECTED_VERSION
USER 0:0
WORKDIR /
SHELL ["/bin/sh", "-c"]
HEALTHCHECK NONE
COPY openflux /usr/local/bin/openflux
COPY entrypoint.sh /usr/local/bin/openflux-entrypoint
COPY LICENSE NOTICE COPYRIGHT provenance.json source.tar.gz /usr/share/openflux/
RUN chmod 0755 /usr/local/bin/openflux /usr/local/bin/openflux-entrypoint \
    && command -v readlink >/dev/null \
    && test -s /etc/ssl/certs/ca-certificates.crt \
    && test "$(/usr/local/bin/openflux --version)" = "$EXPECTED_VERSION"
ENTRYPOINT ["/usr/local/bin/openflux-entrypoint"]
CMD []
