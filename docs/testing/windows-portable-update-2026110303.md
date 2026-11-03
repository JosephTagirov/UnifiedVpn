# Windows update with a portable copy running — 0.1.7 / 2026110303

The user's 0.1.6 setup log returned 1603 at `UnifiedVpnRequireClosed` because
WiX's filename-only detector found two processes from a portable desktop copy.
The registered 0.1.5 installation was in `Program Files`. The EXE correctly left
the portable process alone, but the MSI's broader detector then rejected it.

The MSI now embeds `UnifiedVpnDetectInstalledApp`, a checked immediate DTF custom
action. After costing resolves `INSTALLDIR`, it compares complete executable
paths with that target and the installed locations of products being replaced.
The registered current product is included for repair. The EXE shares the same
path matcher and queries process images with limited access rights. A process
that exits during enumeration is skipped. The guard never closes or kills apps.
The EXE still requests normal closure of the registered app and checks its files.

`tools/Test-WindowsMsiProcessGuard.py` installs an isolated per-user 0.1.5 fixture
and runs the real production custom action in MSI transactions. These checks
passed:

- A running registered app rejects upgrade before removal and remains running.
- Its old registered location still blocks upgrade with a different target path.
- Upgrade succeeds after that app closes while a portable copy stays running.
- Repair also succeeds with the portable copy running.
- Repair rejects a running process from the registered directory.
- The portable process stays alive throughout; fixture products are removed.

The fixture uses randomly generated product and upgrade identities. It does not
install, uninstall or close the user's UnifiedVPN copies. The compiled-policy
verifier also rejects filename-only detectors, ignored/deferred guard failures,
unsafe replacement order and automatic reboots. Its 22 policy tests passed.

The branded EXE maps an MSI refusal for a running installed app to a Russian
explanation with retry instructions and the retained setup log. Physical Android
testing and a complete update of the user's production installation are outside
this automated fixture. Engine code is unchanged from 0.1.6.
