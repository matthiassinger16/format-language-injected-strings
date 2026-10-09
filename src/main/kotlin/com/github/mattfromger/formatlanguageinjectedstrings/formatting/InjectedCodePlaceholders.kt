package com.github.mattfromger.formatlanguageinjectedstrings.formatting

/**
 * Replaces string interpolations (e.g. Kotlin's `$name` or `${expression}`) between the parts of injected code
 * with placeholder identifiers, so the code can be formatted as a whole, and puts them back afterwards.
 */
object InjectedCodePlaceholders {
    private val PLACEHOLDER = Regex("__ij_ph_(\\d+)__", RegexOption.IGNORE_CASE)

    /**
     * @property text the code with placeholders instead of interpolations
     * @property interpolations the replaced interpolations, in the order of their placeholders
     */
    class Template(val text: String, val interpolations: List<String>)

    /**
     * Joins the [segments] of injected code with the raw host text in the [gaps] between them.
     * Gaps consisting of whitespace only (e.g. indentation) are kept, interpolations are replaced by placeholders.
     *
     * Returns null if a gap is neither whitespace nor an interpolation (e.g. a `trimMargin()` margin),
     * or if the text already contains something that looks like a placeholder.
     */
    fun build(segments: List<String>, gaps: List<String>): Template? {
        require(segments.size == gaps.size + 1) { "Expected one gap between each two segments" }
        if ((segments + gaps).any { PLACEHOLDER.containsMatchIn(it) }) return null

        val text = StringBuilder(segments.first())
        val interpolations = mutableListOf<String>()
        gaps.forEachIndexed { index, gap ->
            if (gap.isBlank()) {
                text.append(gap)
            } else {
                val interpolation = gap.trim()
                if (!interpolation.startsWith('$')) return null

                text.append(gap.takeWhile { it.isWhitespace() })
                text.append(placeholder(interpolations.size))
                text.append(gap.takeLastWhile { it.isWhitespace() })
                interpolations.add(interpolation)
            }
            text.append(segments[index + 1])
        }
        return Template(text.toString(), interpolations)
    }

    /**
     * Replaces the placeholders in [text] by the [interpolations] they stand for.
     * Returns null unless every placeholder occurs exactly once and in its original order.
     */
    fun restore(text: String, interpolations: List<String>): String? {
        val indices = PLACEHOLDER.findAll(text).map { it.groupValues[1].toInt() }.toList()
        if (indices != interpolations.indices.toList()) return null

        return PLACEHOLDER.replace(text) { interpolations[it.groupValues[1].toInt()] }
    }

    private fun placeholder(index: Int) = "__ij_ph_${index}__"
}
