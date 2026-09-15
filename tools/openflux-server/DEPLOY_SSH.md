# Explicit SSH Deployment

`deploy_ssh.py` is a separate **mutating** controller. The approved read-only
`inspect_ssh.py` CLI is unchanged. These helpers have mocked local tests, not
an end-to-end deployment guarantee. Keep provider-console access and an existing
SSH session available. Independently verify working olcRTC, AWG and Xray before
and after any test.

Every command starts with:

```text
python tools/openflux-server/deploy_ssh.py PHASE --access-file PRIVATE_ACCESS_JSON --receipt-file PRIVATE_RECEIPT_JSON
```

Access must use the dedicated root identity next to the access JSON and an
independently verified Ed25519 fingerprint. The receipt must be in that private
directory or a child directory. Optional `--socks-port PORT` or
`--http-proxy-port PORT` uses a separate temporary loopback relay through an
already-running proxy; it does not change proxy settings. Never pass passwords,
document URLs, encryption keys, or profile URIs as arguments.

## Phases

| Phase | Additional Arguments | Effect |
| --- | --- | --- |
| `upload` | `--bundle-dir ARTIFACTS --server-config PRIVATE_SERVER_JSON --apply` | Creates a new exclusive local receipt and new root-owned staging directory. |
| `runtime-pull` | `--alpine-image docker.io/library/alpine@sha256:DIGEST --allow-network --apply` | Pulls only this independently reviewed immutable official Alpine image and retains a unique local tag. |
| `runtime-build` | Same `--alpine-image`, plus `--allow-build-network --apply` | Uses the already-present base to build CA certificates and iptables into an image. |
| `preflight` | `--runtime-image sha256:IMAGE_ID --subnet UNUSED_PRIVATE_SUBNET` | Runs the manager's read-only preinstallation checks. |
| `install` | Same runtime/subnet arguments, plus `--apply` | Installs only a new owned installation and its verified image; starts no exit node. |
| `run` | `--apply` | Starts the owned container and bridge. |
| `check` | None | Checks local configuration and ownership, not the encrypted connection. |
| `stop` | `--apply` | Removes only the bound installation's owned container and bridge. |

Use the same receipt and access file for all phases. If a reviewed suitable
runtime image already exists locally, skip both runtime phases. Its immutable
image ID and an original tag must be retained. Runtime build returns its image
ID **only in the private phase report**, not the terminal summary.

## Boundaries

- Upload accepts only the Linux binary, matching source archive, build manifest,
  three license files, and fixed deployment tools. Other files in the artifact
  directory are ignored. Artifact provenance and binary/source hashes must match.
- There is no archive extraction. Files are streamed into fixed allowlisted
  paths under `/root/unifiedvpn-openflux-stage-<random-id>`, using exclusive
  creation and SHA256 checks. Root directories use mode 0700 and files mode 0600.
  The server secret stays in a separate `private` directory, outside all build
  contexts. Existing staging, receipts and installations are never overwritten.
- Each phase re-verifies the host key, target-bound receipt and staged hashes.
  Run/check/stop also require the installation owner recorded for that staging
  session. An unrelated installation is not adopted.
- Runtime build uses only a Dockerfile in its context, the existing default
  builder, and explicit build-network consent. `apk` runs inside the build,
  never on the host. Existing builder CPU/memory behavior is retained; this
  helper does not reconfigure Docker or claim build resource isolation.
- The Docker socket is explicitly local. No SSH forwarding, password login,
  host package installation, VPN-service command, host firewall command, sysctl,
  routing command, DNS change, or Docker installation/restart is performed.
  **Docker build networking and the owned bridge can affect host network
  infrastructure.** Existing VPN behavior must be checked separately.
- Raw SSH, manager and Docker output is discarded. Only validated status fields
  are printed; sanitized phase metadata is written exclusively to private files.
  Successful `check` never implies an authenticated peer or tunneled traffic.

An interrupted phase may have changed remote state. Do not automatically repeat
a mutation. Partial staging, images, build cache and secret files remain for
review; there is no recursive cleanup, global prune, or automatic rollback.
`stop` intentionally retains installed files, original inputs and runtime images.
Removal of a reviewed owned installation remains the manager's separate
`remove --apply` operation, described in [README.md](README.md).

## Tests

```text
python -m unittest discover -s tools/openflux-server/tests -p test_deploy_ssh.py -v
```

## Further Guidance

This controller requires explicit authorization for every mutating phase.
For Russian-language isolation and rollback guidance, see the Russian section
of [README.md](README.md). The SSH controller adds no new editor support and
does not prove that the encrypted connection works.

Preserve upstream GPL-3.0-or-later notices and corresponding source when
distributing the binary or image.
