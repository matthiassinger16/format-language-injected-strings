package com.github.mattfromger.formatlanguageinjectedstrings.formatting

import com.intellij.lang.LanguageFormatting
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.codeStyle.CodeStyleManager
import com.intellij.psi.util.PsiTreeUtil

/**
 * Reformats code that is injected into string literals (e.g. via `// language=JSON` or `@Language("SQL")`).
 *
 * Only fragments that already span multiple lines are formatted, as single-line literals usually can't contain
 * line breaks. Fragments containing escape sequences are skipped, so the raw host text can be rewritten without
 * changing anything but whitespace around the code. Interpolations (`$name`, `${expression}`) are replaced by
 * placeholders while formatting and restored afterwards.
 */
object InjectedCodeFormatter {
    private const val COMMAND_NAME = "Reformat Injected Code"

    private data class Edit(val range: TextRange, val text: String)

    fun scheduleReformat(file: PsiFile, hosts: List<PsiLanguageInjectionHost>) {
        val project = file.project
        // The host file is in the middle of being formatted, so wait until that has finished.
        ApplicationManager.getApplication().invokeLater({ reformat(project, file, hosts) }, project.disposed)
    }

    private fun reformat(project: Project, file: PsiFile, hosts: List<PsiLanguageInjectionHost>) {
        if (!file.isValid) return
        val documentManager = PsiDocumentManager.getInstance(project)
        val document = documentManager.getDocument(file) ?: return
        documentManager.commitDocument(document)

        WriteCommandAction.runWriteCommandAction(project, COMMAND_NAME, null, Runnable {
            val edits = hosts
                .filter { it.isValid && it.containingFile == file }
                .mapNotNull { host ->
                    try {
                        computeEdit(project, host)
                    } catch (e: ProcessCanceledException) {
                        throw e
                    } catch (e: Exception) {
                        thisLogger().warn("Failed to reformat injected code in ${host.text}", e)
                        null
                    }
                }
                .sortedByDescending { it.range.startOffset }

            // Apply from the end of the document, so the offsets of the remaining edits stay valid.
            var lowestChangedOffset = Int.MAX_VALUE
            for (edit in edits) {
                if (edit.range.endOffset > lowestChangedOffset) continue // overlapping (nested) host
                document.replaceString(edit.range.startOffset, edit.range.endOffset, edit.text)
                lowestChangedOffset = edit.range.startOffset
            }
            documentManager.commitDocument(document)
        }, file)
    }

    private fun computeEdit(project: Project, host: PsiLanguageInjectionHost): Edit? {
        val injections = mutableListOf<Pair<PsiFile, List<PsiLanguageInjectionHost.Shred>>>()
        InjectedLanguageManager.getInstance(project).enumerate(host) { injectedFile, shreds ->
            injections.add(injectedFile to shreds.toList())
        }
        val (injectedFile, unsortedShreds) = injections.singleOrNull() ?: return null
        val shreds = unsortedShreds.sortedBy { it.rangeInsideHost.startOffset }

        // Only handle code that lives entirely inside this host and has no (non-whitespace) prefix or suffix.
        if (shreds.isEmpty() || shreds.any { it.host != host }) return null
        if (shreds.first().prefix.isNotBlank() || shreds.last().suffix.isNotBlank()) return null
        if (shreds.zipWithNext().any { (a, b) -> a.rangeInsideHost.endOffset > b.rangeInsideHost.startOffset }) return null
        if (LanguageFormatting.INSTANCE.forContext(injectedFile) == null) return null

        val hostText = host.text
        val segments = shreds.map { it.rangeInsideHost.substring(hostText) }
        val gaps = shreds.zipWithNext { a, b -> hostText.substring(a.rangeInsideHost.endOffset, b.rangeInsideHost.startOffset) }

        // Text the injection inserts between two parts must stand in for an interpolation that is restored later.
        if (gaps.indices.any { gaps[it].isBlank() && (shreds[it].suffix.isNotBlank() || shreds[it + 1].prefix.isNotBlank()) }) {
            return null
        }

        // The raw text is written back as is, so it must not contain escape sequences.
        val expectedText = shreds.indices.joinToString("") { shreds[it].prefix + segments[it] + shreds[it].suffix }
        if (expectedText.filterNot { it.isWhitespace() } != injectedFile.text.filterNot { it.isWhitespace() }) return null

        val template = InjectedCodePlaceholders.build(segments, gaps) ?: return null

        var start = shreds.first().rangeInsideHost.startOffset
        val end = shreds.last().rangeInsideHost.endOffset
        if (start >= end) return null

        // If only indentation precedes the code on its first line, include it, so it gets adjusted as well.
        val lineStart = hostText.lastIndexOf('\n', start - 1) + 1
        val startsAtLineStart = lineStart > 0 && hostText.substring(lineStart, start).isBlank()
        if (startsAtLineStart) start = lineStart

        val raw = hostText.substring(start, end)
        if ('\n' !in raw) return null

        val input = InjectedCodeLayout.normalize(template.text) ?: return null
        val copy = PsiFileFactory.getInstance(project)
            .createFileFromText(injectedFile.language, input.joinToString("\n"))
            ?: return null
        // A placeholder in a position where the language doesn't accept an identifier could mess up the formatting.
        if (countErrors(copy) > countErrors(injectedFile)) return null

        val formatted = InjectedCodeLayout.normalize(CodeStyleManager.getInstance(project).reformat(copy).text)
            ?: return null

        val newRaw = InjectedCodePlaceholders.restore(
            InjectedCodeLayout.layout(raw, startsAtLineStart, formatted),
            template.interpolations,
        ) ?: return null
        if (newRaw == raw) return null
        // Make sure the formatter didn't change the code around a placeholder, e.g. by quoting it.
        if (template.interpolations.isNotEmpty() &&
            !newRaw.filterNot { it.isWhitespace() }.equals(raw.filterNot { it.isWhitespace() }, ignoreCase = true)
        ) {
            return null
        }

        val hostOffset = host.textRange.startOffset
        return Edit(TextRange(hostOffset + start, hostOffset + end), newRaw)
    }

    private fun countErrors(file: PsiFile): Int = PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java).size
}
