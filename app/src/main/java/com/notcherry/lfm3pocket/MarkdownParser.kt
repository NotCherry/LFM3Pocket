package com.notcherry.lfm3pocket

/**
 * In-house markdown subset parser (U07). No Markwon.
 * Supports: bold/italic, headings, unordered/ordered lists, fenced code, links.
 * On failure: single plain-text paragraph (never throws to callers of [parse]).
 */
sealed class MdInline {
    data class Text(val value: String) : MdInline()
    data class Bold(val children: List<MdInline>) : MdInline()
    data class Italic(val children: List<MdInline>) : MdInline()
    data class Code(val value: String) : MdInline()
    data class Link(val text: String, val url: String) : MdInline()
}

sealed class MdBlock {
    data class Paragraph(val inlines: List<MdInline>) : MdBlock()
    data class Heading(val level: Int, val inlines: List<MdInline>) : MdBlock()
    data class BulletList(val items: List<List<MdInline>>) : MdBlock()
    data class OrderedList(val items: List<List<MdInline>>) : MdBlock()
    data class CodeFence(val language: String, val code: String) : MdBlock()
}

object MarkdownParser {
    fun parse(source: String): List<MdBlock> {
        return try {
            parseBlocks(source.replace("\r\n", "\n").replace('\r', '\n'))
        } catch (_: Exception) {
            listOf(MdBlock.Paragraph(listOf(MdInline.Text(source))))
        }
    }

    /** Flatten inlines to plain text (JVM assertions / fallbacks). */
    fun plainText(blocks: List<MdBlock>): String {
        return blocks.joinToString("\n") { block ->
            when (block) {
                is MdBlock.Paragraph -> inlinesPlain(block.inlines)
                is MdBlock.Heading -> inlinesPlain(block.inlines)
                is MdBlock.BulletList -> block.items.joinToString("\n") { "• " + inlinesPlain(it) }
                is MdBlock.OrderedList -> block.items.mapIndexed { i, item ->
                    "${i + 1}. ${inlinesPlain(item)}"
                }.joinToString("\n")
                is MdBlock.CodeFence -> block.code
            }
        }
    }

    fun inlinesPlain(inlines: List<MdInline>): String = buildString {
        for (inline in inlines) {
            when (inline) {
                is MdInline.Text -> append(inline.value)
                is MdInline.Bold -> append(inlinesPlain(inline.children))
                is MdInline.Italic -> append(inlinesPlain(inline.children))
                is MdInline.Code -> append(inline.value)
                is MdInline.Link -> append(inline.text)
            }
        }
    }

