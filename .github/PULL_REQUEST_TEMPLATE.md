## Summary

<!-- One sentence: what + why. -->

Fixes #

## Description

<!-- Explain in your own words what this PR does and how it works at a high level. -->

## Type of Change

- [ ] 🐛 Bug fix
- [ ] ✨ Feature
- [ ] ♻️ Refactor (no behavior change)
- [ ] 📝 Docs
- [ ] ✅ Tests
- [ ] 🔧 CI / chore

## How to test

<!-- Record steps AND observed results. For UI: screen, device/emulator, API level, and behavior exercised. For WS: RPC and observed response. Name any verification that could not be run. Building/downloading a CI APK is not a device test. -->

1.
2.
3.

## Checklist

- [ ] Branch rebased onto `dev` (`git rebase origin/dev`)
- [ ] `./gradlew ktlintCheck` passes (ran `ktlintFormat` first)
- [ ] `./gradlew testDebugUnitTest` green (or CI unit-tests job)
- [ ] `checkColorLiterals` passes (no hardcoded Color outside theme/)
- [ ] Navigation goes through `NavigationController.navigateTo()` (not `backStack.add`)
- [ ] For UI changes, exercised the changed behavior on a device/emulator and recorded the result above (or explicitly documented the unverified gate)
- [ ] Documentation matches changed behavior; UI changes follow DESIGN.md
