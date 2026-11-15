#!/usr/bin/env python3
"""Run the real HEV/lwIP pretend-PCB tuple logic on Windows without installing routes or a C runtime.

Uses the project's pinned Android NDK clang plus lld to build a freestanding host DLL.
The test compiles real production lwIP headers and private helpers in IPv4, IPv6 and
dual-stack modes. A source-only mutation must reproduce the old destination-mixing bug.
"""
from __future__ import annotations

import argparse
import ctypes
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    project = Path(__file__).resolve().parent.parent
    output = (args.output or project / 'build/native-tests/hev-udp').resolve()
    output.mkdir(parents=True, exist_ok=True)
    if os.name != 'nt':
        raise RuntimeError('This freestanding host runner targets Windows; use the same C harness with a host compiler on other systems')
    local = (project / 'local.properties').read_text()
    sdk = Path(re.search(r'^sdk\.dir=(.+)$', local, re.M)[1].strip().replace('\\:', ':').replace('\\\\', '\\'))
    ndk = sdk / 'ndk/28.2.13676358'
    if 'Pkg.Revision = 28.2.13676358' not in (ndk / 'source.properties').read_text():
        raise RuntimeError('The pinned Android NDK is required')
    binary = ndk / 'toolchains/llvm/prebuilt/windows-x86_64/bin'
    clang, linker = binary / 'clang.exe', binary / 'ld.lld.exe'
    lwip = project / 'androidApp/src/main/jni/hev-socks5-tunnel/third-part/lwip/src'
    production = lwip / 'include/lwip/priv/udp_pretend.h'
    udp = lwip / 'core/udp.c'
    text = udp.read_text()
    if text.count('udp_pretend_matches_flow(pcb,') != 1 or text.count('udp_pretend_set_flow(npcb,') != 1:
        raise RuntimeError('The compiled pretend-PCB helpers must be wired into production udp_input')
    original = production.read_text()
    destination_check = ' &&\n         pcb->local_port == destination_port && ip_addr_cmp(&pcb->local_ip, destination)'
    if original.count(destination_check) != 1:
        raise RuntimeError('Review the baseline mutation after changing production tuple matching')
    baseline = output / 'source-only/lwip/priv/udp_pretend.h'
    baseline.parent.mkdir(parents=True, exist_ok=True)
    baseline.write_text(original.replace(destination_check, ''))
    test = project / 'tools/native-tests/hev-udp'
    cases = []
    for name, ipv4, ipv6 in [('ipv4', 1, 0), ('ipv6', 0, 1), ('dual', 1, 1)]:
        for fixed in (False, True):
            label = name + ('-fixed' if fixed else '-source-only')
            obj, dll = output / (label + '.obj'), output / (label + '.dll')
            includes = ([] if fixed else [baseline.parents[2]]) + [test / 'compat', lwip / 'include']
            command = [clang, '--target=x86_64-pc-windows-msvc', '-ffreestanding', '-fno-builtin',
                '-fno-stack-protector', '-O2', '-Wall', '-Wextra', '-Wno-unused-parameter',
                f'-DHEV_TEST_IPV4={ipv4}', f'-DHEV_TEST_IPV6={ipv6}', '-c', test / 'udp_flow_isolation.c', '-o', obj]
            command += [argument for include in includes for argument in ('-I', include)]
            result = subprocess.run(list(map(str, command)), capture_output=True, text=True, timeout=30)
            (output / (label + '.compile.log')).write_text(result.stdout + result.stderr)
            if result.returncode:
                raise RuntimeError(result.stdout + result.stderr)
            result = subprocess.run([str(linker), '-flavor', 'link', '/dll', '/noentry', '/nodefaultlib',
                '/out:' + str(dll), str(obj)], capture_output=True, text=True, timeout=30)
            if result.returncode:
                raise RuntimeError(result.stdout + result.stderr)
            library = ctypes.CDLL(str(dll))
            function = library.hev_udp_isolation_regression
            function.restype = ctypes.c_int
            result = function()
            expected = 26 if ipv4 and ipv6 else 12
            if fixed and result != expected:
                raise RuntimeError(f'{label}: production matching failed check {-result}, expected {expected} passed checks')
            if not fixed and result != -4:
                raise RuntimeError(f'{label}: old source-only matching did not reproduce the destination collision: {result}')
            cases.append({'case': label, 'result': result, 'expected': expected if fixed else -4,
                'binary_sha256': hashlib.sha256(dll.read_bytes()).hexdigest()})
    proof = {'passed': True, 'compiler': str(clang), 'ndk': '28.2.13676358',
        'production_header_sha256': hashlib.sha256(production.read_bytes()).hexdigest(),
        'production_udp_sha256': hashlib.sha256(udp.read_bytes()).hexdigest(), 'cases': cases}
    (output / 'verification.json').write_text(json.dumps(proof, indent=2) + '\n')
    print(json.dumps(proof, indent=2))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
