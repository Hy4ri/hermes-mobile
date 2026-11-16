package com.m57.hermescontrol.data.update

/** User-facing buckets for conventional-commit entries in release notes. */
enum class ReleaseNoteGroup { FEATURES, IMPROVEMENTS, FIXES }

/**
 * One block of the "What's new" list. [group] is set for entries sorted by commit type;
 * otherwise [title] is the release's own heading (null for loose text before any heading).
 */
data class ReleaseNoteSection(
    val group: ReleaseNoteGroup?,
    val title: String?,
    val items: List<String>,
)

data class ReleaseNotes(
    val sections: List<ReleaseNoteSection>,
    /** Entries hidden from the list because they do not change the app (ci, test, chore, fix(ci)...). */
    val internalChanges: Int,
    /** Distinct human authors credited in the notes, in order of first appearance. */
    val contributors: List<String>,
) {
    /** True when no entry would show in the list, e.g. a body that is only a changelog link. */
    val isEmpty: Boolean get() = sections.isEmpty()
}

/**
 * Parse a GitHub release body — usually GitHub's generated notes
 * (`* fix(chat): thing by @user in https://…/pull/N`) — into grouped, readable entries.
 * Hand-written headings and bullets are kept as their own sections.
 */
fun parseReleaseNotes(body: String): ReleaseNotes {
    val custom = LinkedHashMap<String?, MutableList<String>>()
    val grouped = LinkedHashMap<ReleaseNoteGroup, MutableList<String>>()
    val contributors = LinkedHashSet<String>()
    var internal = 0
    var heading: String? = null

    for (raw in body.lines()) {
        val line = raw.trim()
        if (line.isEmpty() || FULL_CHANGELOG.containsMatchIn(line) || HORIZONTAL_RULE.matches(line)) continue
        val headingMatch = HEADING.matchEntire(line)
        if (headingMatch != null) {
            heading = cleanInline(headingMatch.groupValues[1]).takeUnless { GENERIC_HEADING.matches(it) }
            continue
        }
        if (heading?.let(NEW_CONTRIBUTORS::matches) == true) continue

        val text = BULLET.matchEntire(line)?.groupValues?.get(1) ?: line
        val generated = GENERATED_ENTRY.matchEntire(text)
        val title = generated?.groupValues?.get(1) ?: text
        generated
            ?.groupValues
            ?.get(2)
            ?.takeUnless { it.endsWith("[bot]") }
            ?.let(contributors::add)

        val commit = CONVENTIONAL.matchEntire(title)
        val type = commit?.groupValues?.get(1)?.lowercase()
        val scopes =
            commit
                ?.groupValues
                ?.get(2)
                .orEmpty()
                .lowercase()
                .split(',', '/', ' ')
        if (type in INTERNAL_TYPES || scopes.any { it in INTERNAL_SCOPES }) {
            internal++
            continue
        }
        val item = cleanEntry(commit?.groupValues?.get(4) ?: title)
        if (item.isEmpty()) continue
        val group = groupFor(type) ?: ReleaseNoteGroup.IMPROVEMENTS.takeIf { generated != null }
        val bucket =
            if (group != null) {
                grouped.getOrPut(group) { mutableListOf() }
            } else {
                custom.getOrPut(heading) { mutableListOf() }
            }
        if (item !in bucket) bucket += item
    }

    val sections =
        custom.map { (title, items) -> ReleaseNoteSection(group = null, title = title, items = items) } +
            ReleaseNoteGroup.entries.mapNotNull { group ->
                grouped[group]?.let { ReleaseNoteSection(group = group, title = null, items = it) }
            }
    return ReleaseNotes(sections = sections, internalChanges = internal, contributors = contributors.toList())
}

private fun groupFor(type: String?): ReleaseNoteGroup? =
    when (type) {
        in FEATURE_TYPES -> ReleaseNoteGroup.FEATURES
        in FIX_TYPES -> ReleaseNoteGroup.FIXES
        in IMPROVEMENT_TYPES -> ReleaseNoteGroup.IMPROVEMENTS
        else -> null
    }

private fun cleanEntry(text: String): String {
    var out = cleanInline(text)
    while (true) {
        val trimmed = out.replace(TRAILING_ISSUE_REF, "").trim()
        if (trimmed == out) break
        out = trimmed
    }
    return out.replaceFirstChar { it.titlecase() }
}

private fun cleanInline(text: String): String =
    text
        .replace(MARKDOWN_LINK, "$1")
        .replace(EMPHASIS, "$2")
        .replace(INLINE_CODE, "$1")
        .trim()

private val FEATURE_TYPES = setOf("feat", "feature")
private val FIX_TYPES = setOf("fix", "hotfix")
private val IMPROVEMENT_TYPES = setOf("perf", "i18n", "l10n", "ui", "ux", "a11y")
private val INTERNAL_TYPES =
    setOf("chore", "ci", "test", "tests", "docs", "build", "refactor", "style", "revert", "deps")
private val INTERNAL_SCOPES = setOf("ci", "test", "tests", "build", "deps", "release", "lint", "gradle")

private val HEADING = Regex("""^#{1,6}\s+(.+?)\s*#*$""")
private val BULLET = Regex("""^[*+-]\s+(.+)$""")
private val HORIZONTAL_RULE = Regex("""^([-*_])(\s*\1){2,}$""")
private val GENERIC_HEADING = Regex("""^what'?s changed$""", RegexOption.IGNORE_CASE)
private val NEW_CONTRIBUTORS = Regex("""^new contributors$""", RegexOption.IGNORE_CASE)
private val FULL_CHANGELOG = Regex("""^\**\s*full changelog\s*\**\s*:""", RegexOption.IGNORE_CASE)
private val GENERATED_ENTRY = Regex("""^(.+?) by @([A-Za-z0-9][\w.\-\[\]]*) in https?://\S+$""")
private val CONVENTIONAL =
    Regex(
        "^(" + (FEATURE_TYPES + FIX_TYPES + IMPROVEMENT_TYPES + INTERNAL_TYPES).joinToString("|") +
            """)(?:\(([^)]*)\))?(!)?:\s*(.+)$""",
        RegexOption.IGNORE_CASE,
    )
private val TRAILING_ISSUE_REF = Regex("""\s*\((?:#\d+(?:,\s*)?)+\)$""")
private val MARKDOWN_LINK = Regex("""\[([^\]]+)]\([^)]*\)""")
private val EMPHASIS = Regex("""(\*\*|__)(.+?)\1""")
private val INLINE_CODE = Regex("""`([^`]+)`""")
