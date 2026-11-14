package com.notcherry.lfm3pocket

import org.junit.Assert.*
import org.junit.Test

class MarkdownParserTest {
    @Test
    fun boldItalicAndLink() {
        val blocks = MarkdownParser.parse("Say **hello** and *world* plus [docs](https://example.com/a).")
        assertEquals(1, blocks.size)
        val para = blocks[0] as MdBlock.Paragraph
        val plain = MarkdownParser.inlinesPlain(para.inlines)
        assertEquals("Say hello and world plus docs.", plain)
        assertTrue(para.inlines.any { it is MdInline.Bold })
        assertTrue(para.inlines.any { it is MdInline.Italic })
        val link = para.inlines.filterIsInstance<MdInline.Link>().single()
        assertEquals("docs", link.text)
        assertEquals("https://example.com/a", link.url)
    }

    @Test
    fun headingsListsAndFencedCode() {
        val md = """
            # Title
            ## Sub
            - one
            - two
            1. first
            2. second
            ```kotlin
            val x = 1
            ```
        """.trimIndent()
        val blocks = MarkdownParser.parse(md)
        assertTrue(blocks[0] is MdBlock.Heading && (blocks[0] as MdBlock.Heading).level == 1)
        assertTrue(blocks.any { it is MdBlock.Heading && it.level == 2 })
        val bullets = blocks.filterIsInstance<MdBlock.BulletList>().single()
        assertEquals(2, bullets.items.size)
        val ordered = blocks.filterIsInstance<MdBlock.OrderedList>().single()
        assertEquals(2, ordered.items.size)
        val code = blocks.filterIsInstance<MdBlock.CodeFence>().single()
        assertEquals("kotlin", code.language)
        assertEquals("val x = 1", code.code)
    }

    @Test
    fun parseFailureFallsBackToPlainParagraph() {
        // Unbalanced constructs still return a usable tree (no throw).
        val blocks = MarkdownParser.parse("**not closed and [broken](link")
        assertFalse(blocks.isEmpty())
        assertNotNull(MarkdownParser.plainText(blocks))
    }
}
