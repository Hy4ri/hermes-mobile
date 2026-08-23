## Summary

<!-- One sentence: what + why. -->

Fixes #

## Type of Change

- [ ] 🐛 Bug fix
- [ ] ✨ Feature
- [ ] ♻️ Refactor (no behavior change)
- [ ] 📝 Docs
- [ ] ✅ Tests
- [ ] 🔧 CI / chore

## What changed

<!-- What was wrong and what this PR does about it. Link the root cause if known. -->

## How to test

<!-- Steps to verify. For UI: which screen + which emulator/device API level. For WS: which RPC. -->

1.
2.
3.

## Checklist

- [ ] Branch rebased onto `main` (`git rebase origin/main`)
- [ ] `./gradlew ktlintCheck` passes (ran `ktlintFormat` first)
- [ ] `./gradlew testDebugUnitTest` green (or CI unit-tests job)
- [ ] `checkColorLiterals` passes (no hardcoded Color outside theme/)
- [ ] Navigation goes through `NavigationController.navigateTo()` (not `backStack.add`)
- [ ] Tested on device/emulator or verified via CI APK
