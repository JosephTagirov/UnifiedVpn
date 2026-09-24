#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Offline Chromium self-test. Run in the reviewed network-disabled container."""

import asyncio
from importlib.metadata import version
import json
import os
from pathlib import Path


def sandbox_checks(fields):
    checks = {name: fields.get(name) == 'Yes' for name in ('PID namespaces', 'Network namespaces', 'Seccomp-BPF sandbox')}
    checks['Namespace sandbox'] = fields.get('Layer 1 Sandbox') == 'Namespace'
    return checks


async def probe():
    from playwright.async_api import async_playwright
    if os.geteuid() != 10001 or os.getegid() != 10001:
        raise RuntimeError('unexpected_browser_identity')
    status = dict(line.split(':', 1) for line in Path('/proc/self/status').read_text().splitlines() if ':' in line)
    guarded = status['NoNewPrivs'].strip() == '1' and status['Seccomp'].strip() == '2' and int(status['CapEff'], 16) == 0
    if not guarded:
        raise RuntimeError('container_guards_missing')
    async with async_playwright() as playwright:
        browser = await playwright.chromium.launch(headless=True, channel='chromium', chromium_sandbox=True,
            args=['--no-proxy-server'], timeout=20000,
            env={'PATH': '/usr/local/bin:/usr/bin:/bin', 'HOME': '/home/browser', 'TMPDIR': '/tmp'})
        try:
            page = await browser.new_page(accept_downloads=False, service_workers='block', ignore_https_errors=False)
            await page.goto('chrome://sandbox', wait_until='domcontentloaded', timeout=10000)
            rows = await page.locator('tr').evaluate_all("rows => rows.map(r => Array.from(r.querySelectorAll('td')).map(c => c.textContent.trim()))")
            fields = {row[0]: row[1] for row in rows if len(row) == 2}
            checks = sandbox_checks(fields)
            return {'schema': 'openflux-browser-sandbox-v1', 'passed': all(checks.values()),
                    'container_guards': guarded, 'chromium_sandbox': checks,
                    'browser_version': browser.version,
                    'packages': {name: version(name) for name in ('playwright', 'pyee', 'greenlet', 'typing_extensions')},
                    'external_navigation': False}
        finally:
            await browser.close()


def main():
    try:
        report = asyncio.run(asyncio.wait_for(probe(), timeout=35))
    except Exception as error:
        # Exceptions from Playwright contain full command lines; never emit them.
        message = str(error).lower()
        category = 'sandbox_start_failed'
        if 'operation not permitted' in message or 'no usable sandbox' in message:
            category = 'namespace_sandbox_denied'
        elif 'timeout' in message:
            category = 'sandbox_timeout'
        elif 'executable doesn' in message or isinstance(error, (ImportError, ModuleNotFoundError)):
            category = 'browser_dependency_missing'
        report = {'schema': 'openflux-browser-sandbox-v1', 'passed': False, 'category': category,
                  'external_navigation': False, 'error_type': type(error).__name__,
                  'diagnostic_markers': [marker for marker in (
                      'pthread_create', 'clone3', 'no usable sandbox', 'operation not permitted',
                      'permission denied', 'zygote', 'sigtrap', 'sigsegv', 'thread constructor',
                      'failed to move to new namespace', 'eacces', 'cannot open shared object',
                      'executable doesn', 'user data directory') if marker in message]}
        if os.environ.get('OPENFLUX_OFFLINE_PROBE_DIAGNOSTIC') == '1':
            # This probe never receives a document/profile or enables networking.
            # Its owner stores this diagnostic privately, not in application logs.
            report['private_launch_diagnostic'] = str(error)[:16384]
    print(json.dumps(report, sort_keys=True))
    return 0 if report.get('passed') else 1


if __name__ == '__main__':
    raise SystemExit(main())
