"""Resolve the original 0.1.15 publisher before publishing verified recovery packages."""

import json
import subprocess
import time


REPO = "JosephTagirov/UnifiedVpn"
RUN = 44949412274
SOURCE = "bbdeed3ee08db09625c445d3e5cb80a96f8ddcb3"
BRANCH = "maintenance/publish-015-20261121"
DOWNLOAD = "Download and verify immutable release packages"
PUBLISH = "Publish and verify the stable release"


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def gh(*args):
    return subprocess.run(["gh", "api", *args], capture_output=True, text=True, timeout=30)


def inspect(path):
    result = gh(path)
    require(result.returncode == 0, f"GitHub API inspection failed at {path}")
    return json.loads(result.stdout)


def main():
    path = f"repos/{REPO}/actions/runs/{RUN}"
    deadline = time.monotonic() + 600
    requested_cancel = False
    while time.monotonic() < deadline:
        run = inspect(path)
        require(run["id"] == RUN and run["head_sha"] == SOURCE and run["head_branch"] == BRANCH
                and run["path"] == ".github/workflows/publish-0.1.15.yml",
                "Unexpected original publisher identity")
        if run["status"] == "completed":
            print(f"Original publisher is terminal ({run['conclusion']})", flush=True)
            return

        if not requested_cancel:
            jobs = inspect(f"{path}/attempts/{run['run_attempt']}/jobs")["jobs"]
            publishers = [job for job in jobs if job["name"] == "publish"]
            require(len(publishers) <= 1, "More than one original release writer")
            if publishers:
                statuses = {step["name"]: step["status"] for step in publishers[0]["steps"]}
                # An already running publisher may have created or uploaded a draft.
                # Wait for it to finish rather than removing that writer mid-publication.
                if statuses.get(DOWNLOAD) == "in_progress" and statuses.get(PUBLISH) in (None, "queued", "pending"):
                    result = gh("-X", "POST", f"{path}/cancel")
                    if result.returncode != 0 and "(HTTP 409)" not in result.stderr:
                        raise RuntimeError("GitHub could not cancel the original downloading job")
                    requested_cancel = result.returncode == 0
                    if requested_cancel:
                        print("Original publisher was still downloading; requested cancellation", flush=True)

        time.sleep(5)
    raise TimeoutError("The original publisher has not become terminal; release state was left alone")


if __name__ == "__main__":
    main()
