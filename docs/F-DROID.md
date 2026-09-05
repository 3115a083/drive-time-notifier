# F-Droid publication

Drive Time Notifier is prepared for submission to the official F-Droid repository.

The app is MIT-licensed and contains no proprietary routing SDKs. Commercial providers such as TomTom, Google Routes and HERE are optional HTTPS services configured by the user. Free routing alternatives are available.

## Anti-features

The prepared metadata declares:

- `NonFreeNet`, because the UI offers optional proprietary network services.
- `NonFreeAssets`, because some provider marks are trademarked or originate from assets whose licensing is more restrictive than the app's MIT license.

This disclosure is intentional and does not mean that proprietary SDK code is bundled into the APK.

Third-party artwork sources and licenses are documented in `THIRD_PARTY_NOTICES.md`.

## Upstream metadata

F-Droid can read localized metadata from:

```text
fastlane/metadata/android/de-DE
fastlane/metadata/android/en-US
```

The repository includes:

- app title
- short description
- full description
- changelog for versionCode 1
- image directories
- app icon copied from `branding/icon.png`

Before submission, add real anonymized phone screenshots to both locale folders. Do not include personal addresses, real calendar names, API keys or automation tokens.

## Prepared fdroiddata file

A ready-to-copy metadata file is stored at:

```text
fdroid/metadata/de.drivetime.notifier.yml
```

The checked-in copy references `v1.0.0` for readability before the first release exists. For the actual `fdroiddata` merge request, resolve the release tag to the exact full commit SHA and use that SHA in the `Builds.commit` field if requested by current F-Droid tooling/review.

## Publication sequence

1. Merge the release branch to `main`.
2. Confirm Android CI on `main` is green.
3. Run a signed test build through **Actions → Release APK**.
4. Install and smoke-test that signed APK.
5. Run **Release APK** again with public publishing enabled for `1.0.0`.
6. Confirm GitHub Release `v1.0.0` exists and the tag points to the tested `main` commit.
7. Add anonymized screenshots to the Fastlane folders if not already present.
8. Fork `fdroid/fdroiddata` on GitLab.
9. Create `metadata/de.drivetime.notifier.yml` in the fdroiddata fork using the prepared file from this repository.
10. Replace `Builds.commit` with the exact release commit SHA if the validator or reviewer requires it.
11. Run the current F-Droid checks:

```text
fdroid readmeta
fdroid rewritemeta de.drivetime.notifier
fdroid checkupdates --allow-dirty de.drivetime.notifier
fdroid lint de.drivetime.notifier
fdroid build de.drivetime.notifier
```

12. Submit a merge request to `fdroid/fdroiddata` with title:

```text
New App: de.drivetime.notifier
```

## Signing model

The default F-Droid model is recommended for the first release:

- GitHub/direct APKs are signed with the developer keystore.
- F-Droid builds the app from source and signs the F-Droid APK with an F-Droid-managed key.

That means users should normally stay on one distribution channel. Switching between direct GitHub APK and the normal F-Droid build can require uninstalling the existing app because the signatures differ.

If identical signatures are required later, use F-Droid's reproducible-build/developer-signed-binary process instead of sharing the private signing key.

## Never publish

Do not put any of the following in this repository or fdroiddata:

- release keystore
- keystore password
- private key password
- API keys
- automation token
- personal addresses or calendar exports