    private fun parseBlocks(source: String): List<MdBlock> {
        if (source.isEmpty()) return emptyList()
        val lines = source.split('\n')
        val blocks = mutableListOf<MdBlock>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.startsWith("```") -> {
                    val language = line.removePrefix("```").trim()
                    val code = StringBuilder()
                    i++
                    while (i < lines.size && !lines[i].startsWith("```")) {
                        if (code.isNotEmpty()) code.append('\n')
                        code.append(lines[i])
                        i++
                    }
                    blocks.add(MdBlock.CodeFence(language, code.toString()))
                    if (i < lines.size) i++ // closing fence
                }
                headingLevel(line) > 0 -> {
                    val level = headingLevel(line)
                    val content = line.dropWhile { it == '#' }.trimStart()
                    blocks.add(MdBlock.Heading(level, parseInlines(content)))
                    i++
                }
                isBullet(line) -> {
                    val items = mutableListOf<List<MdInline>>()
                    while (i < lines.size && isBullet(lines[i])) {
                        items.add(parseInlines(lines[i].replaceFirst(BULLET, "").trimStart()))
                        i++
                    }
                    blocks.add(MdBlock.BulletList(items))
                }
                isOrdered(line) -> {
                    val items = mutableListOf<List<MdInline>>()
                    while (i < lines.size && isOrdered(lines[i])) {
                        items.add(parseInlines(lines[i].replaceFirst(ORDERED, "").trimStart()))
                        i++
                    }
                    blocks.add(MdBlock.OrderedList(items))
                }
                line.isBlank() -> i++
                else -> {
                    val para = StringBuilder(line)
                    i++
                    while (i < lines.size && lines[i].isNotBlank()
                        && !lines[i].startsWith("```")
                        && headingLevel(lines[i]) == 0
                        && !isBullet(lines[i])
                        && !isOrdered(lines[i])
                    ) {
                        para.append(' ').append(lines[i].trim())
                        i++
                    }
                    blocks.add(MdBlock.Paragraph(parseInlines(para.toString())))
                }
            }
        }
        return blocks
    }

    private fun headingLevel(line: String): Int {
        var n = 0
        while (n < line.length && line[n] == '#') n++
        if (n in 1..6 && n < line.length && line[n] == ' ') return n
        return 0
    }

    private fun isBullet(line: String) = BULLET.containsMatchIn(line)
    private fun isOrdered(line: String) = ORDERED.containsMatchIn(line)

    internal fun parseInlines(text: String): List<MdInline> {
        if (text.isEmpty()) return emptyList()
        val out = mutableListOf<MdInline>()
        var i = 0
        val plain = StringBuilder()
        fun flush() {
            if (plain.isNotEmpty()) {
                out.add(MdInline.Text(plain.toString()))
                plain.clear()
            }
        }
        while (i < text.length) {
            when {
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end > i + 2) {
                        flush()
                        out.add(MdInline.Bold(parseInlines(text.substring(i + 2, end))))
                        i = end + 2
                    } else {
                        plain.append(text[i]); i++
                    }
                }
                text.startsWith("__", i) -> {
                    val end = text.indexOf("__", i + 2)
                    if (end > i + 2) {
                        flush()
                        out.add(MdInline.Bold(parseInlines(text.substring(i + 2, end))))
                        i = end + 2
                    } else {
                        plain.append(text[i]); i++
                    }
                }
                text[i] == '*' && (i + 1 >= text.length || text[i + 1] != '*') -> {
                    val end = text.indexOf('*', i + 1)
                    if (end > i + 1) {
                        flush()
                        out.add(MdInline.Italic(parseInlines(text.substring(i + 1, end))))
                        i = end + 1
                    } else {
                        plain.append(text[i]); i++
                    }
                }
                text[i] == '_' && (i + 1 >= text.length || text[i + 1] != '_') -> {
                    val end = text.indexOf('_', i + 1)
                    if (end > i + 1) {
                        flush()
                        out.add(MdInline.Italic(parseInlines(text.substring(i + 1, end))))
                        i = end + 1
                    } else {
                        plain.append(text[i]); i++
                    }
                }
                text[i] == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i) {
                        flush()
                        out.add(MdInline.Code(text.substring(i + 1, end)))
                        i = end + 1
                    } else {
                        plain.append(text[i]); i++
                    }
                }
                text[i] == '[' -> {
                    val closeBracket = text.indexOf(']', i + 1)
                    if (closeBracket > i && closeBracket + 1 < text.length && text[closeBracket + 1] == '(') {
                        val closeParen = text.indexOf(')', closeBracket + 2)
                        if (closeParen > closeBracket) {
                            flush()
                            val label = text.substring(i + 1, closeBracket)
                            val url = text.substring(closeBracket + 2, closeParen)
                            out.add(MdInline.Link(label, url))
                            i = closeParen + 1
                        } else {
                            plain.append(text[i]); i++
                        }
                    } else {
                        plain.append(text[i]); i++
                    }
                }
                else -> {
                    plain.append(text[i]); i++
                }
            }
        }
        flush()
        return out
    }

    private val BULLET = Regex("""^\s*[-*+]\s+""")
    private val ORDERED = Regex("""^\s*\d+\.\s+""")
}
