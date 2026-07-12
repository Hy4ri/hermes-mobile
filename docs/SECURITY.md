# Cassy Control — Security Model

## Boundaries

Cassy Control is an administrative client for a self-hosted Hermes Agent. The app must be treated like an SSH client: anyone who can unlock the app and authenticate to Hermes can invoke tools and administrative operations allowed by that Hermes profile.

## Implemented controls

- Android Keystore-backed encrypted credential storage (`EncryptedSharedPreferences`).
- SQLCipher-encrypted local message database.
- Biometric or device-credential gate on cold start and after 60 seconds in the background.
- Optional `FLAG_SECURE` protection blocks screenshots, screen recording and recent-app previews when enabled. It is disabled by default so support screenshots work; the user can enable it under Settings → Behavior.
- Android backup disabled for the application.
- Release build is R8-minified and resource-shrunk.
- Production application ID is separate from upstream.
- Dedicated long-lived 4096-bit release key, stored outside the repository with mode `0600`.
- Cleartext HTTP/WebSocket requests are blocked for public destinations. Only loopback, RFC1918 LAN, Tailscale CGNAT, Tailscale MagicDNS (`.ts.net`) and private IPv6 destinations are permitted without TLS.
- OkHttp release logging does not emit request bodies or credentials.
- Dashboard cookies and bearer tokens are never embedded in the APK. Pairing/login credentials are discovered only from the configured private dashboard at runtime and then stored with Android Keystore-backed encryption.
- Internal service, receiver and FileProvider components are not exported.
- Logout clears token, session cookie and WebSocket authentication mode.

## Network expectations

Preferred access paths:

1. Tailscale/WireGuard to a private NAS address, with dashboard authentication enabled.
2. HTTPS/WSS reverse proxy with an authenticated dashboard.
3. Trusted private LAN only.

Do not expose Hermes dashboard or API ports directly to the public Internet. The app deliberately refuses public cleartext destinations.

## Residual risks

- Android accessibility services with elevated privileges can observe UI content despite screenshot protection.
- A rooted device can defeat Android Keystore and process isolation.
- Tailscale/private-LAN cleartext is permitted because the outer private transport is expected to provide confidentiality; a hostile local segment without VPN can still attack plain HTTP.
- Hermes approvals remain the final authority. Do not auto-approve destructive tool, sudo, secret or pairing requests.
- The release signing key must be backed up securely. Losing it prevents in-place updates.
