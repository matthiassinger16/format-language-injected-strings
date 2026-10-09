package com.github.mattfromger.formatlanguageinjectedstrings.formatting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InjectedCodeLayoutTest {
    private val json = listOf("{", "  \"a\": 1", "}")

    @Test
    fun `normalize strips surrounding blank lines, trailing whitespace and common indentation`() {
        assertEquals(listOf("a", "  b"), InjectedCodeLayout.normalize("\n\n    a  \n      b\n\n   "))
        assertNull(InjectedCodeLayout.normalize("  \n "))
    }

    @Test
    fun `keeps line breaks after the opening and before the closing delimiter`() {
        assertEquals(
            "\n        {\n          \"a\": 1\n        }\n        ",
            InjectedCodeLayout.layout("\n        {\"a\":1}\n        ", false, json),
        )
    }

    @Test
    fun `indents code that starts at the beginning of a line`() {
        assertEquals(
            "        {\n          \"a\": 1\n        }\n        ",
            InjectedCodeLayout.layout("        {\"a\":1}\n        ", true, json),
        )
    }

    @Test
    fun `keeps code that starts right after the opening delimiter on that line`() {
        assertEquals(
            "{\n      \"a\": 1\n    }",
            InjectedCodeLayout.layout("{\"a\":\n    1}", false, json),
        )
    }

    @Test
    fun `uses the indentation of the closing delimiter if there is no other code`() {
        assertEquals(
            "\n  x\n\n    y\n  ",
            InjectedCodeLayout.layout("\n\n  ", false, listOf("x", "", "  y")),
        )
    }

    @Test
    fun `is idempotent`() {
        val once = InjectedCodeLayout.layout("\n        {\"a\":1}\n        ", false, json)
        assertEquals(once, InjectedCodeLayout.layout(once, false, json))
    }
}
