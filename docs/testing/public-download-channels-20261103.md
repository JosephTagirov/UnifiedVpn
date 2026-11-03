# Public and owner download channels

The public website and GitHub release distribute the user edition. The server's
`UnifiedVPN-Android-latest.apk`, `UnifiedVPN-Windows-latest.exe` and
`UnifiedVPN-Windows-latest.zip` aliases resolve exclusively through
`release-stable.json`. They do not fall back to the owner edition if the public
manifest is unavailable. An owner artifact in the public manifest fails closed.

The owner edition retains a separate update feed and explicit versioned download
URLs delivered directly to the owner. Owner artifacts are not uploaded to public
GitHub releases or selected by public website download links.

On 2026-11-03, the public aliases were corrected to select public build 2026110301.
Owner build 2026110302 and both edition-specific update manifests were preserved.
No application rebuild was required for this server routing correction.

`tools/update-server/public-downloads.patch` applies to the download agent's
`downloads.py`. Run `test_latest_downloads.py`, `test_release_store.py` and
`test_owner_feed.py` together in the agent directory. All 15 tests passed locally
and against the server's release module in an isolated server directory before
deployment. Only `unifiedvpn-control` was restarted. The 14 running containers,
nginx and x-ui retained their existing processes and start times.
