# Contributing to Hermes Mobile

Thank you for your interest in contributing to Hermes Mobile! To maintain high code quality and consistency, we ask all contributors to follow the workflow and guidelines outlined below.

---

## Before You Start: Search First

A quick duplicate check saves your time and keeps the PR queue clean:

```bash
gh search prs --repo Hy4ri/hermes-mobile --state open "<your feature>"
gh search prs --repo Hy4ri/hermes-mobile --state closed "<your feature>"
gh search issues --repo Hy4ri/hermes-mobile --state open "<your feature>"
```

If an open PR already covers it, review or improve that one instead of opening a competing duplicate. For larger work, comment on the issue to signal you're on it.

---

## PR Workflow

All code changes must go through a pull request (PR). Directly pushing to `main` is not allowed **except for trivial changes the maintainer explicitly okays** — when in doubt, open a PR.

1. **Pick or open an issue** to discuss the changes you want to make.
2. **Create a branch** off `main` using the following naming convention:
   - Features: `feat/short-description` (optionally `feat/issue-N-description`)
   - Bug fixes: `fix/short-description` (optionally `fix/issue-N-description`)
   - CI/chore: `ci/...`, `chore/...`
3. **Implement your changes** and format them locally (see Code Style).
4. **Submit a PR** targeting the `main` branch, filling the PR template.
5. **Ensure all CI checks pass** (ktlint, Android Lint, unit tests, build).
6. **Rebase onto `main` before merge.** Maintainers squash-merge; a stale branch's version of an unrelated file can silently overwrite recent fixes on `main` when squashed. `git fetch origin main && git rebase origin/main` first.

---

## Commit Messages

We follow [Conventional Commits](https://www.conventionalcommits.org/) to keep the history scannable:

- `feat:` — New feature for the user
- `fix:` — Bug fix
- `refactor:` — Code change with no functional change
- `docs:` — Documentation only
- `test:` — Adding or fixing tests
- `ci:` — CI configuration or scripts
- `chore:` — Maintenance, deps, tooling

Keep commits **atomic**: one subject line (≤72 chars) + max 2 lines of body. If it needs more, split into multiple commits.

```
fix(#431): resume last session from Room cache on cold start

Reorders init to show cached messages before WS connects,
and resumes the last session on GatewayReady instead of
always creating a blank new one.
```

---

## AI Tool Usage

If you use AI coding tools (including agents) to contribute:

- **Never** add the AI tool as author, co-author, or `Co-Authored-By` in commit metadata.
- Direct AI agents to [`AGENTS.md`](AGENTS.md) in the repo root — it contains agent-specific guidance on project conventions, build quirks, and security considerations that complement this guide.

---

## Code Style

We enforce Kotlin coding conventions and Jetpack Compose best practices.

### Kotlin Formatting

- Code formatting is checked and enforced by **ktlint 1.2.1** in CI. The authoritative command is the Gradle task (not the standalone binary):
  ```bash
  ./gradlew ktlintCheck        # check (CI-equivalent)
  ./gradlew ktlintFormat       # auto-fix, then re-check with ktlintCheck
  ```
  > ⚠️ Hand-patching indentation/import order routinely fails the real CI gate (multiline-expression-wrapping and ASCII import order are the usual casualties). Always run `ktlintFormat`, never fix formatting by hand.
- Import ordering is strictly ASCII-lexicographic (uppercase before lowercase: e.g., `LaunchedEffect` before `collectAsState`).

### Compose Guidelines

- Standard screen structures must use `HermesScaffold` rather than raw Material3 `Scaffold`.
- Composable parameters must follow the standard order: `modifier` first, then event callbacks, and finally children content.
- Do **not** apply `paddingValues` on inner content inside `HermesScaffold` — the scaffold already handles top bar padding. See `AGENTS.md` for the full breakdown of this recurring bug.
- Every data screen must implement `LoadingState`, `ErrorState`, and `EmptyState` branches in its `when { }` block.

### Color Usage

- **No hardcoded `Color(0x...)` literals** outside `theme/`, `*Preview.kt`, and auth screens. A Gradle task (`checkColorLiterals`) enforces this in CI.
- Use `MaterialTheme.colorScheme.<token>` or `LocalHermesStatusColors.current.<semantic>` instead.
- `Color.Transparent` and `Color.Unspecified` are allowed.

### Tests

- Unit tests: `./gradlew testDebugUnitTest` (MockK). Instrumented Compose UI tests run in CI on an emulator.
- **Tests must not touch real on-device storage.** Use `@get:Rule TemporaryFolder` (or Room `inMemoryDatabaseBuilder`) — never write to real app data or external storage. CI/emulator environments are ephemeral; leaking state breaks the next run.
- Mock time/dispatchers explicitly (`StandardTestDispatcher` + `Dispatchers.setMain`) — see `AGENTS.md` test references.

---

## Dependency & Build Hygiene

- **Pin build tooling.** AGP/Kotlin/SDK versions are pinned in `gradle/libs.versions.toml` and `build.gradle.kts` on purpose. Unpinned versions silently break the build on the next dependabot/CI refresh (the "SDK not writable" AGP trap). Bump deliberately, not via wildcard ranges.
- Never add a dependency without updating the version catalog; CI validates the Gradle wrapper.

---

## PR Checklist

Before submitting your PR, please verify:

- [ ] I searched open/closed PRs + issues for duplicates (see *Before You Start*).
- [ ] Branch is rebased onto current `main` (`git rebase origin/main`).
- [ ] `./gradlew ktlintCheck` passes (ran `ktlintFormat` first — no hand edits).
- [ ] `checkColorLiterals` passes (no hardcoded Color literals outside theme/).
- [ ] `./gradlew testDebugUnitTest` passes locally (or CI unit-tests job is green).
- [ ] No unused imports, unused parameters, or dead code.
- [ ] Every `Image` and `Icon` element has a descriptive `contentDescription` for accessibility.
- [ ] New screens use `HermesScaffold` and implement Loading/Error/Empty states.
- [ ] New components match the UI/UX style of similar existing screens (28+ screens for reference).
- [ ] Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/) and are atomic (subject + ≤2 lines body).
