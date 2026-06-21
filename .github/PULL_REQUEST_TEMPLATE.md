## Summary

<!-- One-sentence summary of what this PR does. -->

Fixes #<!-- issue number -->

## Type of change

<!-- Check the type that applies. Delete the rest. -->

- [ ] 🐛 Bug fix
- [ ] ✨ Feature
- [ ] 🎨 UX improvement
- [ ] ♻️ Refactor
- [ ] 🔧 CI / build config
- [ ] 📝 Documentation
- [ ] 🔒 Security

## Screenshots / screen recordings

<!-- Required for UI changes. Drag & drop images or link to recordings. -->

_Before:_

_After:_

## What changed

<!--
  Describe the changes in detail — what was wrong, what was done to fix it,
  key decisions made, trade-offs considered. For UI changes, explain the
  reasoning behind layout, color, or interaction decisions.
-->

## How to test

<!--
  Steps to verify the change works correctly. Example:

  1. Open the app
  2. Navigate to …
  3. Tap …
  4. Verify that …
-->

## Checklist

- [ ] Code follows the project's style (ktlint passes)
- [ ] No new compiler warnings introduced
- [ ] If adding a new screen, checked that existing screens don't already cover the functionality (see `NavigationKeys.kt`/`ls ui/`)
- [ ] Routes all navigation through `NavigationController.navigateTo()` — no raw `backStack.add()` from UI callbacks
- [ ] Uses `HermesScaffold` instead of raw `Scaffold` for new/rewritten screens
- [ ] Room schema directory updated if entities changed
- [ ] Tested the change on a real device or CI APK
- [ ] CI is green (all checks pass: ktlint, Android Lint, unit tests, build)

## Related issues

<!--
  Link any related issues, PRs, or discussions.
  e.g., Closes #N, Refs #M, Related to #P
-->

## Notes for reviewers

<!--
  Any gotchas, follow-up work, or things to watch out for.
-->
