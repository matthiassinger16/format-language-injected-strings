package com.github.mattfromger.formatlanguageinjectedstrings.formatting

/**
 * Pure text helpers that place formatted injected code back into the raw text of its host literal,
 * keeping the indentation and the line layout around the string delimiters of the host.
 */
object InjectedCodeLayout {

    /**
     * Strips blank lines at the start and the end, trailing whitespace of every line and the common indentation.
     * Returns null if nothing is left.
     */
    fun normalize(text: String): List<String>? {
        val lines = text.lines()
            .map { it.trimEnd() }
            .dropWhile { it.isEmpty() }
            .dropLastWhile { it.isEmpty() }
        if (lines.isEmpty()) return null

        val commonIndent = lines.filter { it.isNotEmpty() }.minOf { line -> line.indexOfFirst { !it.isWhitespace() } }
        return lines.map { it.drop(commonIndent) }
    }

    /**
     * Builds the new raw content of the host.
     *
     * @param raw the raw text inside the host that is replaced; it must span multiple lines
     * @param startsAtLineStart whether [raw] starts at the beginning of a line (instead of right after the opening delimiter)
     * @param formattedLines the formatted code without common indentation, see [normalize]
     */
    fun layout(raw: String, startsAtLineStart: Boolean, formattedLines: List<String>): String {
        val lines = raw.split('\n')
        var first = 0
        var last = lines.size
        var leading = ""
        var trailing = ""
        var inlineFirstLine = false

        if (!startsAtLineStart) {
            if (lines.first().isBlank()) {
                // The opening delimiter is followed by a line break, keep it that way.
                leading = lines.first() + "\n"
                first = 1
            } else {
                // The code starts right after the opening delimiter.
                leading = lines.first().takeWhile { it.isWhitespace() }
                inlineFirstLine = true
            }
        }

        if (lines.size - 1 >= first && lines.last().isBlank()) {
            // The closing delimiter is on its own line, keep its indentation.
            trailing = "\n" + lines.last()
            last = lines.size - 1
        }

        val indent = lines.subList(minOf(if (inlineFirstLine) first + 1 else first, last), last)
            .filter { it.isNotBlank() }
            .map { line -> line.takeWhile { it.isWhitespace() } }
            .minByOrNull { it.length }
            ?: if (trailing.isNotEmpty()) lines.last() else ""

        val body = formattedLines.mapIndexed { index, line ->
            when {
                line.isEmpty() -> ""
                index == 0 && inlineFirstLine -> line
                else -> indent + line
            }
        }.joinToString("\n")

        return leading + body + trailing
    }
}
