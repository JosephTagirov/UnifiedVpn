# Use the hash-verified context from prepare_browser_image.py.
# No RUN instruction: building never executes package installers or the browser.
ARG PLAYWRIGHT_IMAGE
FROM ${PLAYWRIGHT_IMAGE}
COPY python/ /opt/openflux-browser/python/
COPY browser_worker.py /opt/openflux-browser/browser_worker.py
COPY browser_sandbox_probe.py /opt/openflux-browser/browser_sandbox_probe.py
ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright \
    PYTHONPATH=/opt/openflux-browser/python \
    OPENFLUX_BROWSER_CONTAINER=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    HOME=/home/browser
LABEL org.unifiedvpn.openflux.browser-contract="2"
USER 10001:10001
WORKDIR /opt/openflux-browser
HEALTHCHECK NONE
ENTRYPOINT ["python3", "-B", "/opt/openflux-browser/browser_worker.py"]
