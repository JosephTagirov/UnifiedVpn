"""REG.API v2 transport and restartable, narrowly scoped DNS reconciliation.

One configured publisher owns these records. REG.API has no compare-and-swap
primitive, so concurrent independent writers require external coordination.
"""
import ipaddress
import http.client
import json
import os
from pathlib import Path
import stat
import time
import urllib.parse
import urllib.request

from probes import NoRedirect


class DnsUnavailable(RuntimeError):
    pass


class DnsDrift(DnsUnavailable):
    pass


def read_private_credentials(path):
    source = Path(path)
    info = source.lstat()
    if not stat.S_ISREG(info.st_mode) or source.is_symlink():
        raise DnsUnavailable("Credential source must be a protected regular file")
    if os.name != "posix":
        raise DnsUnavailable("Live registrar credentials require the Linux service host")
    if info.st_uid != os.geteuid() or stat.S_IMODE(info.st_mode) & 0o077:
        raise DnsUnavailable("Registrar credentials must be owner-only")
    if info.st_size > 16384:
        raise DnsUnavailable("Credential source exceeds its size limit")
    try:
        value = json.loads(source.read_text(encoding="utf-8"))
    except (ValueError, OSError):
        raise DnsUnavailable("Credential source is unreadable") from None
    if not isinstance(value, dict) or not all(isinstance(value.get(key), str) and value[key] for key in ["username", "password"]):
        raise DnsUnavailable("Registrar credential schema is invalid")
    return value


class RegRu:
    BASE = "https://api.reg.ru/api/regru2/"
    METHODS = {"zone/get_resource_records", "zone/add_alias", "zone/remove_record"}

    def __init__(self, zone, records, credentials, *, allow_writes=False, opener=None, clock=time.monotonic, sleep=time.sleep):
        self.zone = zone.rstrip(".").lower()
        self.records = tuple(records)
        self.credentials = credentials
        self.allow_writes = allow_writes
        self.opener = opener or urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
        self.clock = clock
        self.sleep = sleep
        self.last_request = -1000
        for name in self.records:
            self.subdomain(name)

    def subdomain(self, name):
        name = name.rstrip(".").lower()
        if name == self.zone:
            return "@"
        if not name.endswith("." + self.zone):
            raise DnsUnavailable("Managed DNS name leaves the configured zone")
        return name[: -len(self.zone) - 1]

    def call(self, method, parameters):
        if method not in self.METHODS:
            raise DnsUnavailable("Registrar method is outside this service's scope")
        wait = 4 - (self.clock() - self.last_request)
        if wait > 0:
            self.sleep(wait)
        data = {
            **parameters,
            "username": self.credentials["username"],
            "password": self.credentials["password"],
            "output_content_type": "plain",
            "show_input_params": 0,
        }
        body = urllib.parse.urlencode({"input_format": "json", "output_format": "json", "input_data": json.dumps(data)}).encode()
        request = urllib.request.Request(
            self.BASE + method, data=body,
            headers={"Content-Type": "application/x-www-form-urlencoded", "Accept": "application/json"},
            method="POST",
        )
        self.last_request = self.clock()
        try:
            with self.opener.open(request, timeout=20) as response:
                encoded = response.read(2 * 1024 * 1024 + 1)
            if len(encoded) > 2 * 1024 * 1024:
                raise ValueError("Oversized registrar response")
            result = json.loads(encoded)
            if not isinstance(result, dict) or result.get("result") != "success":
                raise ValueError("Registrar returned an error")
            domains = result.get("answer", {}).get("domains")
            if not isinstance(domains, list) or len(domains) != 1:
                raise ValueError("Registrar result has unexpected domain scope")
            domain = domains[0]
            if not isinstance(domain, dict) or domain.get("dname", "").rstrip(".").lower() != self.zone or domain.get("result") != "success":
                raise ValueError("Registrar domain operation failed")
            return result
        except (OSError, ValueError, AttributeError, TypeError, http.client.HTTPException):
            # Even an HTTP exception can carry the echoed request or credentials.
            raise DnsUnavailable("Registrar API unavailable or rejected the request") from None

    def records_now(self):
        result = self.call("zone/get_resource_records", {"domains": [{"dname": self.zone}]})
        domains = result.get("answer", {}).get("domains", [])
        matching = [item for item in domains if isinstance(item, dict) and item.get("dname", "").rstrip(".").lower() == self.zone]
        if len(matching) != 1 or matching[0].get("result") != "success" or not isinstance(matching[0].get("rrs"), list):
            raise DnsUnavailable("Registrar DNS result is incomplete")
        values = {name: [] for name in self.records}
        names = {self.subdomain(name): name for name in self.records}
        for record in matching[0]["rrs"]:
            if not isinstance(record, dict):
                raise DnsUnavailable("Registrar record schema is invalid")
            name = names.get(record.get("subname"))
            if name is None:
                continue
            if record.get("rectype") in {"AAAA", "CNAME"}:
                raise DnsDrift("Managed DNS has an uncovered IPv6 route or alias")
            if record.get("rectype") == "A":
                if record.get("state") != "A":
                    raise DnsUnavailable("Managed registrar record is not active")
                try:
                    values[name].append(str(ipaddress.IPv4Address(record.get("content"))))
                except (ValueError, TypeError):
                    raise DnsUnavailable("Registrar returned an invalid IPv4 record") from None
        return {name: sorted(set(addresses)) for name, addresses in values.items()}

    def add_a(self, name, address):
        self.require_writes()
        self.call("zone/add_alias", {"domains": [{"dname": self.zone}], "subdomain": self.subdomain(name), "ipaddr": address})

    def remove_a(self, name, address):
        self.require_writes()
        self.call("zone/remove_record", {"domains": [{"dname": self.zone}], "subdomain": self.subdomain(name), "record_type": "A", "content": address})

    def require_writes(self):
        if not self.allow_writes:
            raise DnsUnavailable("Registrar mutations are disabled")


def validate_owned_records(current, names, source, target):
    if set(current) != set(names):
        raise DnsDrift("Registrar managed-record set changed")
    allowed = {source, target}
    if any(not values or set(values) - allowed for values in current.values()):
        raise DnsDrift("Registrar managed records changed outside the pending operation")


def reconcile(provider, names, source, target, authoritative, *, resuming=False):
    """Reread after every mutation. A lost reply is reconciled on the next tick."""
    if len(authoritative) < 2:
        raise DnsDrift("Authoritative DNS observations are missing")
    for observation in authoritative.values():
        validate_owned_records(observation, names, source, target)
    current = provider.records_now()
    validate_owned_records(current, names, source, target)
    if not resuming:
        expected = {name: [source] for name in names}
        if current != expected or any(observation != expected for observation in authoritative.values()):
            raise DnsDrift("Authoritative and registrar state changed before the DNS write")
    for name in names:
        before = provider.records_now()
        validate_owned_records(before, names, source, target)
        if target not in before[name]:
            provider.add_a(name, target)
            after = provider.records_now()
            expected = {**before, name: sorted(set(before[name] + [target]))}
            if after != expected:
                raise DnsDrift("Registrar state diverged after adding the target")
        before = provider.records_now()
        validate_owned_records(before, names, source, target)
        if source in before[name]:
            provider.remove_a(name, source)
            after = provider.records_now()
            expected = {**before, name: [target]}
            if after != expected:
                raise DnsDrift("Registrar state diverged after removing the former target")
    return provider.records_now()
