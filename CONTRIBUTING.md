# Contributing to Hermes Mobile

Thank you for your interest in contributing to Hermes Mobile! To maintain high code quality and consistency, we ask all contributors to follow the workflow and guidelines outlined below.

---

## PR Workflow

All code changes must go through a pull request (PR). Directly pushing to `main` is not allowed.

1. **Pick or open an issue** to discuss the changes you want to make.
2. **Create a branch** off `main` using the following naming convention:
   - Features: `feat/issue-N-description`
   - Bug fixes: `fix/issue-N-description`
3. **Implement your changes** and format them locally.
4. **Submit a PR** targeting the `main` branch.
5. **Ensure all CI checks pass** (formatting, linting, and unit tests).
6. **Maintainers will squash-merge** your PR into `main`.

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
- Code formatting is checked and enforced by **ktlint 1.2.1**.
- Run formatting check locally before committing:
  ```bash
  ./ktlint <file>            # Check one file
  ./ktlint --format <file>   # Auto-fix formatting issues
  ```
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

---

## PR Checklist

Before submitting your PR, please verify:

- [ ] Your branch is up-to-date with `main`.
- [ ] Local build and `ktlint` checks pass successfully.
- [ ] `checkColorLiterals` passes (no hardcoded Color literals outside theme/).
- [ ] No unused imports, unused parameters, or dead code.
- [ ] Every `Image` and `Icon` element has a descriptive `contentDescription` for accessibility.
- [ ] New screens use `HermesScaffold` and implement Loading/Error/Empty states.
- [ ] New components match the UI/UX style of similar existing screens (28+ screens for reference).
- [ ] Commit messages follow [Conventional Commits](https://www.conventionalcommits.org/) and are atomic (subject + ≤2 lines body).
