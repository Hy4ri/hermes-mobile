#!/usr/bin/env python3
"""Validate a pull request description against .github/PULL_REQUEST_TEMPLATE.md.

Usage: check_pr_template.py <event.json> [--comment]

Reads only the pull_request event payload (no PR code is executed). Exits 1 when
the description is incomplete. With --comment, also upserts a single bot comment
on the PR explaining what is missing (or marks it resolved once fixed).
Needs GITHUB_TOKEN (pull-requests: write) only for --comment. Stdlib only.
"""

import json
import os
import re
import sys
import urllib.request

MARKER = "<!-- pr-template-check -->"
TEMPLATE = "https://github.com/{repo}/blob/dev/.github/PULL_REQUEST_TEMPLATE.md"
REQUIRED = ["Summary", "Description", "Type of Change", "How to test", "Checklist"]
MIN_SUMMARY = 10
MIN_DESCRIPTION = 20
MIN_TEST = 20


def strip_comments(text):
    return re.sub(r"<!--.*?-->", "", text, flags=re.DOTALL)


def sections(body):
    parts = re.split(r"^##[ \t]+(.+?)[ \t]*$", strip_comments(body), flags=re.MULTILINE)
    return {parts[i].strip().lower(): parts[i + 1] for i in range(1, len(parts) - 1, 2)}


def prose_len(text):
    text = re.sub(r"^\s*(fixes|closes|resolves)\s*#?\s*$", "", text, flags=re.MULTILINE | re.IGNORECASE)
    text = re.sub(r"^\s*\d+\.\s*$", "", text, flags=re.MULTILINE)
    return len(text.strip())


def validate(body):
    """Return a list of (problem, how_to_fix)."""
    secs = sections(body or "")
    problems = []

    missing = [name for name in REQUIRED if name.lower() not in secs]
    if missing:
        names = ", ".join(f"`## {m}`" for m in missing)
        problems.append(
            (f"Missing template section(s): {names}.", "Re-add the headings from the PR template and fill them in.")
        )
    present = lambda name: secs.get(name.lower())  # noqa: E731

    for name, minimum, hint in [
        ("Summary", MIN_SUMMARY, "One sentence: what changed and why."),
        ("Description", MIN_DESCRIPTION, "Explain what the PR does and how it works, in your own words."),
        (
            "How to test",
            MIN_TEST,
            "Write the steps AND what you observed (screen/device/API level for UI, RPC + response for WS). "
            "If something could not be verified, say so explicitly.",
        ),
    ]:
        text = present(name)
        if text is not None and prose_len(text) < minimum:
            problems.append((f"**{name}** is empty or only placeholder text.", hint))

    types = present("Type of Change")
    if types is not None and not re.search(r"^\s*[-*]\s*\[[xX]\]", types, flags=re.MULTILINE):
        problems.append(("**Type of Change** has no box ticked.", "Tick at least one `- [x]` that matches the PR."))

    checklist = present("Checklist")
    if checklist is not None:
        unchecked = re.findall(r"^\s*[-*]\s*\[ \]\s*(.+)$", checklist, flags=re.MULTILINE)
        if unchecked:
            items = "\n".join(f"  - {u.strip()}" for u in unchecked)
            problems.append(
                (
                    f"**Checklist** has unticked items:\n{items}",
                    "Do the item and tick it. If it truly does not apply, tick it and add `N/A: <reason>` "
                    "to the end of the line.",
                )
            )
    return problems


def render(problems, repo):
    if not problems:
        return f"{MARKER}\n✅ PR description now matches the template. CI is unblocked."
    lines = [
        MARKER,
        "### 🚧 PR description is incomplete",
        "",
        "CI is **paused** until this is fixed, so no runner minutes are wasted on an unreviewable PR.",
        "",
    ]
    for i, (problem, fix) in enumerate(problems, 1):
        lines += [f"{i}. {problem}", f"   - **Fix:** {fix}", ""]
    lines += [
        "**What to do:** edit the PR description (the ••• menu → *Edit*, or the pencil on the first comment). "
        "Checks re-run automatically when you save, and this comment updates itself.",
        "",
        f"Template: {TEMPLATE.format(repo=repo)}",
    ]
    return "\n".join(lines)


def api(method, url, token, data=None):
    req = urllib.request.Request(
        url,
        method=method,
        data=json.dumps(data).encode() if data is not None else None,
        headers={
            "Authorization": f"Bearer {token}",
            "Accept": "application/vnd.github+json",
            "Content-Type": "application/json",
        },
    )
    with urllib.request.urlopen(req, timeout=30) as resp:  # noqa: S310 - fixed https host
        return json.load(resp)


def upsert_comment(repo, number, body, token, only_if_exists):
    base = f"https://api.github.com/repos/{repo}/issues"
    existing = None
    page = 1
    while existing is None:
        batch = api("GET", f"{base}/{number}/comments?per_page=100&page={page}", token)
        existing = next((c for c in batch if MARKER in c["body"]), None)
        if len(batch) < 100:
            break
        page += 1
    if existing:
        if existing["body"] != body:
            api("PATCH", f"{base}/comments/{existing['id']}", token, {"body": body})
    elif not only_if_exists:
        api("POST", f"{base}/{number}/comments", token, {"body": body})


def main():
    event = json.load(open(sys.argv[1]))
    pr = event["pull_request"]
    repo = event["repository"]["full_name"]

    # Bots (dependabot) and dev -> main release PRs do not use the template.
    if pr["user"]["type"] == "Bot" or (pr["head"]["ref"] == "dev" and pr["base"]["ref"] == "main"):
        print("PR exempt from template check.")
        return 0

    problems = validate(pr.get("body"))
    report = render(problems, repo)

    if "--comment" in sys.argv and os.environ.get("GITHUB_TOKEN"):
        try:
            upsert_comment(repo, pr["number"], report, os.environ["GITHUB_TOKEN"], only_if_exists=not problems)
        except Exception as exc:  # commenting is best-effort; the verdict is the exit code
            print(f"::warning::Could not post comment: {exc}")

    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a") as fh:
            fh.write(report.replace(MARKER, "") + "\n")
    for problem, _ in problems:
        print("::error::" + problem.replace("\n", " ").replace("**", ""))
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
