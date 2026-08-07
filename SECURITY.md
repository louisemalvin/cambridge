# Security policy

## Supported release

Security reports are accepted for the current supported release and the
current `main` branch.

## Reporting a vulnerability

Please do not publish suspected vulnerabilities in a public issue. Use
GitHub's private vulnerability-reporting or security-advisory channel for this
repository. If that channel is unavailable, contact the repository owner
through GitHub and request a private reporting route before sharing details.

Include the affected version, operating system, OBS version where relevant,
steps to reproduce, and any logs needed to confirm the issue. Remove private
IP addresses, credentials, and personal data from reports.

Release 1 intentionally uses an unauthenticated trusted-LAN transport. Do not
expose its control or media ports to an untrusted network or the internet.
