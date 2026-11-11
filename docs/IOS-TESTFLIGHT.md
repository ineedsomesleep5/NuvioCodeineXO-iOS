# iOS beta (Nuvio XO) — build and TestFlight

This fork of [codeineXO/NuvioCodeineXO](https://github.com/codeineXO/NuvioCodeineXO)
(itself a fork of [NuvioMedia/NuvioDesktop](https://github.com/NuvioMedia/NuvioDesktop), GPL-3.0)
ships the iOS **full** distribution as a separate app for internal TestFlight testing:
bundle ID `com.calebfeliciano.nuviocodeine`, display name **Nuvio XO**.

## Why the build is split

Linking the optimized Kotlin/Native `ComposeApp.framework` needs more than 8 GB of RAM.
GitHub Actions does that step (`.github/workflows/ios-kotlin-framework.yml`, runs on every
push to `ios-testflight`). The Mac then compiles Swift, signs, archives and uploads. No
signing certificate or App Store Connect key is stored in this repository.

## Upload a beta

```bash
git push                                   # GitHub links the Kotlin framework for this commit
gh run watch                               # wait for "iOS Kotlin framework (release)" to finish
scripts/deploy-ios-testflight.sh --from-github
scripts/asc-testflight-status.py --build-number <printed build number>
```

The deploy script reads the App Store Connect key ID, issuer ID and team ID from
`~/.appstoreconnect/nuviocodeine.env` and the `.p8` key from `~/.appstoreconnect/private_keys/`.
Without `--from-github` it links the Kotlin framework locally (needs a Mac with ≥16 GB RAM).
