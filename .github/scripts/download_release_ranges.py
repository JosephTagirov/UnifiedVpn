"""Fetch verified immutable UnifiedVPN packages with bounded concurrent HTTP ranges."""

import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from hashlib import file_digest
from http.client import HTTPException
import json
import os
from pathlib import Path
import re
import tempfile
import threading
import time
from urllib.parse import urlsplit
from urllib.request import Request, urlopen


CHUNK_BYTES = 8 * 1024 * 1024
SOCKET_TIMEOUT = 25
RANGE_TIMEOUT = 120
ATTEMPTS = 3
ASSET_NAME = re.compile(r"UnifiedVPN-\d+\.\d+\.\d+-build\.\d{10}-[a-z0-9-]+\.(?:apk|exe|zip)")


def require(condition, message):
    if not condition:
        raise ValueError(message)


@dataclass(frozen=True)
class Asset:
    name: str
    size: int
    sha256: str
    url: str
    part: Path
    etag: str


def request(url, method, headers=None):
    return urlopen(Request(url, method=method, headers={
        "Accept-Encoding": "identity",
        "User-Agent": "UnifiedVPN-release-publisher",
        **(headers or {}),
    }), timeout=SOCKET_TIMEOUT)


def head(url, size):
    error = None
    for attempt in range(ATTEMPTS):
        try:
            with request(url, "HEAD") as response:
                require(response.status == 200 and response.geturl() == url, "Unexpected HEAD response")
                require(response.headers.get("Content-Length") == str(size), "HEAD length differs from manifest")
                require(response.headers.get("Content-Encoding", "identity") == "identity", "HEAD is encoded")
                etag = response.headers.get("ETag", "")
                return etag if etag.startswith('"') and etag.endswith('"') else ""
        except (ValueError, OSError, HTTPException) as caught:
            error = caught
            if attempt + 1 < ATTEMPTS:
                time.sleep(1 << attempt)
    raise RuntimeError(f"HEAD verification failed: {url}") from error


def write_range(asset, start, end, cancelled):
    length = end - start + 1
    headers = {"Range": f"bytes={start}-{end}"}
    if asset.etag:
        headers["If-Match"] = asset.etag
    error = None
    for attempt in range(ATTEMPTS):
        if cancelled.is_set():
            return
        try:
            deadline = time.monotonic() + RANGE_TIMEOUT
            with request(asset.url, "GET", headers) as response, asset.part.open("r+b", buffering=0) as part:
                require(response.status == 206 and response.geturl() == asset.url, "Expected exact HTTP partial response")
                require(response.headers.get("Content-Range") == f"bytes {start}-{end}/{asset.size}",
                        "Unexpected Content-Range")
                require(response.headers.get("Content-Length") == str(length), "Range length differs")
                require(response.headers.get("Content-Encoding", "identity") == "identity", "Range is encoded")
                if asset.etag:
                    require(response.headers.get("ETag") == asset.etag, "Mirror entity changed during transfer")
                part.seek(start)
                remaining = length
                while remaining:
                    if cancelled.is_set():
                        return
                    if time.monotonic() >= deadline:
                        raise TimeoutError("Range transfer exceeded its deadline")
                    data = response.read1(min(remaining, 256 * 1024))
                    require(data, "Range body ended early")
                    require(part.write(data) == len(data), "Partial file write")
                    remaining -= len(data)
                require(not response.read(1), "Unexpected bytes after the requested range")
            return
        except (ValueError, OSError, HTTPException) as caught:
            error = caught
            if attempt + 1 < ATTEMPTS and cancelled.wait(1 << attempt):
                return
    raise RuntimeError(f"{asset.name} range {start}-{end} failed after {ATTEMPTS} attempts") from error


def hash_file(path):
    with path.open("rb") as stream:
        return file_digest(stream, "sha256").hexdigest()


def fetch_assets(description, root, directory, workers):
    require(6 <= workers <= 10, "Worker limit must be 6–10")
    require(bool(description) and len(description) <= 4, "Unexpected manifest size")
    directory.mkdir(parents=True, exist_ok=True)
    cancelled = threading.Event()
    with tempfile.TemporaryDirectory(prefix=".ranges-", dir=directory) as temporary:
        assets = []
        for name, item in description.items():
            require(ASSET_NAME.fullmatch(name), "Unexpected asset filename")
            size, sha256 = item["bytes"], item["sha256"]
            require(type(size) is int and size > 0 and re.fullmatch(r"[0-9a-f]{64}", sha256),
                    f"Invalid manifest entry for {name}")
            final = directory / name
            if final.exists():
                require(final.is_file() and not final.is_symlink() and final.stat().st_size == size
                        and hash_file(final) == sha256, f"Existing file differs: {name}")
                print(f"Already verified {name}", flush=True)
                continue
            url = f"{root}/{name}"
            etag = head(url, size)
            part = Path(temporary) / name
            with part.open("xb") as stream:
                stream.truncate(size)
            assets.append(Asset(name, size, sha256, url, part, etag))

        ranges = [
            (asset, start, min(start + CHUNK_BYTES, asset.size) - 1)
            for start in range(0, max((item.size for item in assets), default=0), CHUNK_BYTES)
            for asset in assets if start < asset.size
        ]
        print(f"Downloading {len(ranges)} verified byte ranges with {workers} workers", flush=True)
        with ThreadPoolExecutor(max_workers=workers) as pool:
            futures = [pool.submit(write_range, *span, cancelled) for span in ranges]
            try:
                for count, pending in enumerate(as_completed(futures), 1):
                    pending.result()
                    if count % 12 == 0 or count == len(futures):
                        print(f"Completed {count}/{len(futures)} ranges", flush=True)
            except Exception:
                cancelled.set()
                for pending in futures:
                    pending.cancel()
                raise

        for asset in assets:
            require(asset.part.stat().st_size == asset.size and hash_file(asset.part) == asset.sha256,
                    f"Whole-file SHA-256 differs: {asset.name}")
            # Keep an existing final file intact rather than replacing it.
            os.link(asset.part, directory / asset.name)
            print(f"Verified {asset.name} ({asset.size} bytes)", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--mirror", required=True)
    parser.add_argument("--directory", type=Path, required=True)
    parser.add_argument("--workers", type=int, default=8)
    args = parser.parse_args()
    root = args.mirror.rstrip("/")
    parts = urlsplit(root)
    require(parts.scheme == "https" and parts.hostname and not parts.username and not parts.password
            and not parts.query and not parts.fragment, "Use an HTTPS mirror URL without credentials or query")
    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    fetch_assets(manifest["assets"], root, args.directory, args.workers)


if __name__ == "__main__":
    main()
