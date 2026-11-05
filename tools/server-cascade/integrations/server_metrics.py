"""Read host resource usage from Linux procfs; no subprocesses or dependencies."""
import json
import pathlib
import shutil
import threading
import time


def cluster_status(path=pathlib.Path('/var/lib/unifiedvpn-cluster/status.json')):
    try:
        raw=json.loads(path.read_text())
        # Only operational status enters the owner API; no diagnostic strings or credentials.
        allowed=('primary','standby','standbyReachable','automaticTrafficFailover','localBackupAt',
                 'offsiteBackupAt','offsiteBackupPending','localBackupBytes','checkedAt')
        return {key:raw[key] for key in allowed if key in raw}
    except (OSError,ValueError,TypeError):
        return None


def cascade_status(path=pathlib.Path('/var/lib/unifiedvpn-cascade/status.json')):
    try:
        raw=json.loads(path.read_text())
        allowed=('checkedAt','entry','exit','mode','cascadeEnabled','privateRttMs',
                 'rateLimitMbit','leaseSeconds','newFlowOnly','automaticIngressFailover')
        result={key:raw[key] for key in allowed if key in raw}
        result['stale']=not 0<=time.time()-raw['checkedAt']<50
        if result['stale']:result.update(cascadeEnabled=False,mode='unknown')
        return result
    except (OSError,ValueError,TypeError,KeyError):
        return None


class Collector:
    def __init__(self, proc=pathlib.Path('/proc'), disk_path='/'):
        self.proc = proc
        self.disk_path = disk_path
        self.lock = threading.Lock()
        self.previous_cpu = None
        self.previous_cpu_at = 0
        self.cached = None
        self.cached_at = 0

    def cpu_counters(self):
        lines = (self.proc / 'stat').read_text().splitlines()
        values = [int(v) for v in lines[0].split()[1:9]]
        if not lines[0].startswith('cpu ') or len(values) < 4 or any(v < 0 for v in values):
            raise ValueError('Invalid CPU counters')
        # guest/guest_nice are already included in user/nice and must not be added.
        idle = values[3] + (values[4] if len(values) > 4 else 0)
        cores = sum(line.split()[0][3:].isdigit() for line in lines if line.startswith('cpu'))
        return sum(values), idle, cores

    def cpu(self):
        current = self.cpu_counters()
        previous = self.previous_cpu
        if previous is None or time.monotonic() - self.previous_cpu_at > 10:
            previous = current
            time.sleep(0.15)
            current = self.cpu_counters()
        self.previous_cpu = current
        self.previous_cpu_at = time.monotonic()
        total = current[0] - previous[0]
        idle = current[1] - previous[1]
        if total <= 0 or not 0 <= idle <= total:
            raise ValueError('CPU sample unavailable')
        return {'percent': round(100 * (total - idle) / total, 1), 'cores': current[2]}

    def memory(self):
        values = {}
        for line in (self.proc / 'meminfo').read_text().splitlines():
            key, value = line.split(':', 1)
            fields = value.split()
            if fields:
                values[key] = int(fields[0]) * 1024
        total = values['MemTotal']
        # MemAvailable includes reclaimable cache: cached RAM is not all busy RAM.
        available = values.get('MemAvailable')
        if available is None:
            available = (values['MemFree'] + values.get('Buffers', 0) + values.get('Cached', 0)
                         + values.get('SReclaimable', 0) - values.get('Shmem', 0))
        if total <= 0 or available < 0:
            raise ValueError('Memory sample unavailable')
        available = min(total, available)
        used = total - available
        return {'percent': round(100 * used / total, 1), 'usedBytes': used,
                'totalBytes': total, 'availableBytes': available}

    def disk(self):
        usage = shutil.disk_usage(self.disk_path)
        if usage.total <= 0:
            raise ValueError('Disk sample unavailable')
        return {'percent': round(100 * usage.used / usage.total, 1), 'usedBytes': usage.used,
                'totalBytes': usage.total, 'availableBytes': usage.free, 'mount': '/'}

    def snapshot(self):
        with self.lock:
            if self.cached is not None and time.monotonic() - self.cached_at < 1:
                return self.cached
            result = {'capturedAt': int(time.time())}
            for name in ['cpu', 'memory', 'disk']:
                try:
                    result[name] = getattr(self, name)()
                except (OSError, ValueError, KeyError, IndexError):
                    result[name] = None
                    if name == 'cpu':
                        self.previous_cpu = None
            cluster=cluster_status()
            if cluster is not None:result['cluster']=cluster
            cascade=cascade_status()
            if cascade is not None:result['cascade']=cascade
            self.cached = result
            self.cached_at = time.monotonic()
            return result


snapshot = Collector().snapshot
