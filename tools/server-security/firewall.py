"""Restrict management services to local SSH tunnels and local reverse proxies."""
import subprocess

CHAIN = 'UVPN_SECURITY_IN'
COMMENT = 'unifiedvpn-security-v1'
PORTS = '21,631,2096,7777,8888,8989,9211,9876,9877,11111,18089'


def command(binary, *args, check=True):
    return subprocess.run([binary, '-w', '5', *args], capture_output=True, text=True, timeout=15, check=check)


def ensure_rule(binary, chain, *args):
    if command(binary, '-C', chain, *args, check=False).returncode:
        command(binary, '-A', chain, *args)


for binary in ['iptables', 'ip6tables']:
    if command(binary, '-S', CHAIN, check=False).returncode:
        command(binary, '-N', CHAIN)
    ensure_rule(binary, CHAIN, '-i', 'lo', '-j', 'RETURN')
    ensure_rule(binary, CHAIN, '-p', 'tcp', '-m', 'multiport', '--dports', PORTS, '-j', 'DROP')
    if binary == 'ip6tables':
        # The private inter-node link currently has IPv4 peers only.
        ensure_rule(binary, CHAIN, '-p', 'udp', '--dport', '51889', '-j', 'DROP')
    hook = ['-m', 'comment', '--comment', COMMENT, '-j', CHAIN]
    if command(binary, '-C', 'INPUT', *hook, check=False).returncode:
        command(binary, '-I', 'INPUT', '1', *hook)
