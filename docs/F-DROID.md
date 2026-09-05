# F-Droid publication

Drive Time Notifier is prepared for submission to the official F-Droid repository.

Version `1.0.0` is published on GitHub as tag `v1.0.0`. The F-Droid build metadata points to the exact release commit:

```text
c05f631f64c3fb4bbecd10c553172e4aa581ebcb
```

The app is MIT-licensed and contains no proprietary routing SDKs. Commercial providers such as TomTom, Google Routes and HERE are optional HTTPS services configured by the user. Free/open routing alternatives are available.

## Anti-features

The prepared metadata declares:

- `NonFreeNet`, because the UI offers optional proprietary network services.
- `NonFreeAssets`, because some provider marks are trademarked or originate from assets whose licensing is more restrictive than the app's MIT license.

This disclosure does not mean that proprietary routing SDK code is bundled into the APK.

Third-party artwork sources and licenses are documented in `THIRD_PARTY_NOTICES.md`.

## Upstream metadata

Localized store metadata is included under:

```text
fastlane/metadata/android/de-DE
fastlane/metadata/android/en-US
```

Both locales include:

- app title
- short description
- full description
- changelog for versionCode 1
- app icon
- localized phone screenshots

The screenshot files use numbered names so their display order is deterministic.

## Prepared fdroiddata file

A ready-to-copy metadata file is stored at:

```text
fdroid/metadata/de.drivetime.notifier.yml
```

It already references the full commit SHA of the published `v1.0.0` release.

## Submission sequence

1. Fork `fdroid/fdroiddata` on GitLab.
2. Create a branch in the fork for `de.drivetime.notifier`.
3. Copy this repository's prepared file to:

```text
metadata/de.drivetime.notifier.yml
```

4. Run the current F-Droid metadata and build checks:

```text
fdroid readmeta
fdroid rewritemeta de.drivetime.notifier
fdroid checkupdates --allow-dirty de.drivetime.notifier
fdroid lint de.drivetime.notifier
fdroid build de.drivetime.notifier
```

5. Resolve any validator or build-server findings before submission.
6. Submit a merge request to `fdroid/fdroiddata` with the title:

```text
New App: de.drivetime.notifier
```

A concise merge-request description should mention:

- package ID `de.drivetime.notifier`
- MIT license
- source release `v1.0.0`
- exact release commit `c05f631f64c3fb4bbecd10c553172e4aa581ebcb`
- no proprietary routing SDKs are embedded
- optional proprietary network providers are disclosed as `NonFreeNet`
- provider artwork is disclosed as `NonFreeAssets`
- free/open routing alternatives are available

## Signing model

The default F-Droid model is recommended for the first release:

- GitHub/direct APKs are signed with the developer keystore.
- F-Droid builds the app from source and signs the F-Droid APK with an F-Droid-managed key.

Users should normally stay on one distribution channel. Switching between a directly installed GitHub APK and a normal F-Droid build can require uninstalling the existing app because the signatures differ.

If identical signatures are required later, use F-Droid's reproducible-build/developer-signed-binary process instead of sharing the private signing key.

## Never publish

Do not put any of the following in this repository or fdroiddata:

- release keystore
- keystore password
- private key password
- API keys
- automation token
- personal addresses or calendar exports
