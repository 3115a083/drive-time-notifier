# Release and signing guide

This project is designed so the final signed APK can be built and published entirely from the GitHub web interface after the one-time signing setup.

## One-time signing setup

Create the signing key locally. Never create it in CI and never commit it.

Windows 11 PowerShell example:

```powershell
$Keytool = "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe"
& $Keytool -genkeypair -v -keystore ".\drive-time-release.jks" -alias "drive-time" -keyalg RSA -keysize 4096 -validity 10000
```

Inspect the certificate:

```powershell
& $Keytool -list -v -keystore ".\drive-time-release.jks" -alias "drive-time"
```

Back up the keystore and passwords before the first public release. The same signing key must be retained for future direct APK updates.

## GitHub Environment and secrets

Create a GitHub Environment named exactly:

```text
release
```

Add these Environment secrets:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

PowerShell Base64 conversion:

```powershell
$Base64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes((Resolve-Path ".\drive-time-release.jks")))
$Base64 | Set-Clipboard
```

If the PKCS12 keystore did not ask for a separate key password, use the keystore password for both `ANDROID_KEYSTORE_PASSWORD` and `ANDROID_KEY_PASSWORD`.

## Signed test build from the GitHub website

After `.github/workflows/release.yml` exists on `main`:

1. Open **Actions**.
2. Select **Release APK**.
3. Select **Run workflow**.
4. Enter the version, for example `1.0.0`.
5. Leave **Create vX.Y.Z tag and publish a public GitHub Release** disabled.
6. Run the workflow from `main`.

The workflow will:

- validate the requested version against `app/build.gradle.kts`
- validate the signing secrets
- run unit tests and lint
- build the signed release APK
- verify the APK with `apksigner`
- create a SHA-256 checksum
- upload the APK, checksum and certificate output as an Actions artifact

## Public GitHub release from the website

Once the signed test build has been installed and tested:

1. Open **Actions → Release APK → Run workflow**.
2. Select branch `main`.
3. Enter the exact version from `app/build.gradle.kts`.
4. Enable **Create vX.Y.Z tag and publish a public GitHub Release**.
5. Run the workflow.
6. Approve the `release` Environment if approval is configured.

The workflow then additionally:

- creates the annotated `vX.Y.Z` tag on the exact tested `main` commit
- pushes the tag
- creates the public GitHub Release
- attaches `drive-time-notifier-X.Y.Z.apk`
- attaches `SHA256SUMS.txt`
- attaches `apksigner.txt`

Public releases are blocked if the manual workflow is not running from `main`.

## Release checklist

Before enabling public release:

1. PR is merged to `main`.
2. Android CI on `main` is green.
3. `versionCode` is incremented for every published update.
4. `versionName` matches the intended release version.
5. `CHANGELOG.md` and Fastlane changelog are current.
6. Screenshots contain no personal addresses, calendar names, tokens or API keys.
7. Signed test APK was installed and smoke-tested on a real device.
8. Signing keystore and passwords are backed up in at least two safe locations.

## After publication

Download the release APK from the GitHub Release and compare the included `SHA256SUMS.txt`. Keep the release APK and signing certificate fingerprint in your own release records.

Never commit or upload the private keystore outside the protected GitHub Environment secret.
