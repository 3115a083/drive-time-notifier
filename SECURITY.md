# Security

## Reporting vulnerabilities

Please report security issues privately to the repository owner rather than opening a public issue when the report contains an exploitable vulnerability, credentials, or sensitive data.

## Signing-key policy

The Android release signing key must never be committed to this repository.

Recommended handling:

1. Generate the release keystore on an offline or trusted local machine.
2. Create at least two encrypted offline backups stored in separate locations.
3. Record the alias, certificate SHA-256 fingerprint, creation date, and backup locations.
4. Put only an encrypted/base64 representation into GitHub Actions **Environment secrets** if GitHub is used to create signed releases.
5. Protect the GitHub `release` Environment with required reviewers.
6. Rotate repository/API credentials if a signing secret is ever exposed. The Android app signing key itself generally cannot be replaced for already installed direct-distribution builds without breaking update compatibility.

The workflow expects:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Do not paste these values into issues, pull requests, logs, source files, or chat transcripts.

## Repository hardening

Recommended GitHub settings:

- Require pull requests for `main`
- Require the Android CI status check
- Block force pushes and branch deletion
- Enable secret scanning and push protection where available
- Enable Dependabot security updates
- Protect the `release` Environment with required reviewers
- Keep Actions permissions read-only by default
