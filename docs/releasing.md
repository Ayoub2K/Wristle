# Releasing the watch app

Pushing a version tag builds the release APK on GitHub and attaches it to a GitHub release
([`.github/workflows/app-release.yml`](../.github/workflows/app-release.yml)):

```bash
git tag v0.2.0
git push origin v0.2.0
```

## What is (not) in the APK

- **No API key.** It reaches the watch after install (`wristle setup` → Chat, the adb scripts or typing it).
- **No Firebase config.** The CI build has no `local.properties`, so the relay is off: the released APK offers
  **Chat**. **Code** needs a build with your own Firebase project, which `wristle setup` → Code prepares.
- **The signature** of the signing key below, so each release installs over the previous one.

## Signing key (once)

Android only installs an update when it is signed with the same key as the installed app, so releases use one
fixed key, stored as repository secrets. Create it on your computer (`keytool` ships with the JDK):

```bash
keytool -genkeypair -v -keystore wristle-release.jks -alias wristle -keyalg RSA -keysize 4096 -validity 10000
```

Keep `wristle-release.jks` and its passwords safe and out of the repo (`*.jks` is git-ignored): losing it means
users have to uninstall before the next update. Then add four secrets (GitHub → Settings → Secrets and
variables → Actions, or with the GitHub CLI):

```bash
base64 -w0 wristle-release.jks | gh secret set WRISTLE_KEYSTORE_BASE64   # macOS: base64 -i wristle-release.jks
gh secret set WRISTLE_STORE_PASSWORD
gh secret set WRISTLE_KEY_ALIAS        # wristle
gh secret set WRISTLE_KEY_PASSWORD
```

On Windows PowerShell, the first line is:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("wristle-release.jks")) | gh secret set WRISTLE_KEYSTORE_BASE64
```

To sign your own local builds with the same key, copy `keystore.properties.example` to `keystore.properties` and
fill it in. A watch that has a build signed with another key (for example the debug key) needs
`adb uninstall dev.ayoub.wristle` once before the first release installs; that also removes its chats and key.
