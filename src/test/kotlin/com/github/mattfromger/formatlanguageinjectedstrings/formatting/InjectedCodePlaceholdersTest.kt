package com.github.mattfromger.formatlanguageinjectedstrings.formatting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InjectedCodePlaceholdersTest {
    @Test
    fun `replaces interpolations by placeholders and keeps whitespace gaps`() {
        val template = InjectedCodePlaceholders.build(
            listOf("SELECT * FROM table_", " WHERE id = ", "\n", "AND a = 1"),
            listOf("\$suffix", "\${ids.first()}", "    "),
        )!!

        assertEquals("SELECT * FROM table___ij_ph_0__ WHERE id = __ij_ph_1__\n    AND a = 1", template.text)
        assertEquals(listOf("\$suffix", "\${ids.first()}"), template.interpolations)
    }

    @Test
    fun `keeps whitespace around an interpolation outside of the placeholder`() {
        val template = InjectedCodePlaceholders.build(listOf("a", "b"), listOf("\n  \$x "))!!

        assertEquals("a\n  __ij_ph_0__ b", template.text)
        assertEquals(listOf("\$x"), template.interpolations)
    }

    @Test
    fun `rejects gaps that are no interpolations`() {
        assertNull(InjectedCodePlaceholders.build(listOf("a", "b"), listOf("\n    |")))
    }

    @Test
    fun `rejects text that already contains a placeholder`() {
        assertNull(InjectedCodePlaceholders.build(listOf("__ij_ph_0__", "b"), listOf("\$x")))
    }

    @Test
    fun `restores interpolations literally and ignores the case of placeholders`() {
        assertEquals(
            "SELECT \${'$'}1, \$a\nFROM t",
            InjectedCodePlaceholders.restore("SELECT __IJ_PH_0__, __ij_ph_1__\nFROM t", listOf("\${'$'}1", "\$a")),
        )
    }

    @Test
    fun `rejects missing, duplicated or reordered placeholders`() {
        val interpolations = listOf("\$a", "\$b")
        assertNull(InjectedCodePlaceholders.restore("__ij_ph_0__", interpolations))
        assertNull(InjectedCodePlaceholders.restore("__ij_ph_0__ __ij_ph_0__ __ij_ph_1__", interpolations))
        assertNull(InjectedCodePlaceholders.restore("__ij_ph_1__ __ij_ph_0__", interpolations))
    }

    @Test
    fun `round trips through the layout`() {
        val template = InjectedCodePlaceholders.build(listOf("\n    SELECT * FROM ", " WHERE a=1\n    "), listOf("\$table"))!!
        val formatted = InjectedCodeLayout.normalize("SELECT *\nFROM __ij_ph_0__\nWHERE a = 1")!!
        val raw = "\n    SELECT * FROM \$table WHERE a=1\n    "

        assertEquals(
            "\n    SELECT *\n    FROM \$table\n    WHERE a = 1\n    ",
            InjectedCodePlaceholders.restore(InjectedCodeLayout.layout(raw, false, formatted), template.interpolations),
        )
    }
}
