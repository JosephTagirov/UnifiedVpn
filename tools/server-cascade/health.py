"""Resource/probe endpoint bound only to the authenticated private WG link."""

import http.server, json, os, pathlib, shutil, threading, time, urllib.request

CPU = None


def resources():
    global CPU
    values = list(
        map(int, pathlib.Path("/proc/stat").read_text().splitlines()[0].split()[1:9])
    )
    total = sum(values)
    idle = values[3] + values[4]
    busy = (
        0.0
        if CPU is None or total == CPU[0]
        else 100 * (1 - (idle - CPU[1]) / (total - CPU[0]))
    )
    CPU = (total, idle)
    mem = {
        line.split(":")[0]: int(line.split(":")[1].split()[0])
        for line in pathlib.Path("/proc/meminfo").read_text().splitlines()
        if ":" in line
    }
    return {
        "cpuCount": os.cpu_count(),
        "cpuBusyPct": round(max(0, min(100, busy)), 1),
        "availableMiB": round(mem["MemAvailable"] / 1024),
        "totalMiB": round(mem["MemTotal"] / 1024),
        "freeDiskMiB": round(shutil.disk_usage("/").free / 1048576),
        "load1": round(os.getloadavg()[0], 2),
        "checkedAt": int(time.time()),
    }


internet = {"internetOk": False, "internetCheckedAt": 0}


def probes():
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    while True:
        ok = False
        for url in [
            "https://www.gstatic.com/generate_204",
            "https://cp.cloudflare.com/generate_204",
        ]:
            try:
                with opener.open(
                    urllib.request.Request(url, method="HEAD"), timeout=4
                ) as response:
                    ok = response.status == 204
            except Exception:
                pass
            if ok:
                break
        internet.update(internetOk=ok, internetCheckedAt=int(time.time()))
        time.sleep(25)


class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path != "/health":
            self.send_error(404)
            return
        probe = dict(internet)
        probe["internetAgeSeconds"] = max(0, time.time() - probe["internetCheckedAt"])
        data = json.dumps(dict(resources(), **probe, nodeRole="exit")).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    cfg = json.loads(pathlib.Path("/etc/unifiedvpn-cascade/node.json").read_text())
    threading.Thread(target=probes, daemon=True).start()
    http.server.HTTPServer((cfg["private"], 18443), Handler).serve_forever()
