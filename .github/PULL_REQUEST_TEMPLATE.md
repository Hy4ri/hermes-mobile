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

## Screenshots

<!-- REQUIRED if this PR changes UI (anything under ui/, theme/ or drawables): drag in before/after screenshots or a screen recording. No UI change? Write N/A. -->

## Checklist

<!-- Tick every box. If one truly does not apply, tick it and add `N/A: <reason>` to the end of the line. -->

- [ ] I searched open/closed PRs and issues for duplicates
- [ ] PR is focused on one feature or one fix (no unrelated changes)
- [ ] Branch rebased onto `dev` (`git rebase origin/dev`)
- [ ] `./gradlew ktlintCheck` passes (ran `ktlintFormat` first)
- [ ] `./gradlew testDebugUnitTest` green (or CI unit-tests job)
- [ ] `checkColorLiterals` passes (no hardcoded Color outside theme/)
- [ ] No unused imports, unused parameters, or dead code
- [ ] Navigation goes through `NavigationController.navigateTo()` (not `backStack.add`)
- [ ] Icon-only actions and meaningful images have localized accessible labels (decorative ones use `contentDescription = null`)
- [ ] New screens use `HermesScaffold` and implement Loading/Error/Empty states
- [ ] For UI changes, exercised the changed behavior on a device/emulator and recorded the result above (or explicitly documented the unverified gate)
- [ ] UI changes follow DESIGN.md (contrast, touch targets, font scaling, RTL) and match similar existing screens
- [ ] Documentation matches changed behavior
- [ ] Commits follow Conventional Commits and are atomic (subject + max 2 lines of body)
