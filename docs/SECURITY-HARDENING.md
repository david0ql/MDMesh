# Security hardening before going live (2026-09-28)

DallyControl went through a four-area audit before being exposed to the Internet: the unauthenticated surface,
authentication and authorization, dangerous inputs (injection, files, XSS, SSRF), and the deployment (edge, Tomcat,
supervisor, containers, secrets, dependencies). Every finding below was reproduced on the dev stack, fixed, and
re-checked. `scripts/security-check.sh` repeats the checks against any running install (43 checks);
`scripts/agent-v1-e2e.sh` (82) and the browser runs confirm nothing functional broke.

## Findings and fixes

| Severity | Finding | Fix |
|---|---|---|
| Critical | Any signed-in user, even read-only, could write a file anywhere on the server through the configuration-file upload's file name (path traversal → code execution). | Needs the `configurations` permission; the name must be a plain file name inside the customer's directory (`FileUtil.resolveInside`); existing files are never overwritten (the directory also hosts the APKs devices install). |
| Critical | Legacy Headwind device endpoints trusted a device number as identity: anyone could read a device's configuration (incl. the kiosk password), write device data, create devices, read the notification queue, and use plugin device APIs. | `RestSurfaceFilter`: outside `/rest/private/**` only the endpoints DallyControl uses answer (decoded, canonical path; encoding tricks tested); the same allowlist in Caddy. |
| High | Stored XSS: a device-reported location provider was rendered as HTML in the console's location popup. | Every Leaflet popup/tooltip escapes device data (`escapeHtml`); strict CSP on the console (no inline scripts at all). |
| High | Any user could change another user's name/email (the email drives password recovery → account takeover). | Only the user themself or user administrators. |
| High | Legacy self-update endpoint fetched arbitrary URLs server-side (SSRF) and could replace the server. | Removed (DallyControl updates through the signed supervisor). |
| Medium | Session id kept across login (fixation). | New session at every login. |
| Medium | Session cookie without Secure/SameSite, 24 h sessions. | SameSite=Lax, Secure when `BASE_URL` is https, 8 h idle. |
| Medium | No security headers; the console and recovery page could be framed (clickjacking on destructive actions). | HSTS, CSP, `X-Frame-Options: SAMEORIGIN`, nosniff, referrer and permissions policies; no `Server` header. |
| Medium | `Access-Control-Allow-Origin: *` on the whole API; Swagger listing every endpoint publicly. | No CORS (same-origin console); Swagger closed. |
| Medium | A fresh install exposed `admin`/`admin` until the installer seeded the generated password. | Edge starts after the seed; the server refuses the default password outside the dev stack. |
| Medium | Server-side fetch of user-provided URLs (configuration external files) could reach internal services. | `UrlGuard`: http/https to public addresses only, no redirects. |
| Medium | Signed update manifests named images by tag, which can be re-pushed. | Manifests pin images by digest; the supervisor and `apply.sh` refuse anything else; rollback restores the previous references. |
| Low | No brute-force protection on login (a 1 s delay, bypassed in parallel). | Per-account lockout: 5 failures → 5 min, doubling to 30. |
| Low | Passwords stored as SHA-1 with one salt shared by every install. | PBKDF2-HMAC-SHA256, 310k iterations, per-user salt; old hashes upgrade at the next login. |
| Low | Tomcat version and stack traces in error pages; update status (versions, errors) public. | Quiet error pages; update detail only for admins or the recovery token. |
| Low | Client could spoof a device's recorded public IP (`CF-Connecting-IP`). | Dropped at the edge unless in Cloudflare mode. |
| Low | Containers with default capabilities; `cloudflared:latest`, `postgres:14` (EOL 2026-11-12), Alpine 3.20 (EOL). | `no-new-privileges`, capabilities dropped to the minimum, Caddy fully unprivileged; PostgreSQL 17 and cloudflared pinned by digest; Alpine 3.22. |
| Low | Deleting a device left its commands, events, state and location trail (personal data) behind. | A trigger deletes them with the device; existing orphans removed. |
| Low | `.env` briefly world-readable during setup; update backups (full DB dumps) default permissions, unbounded. | Created owner-only; backups owner-only, newest 5 kept. |
| Low | `/files` path containment relied on Tomcat alone; unauthenticated video and file-download endpoints (latent). | Explicit canonical-path check; the endpoints removed. |

## Residual risks (accepted, with reasons)

- **Hosted files are public by URL.** `/files/*` (APKs, configuration files) is downloadable without a session
  because devices fetch them anonymously during installs. Anyone who knows or guesses a file name (e.g. the uploaded
  APK's name) can download it. Don't put secrets in configuration files; treat hosted APKs as public.
- **Old Java libraries without a reachable path.** Jersey 2.25.1 (CVE-2021-28168: temporary files readable by other
  local users — the server runs alone in its container), xalan 2.7.2 (only with untrusted XSLT, never processed),
  xmlgraphics-commons 2.2 / Batik (only the SVG writer is present). Upgrading Jersey means moving the whole JAX-RS/HK2
  stack; plan it as its own change.
- **The supervisor holds the Docker socket** (root-equivalent on the host). It only acts on signed, digest-pinned
  manifests for authenticated admins; keep the release signing key out of the job that pushes images.
- **The loopback remote-control port on the phone** is briefly reachable by other apps on the phone between the agent
  opening it and droidVNC-NG connecting (ADR 0010); a racing app would still need the VNC password.
- **Legacy code stays in the WAR.** The closed Headwind endpoints are unreachable (allowlist in the server and at the
  edge), not deleted; the allowlist tests guard it.

## Re-checking

```bash
ADMIN_PW='<admin password>' scripts/security-check.sh https://mdm.example.com   # edge
ADMIN_PW=admin scripts/security-check.sh http://localhost:8088 http://localhost:8080   # dev: edge + Tomcat
```
