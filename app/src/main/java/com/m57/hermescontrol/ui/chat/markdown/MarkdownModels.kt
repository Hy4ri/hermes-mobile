package com.m57.hermescontrol.ui.chat.markdown

sealed interface MdBlock {
    data class Code(
        val code: String,
        val language: String? = null,
    ) : MdBlock

    data class Math(
        val latex: String,
    ) : MdBlock

    data class Heading(
        val level: Int,
        val text: String,
    ) : MdBlock

    data class Bullet(
        val text: String,
        val level: Int = 0,
    ) : MdBlock

    data class Task(
        val checked: Boolean,
        val text: String,
        val level: Int = 0,
    ) : MdBlock

    data class Ordered(
        val index: Int,
        val text: String,
        val level: Int = 0,
    ) : MdBlock

    data class Image(
        val uri: String,
        val alt: String = "",
    ) : MdBlock

    data class Video(
        val uri: String,
        val alt: String = "",
    ) : MdBlock

    data class Quote(
        val text: String,
    ) : MdBlock

    data class Paragraph(
        val text: String,
    ) : MdBlock

    data class Table(
        val header: List<String>,
        val alignments: List<TableAlign>,
        val rows: List<List<String>>,
    ) : MdBlock

    object Hr : MdBlock

    data class DefList(
        val items: List<DefItem>,
    ) : MdBlock

    data class Footnotes(
        val notes: List<FnNote>,
    ) : MdBlock
}

data class DefItem(
    val term: String,
    val definitions: List<String>,
)

data class Footnote(
    val id: String,
    val text: String,
)

data class FnNote(
    val id: String,
    val text: String,
)

enum class TableAlign {
    LEFT,
    CENTER,
    RIGHT,
}

sealed interface InlineMathSegment {
    data class Text(
        val value: String,
    ) : InlineMathSegment

    data class Math(
        val latex: String,
    ) : InlineMathSegment
}
