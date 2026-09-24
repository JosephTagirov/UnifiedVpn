#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Read-only, hostname-free prerequisites for the experimental browser companion."""

import importlib.util
import json
from pathlib import Path
import shutil
import subprocess


def collect(command=subprocess.run):
    def query(arguments):
        result = command(arguments, stdin=subprocess.DEVNULL, capture_output=True,
                         text=True, timeout=10, check=False)
        if result.returncode or len(result.stdout) > 1048576:
            raise RuntimeError("prerequisite_query_failed")
        return result.stdout

    def integer(path):
        try:
            return int(Path(path).read_text().strip())
        except (OSError, ValueError):
            return None

    memory = {}
    for line in Path("/proc/meminfo").read_text().splitlines():
        name, value = line.split(":", 1)
        if name in ("MemTotal", "MemAvailable"):
            memory[name] = int(value.split()[0]) * 1024
    docker = ["docker", "--host", "unix:///var/run/docker.sock"]
    info = json.loads(query(docker + ["info", "--format", "{{json .}}"]))
    images = [json.loads(line) for line in query(docker + ["image", "ls", "--format", "{{json .}}"]).splitlines()]
    return {
        "schema": "openflux-browser-prerequisites-v1",
        "memory_bytes": memory,
        "root_disk_available_bytes": shutil.disk_usage("/").free,
        "docker_linux": info.get("OSType") == "linux",
        "docker_rootless": any("rootless" in item for item in info.get("SecurityOptions", [])),
        "docker_seccomp": any("seccomp" in item for item in info.get("SecurityOptions", [])),
        "docker_apparmor": any("apparmor" in item for item in info.get("SecurityOptions", [])),
        "docker_memory_limit": info.get("MemoryLimit") is True,
        "docker_pids_limit": info.get("PidsLimit") is True,
        "browser_named_image_count": sum(any(word in row.get("Repository", "").lower()
                                           for word in ("playwright", "chromium", "chrome")) for row in images),
        "host_browser_executable_count": sum(shutil.which(name) is not None
                                            for name in ("chromium", "chromium-browser", "google-chrome")),
        "host_python_playwright_present": importlib.util.find_spec("playwright") is not None,
        "unprivileged_userns_clone": integer("/proc/sys/kernel/unprivileged_userns_clone"),
        "maximum_user_namespaces": integer("/proc/sys/user/max_user_namespaces"),
        "apparmor_restrict_unprivileged_userns": integer("/proc/sys/kernel/apparmor_restrict_unprivileged_userns"),
        "sandbox_runtime_tested": False,
        "server_mutations": 0,
    }


if __name__ == "__main__":
    try:
        print(json.dumps(collect(), sort_keys=True))
    except Exception:
        print(json.dumps({"status": "prerequisite_query_failed", "server_mutations": 0}))
        raise SystemExit(1)
