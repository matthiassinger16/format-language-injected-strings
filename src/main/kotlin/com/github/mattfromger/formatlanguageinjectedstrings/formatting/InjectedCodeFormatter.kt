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
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.codeStyle.CodeStyleManager

/**
 * Reformats code that is injected into string literals (e.g. via `// language=JSON` or `@Language("SQL")`).
 *
 * Only fragments that already span multiple lines are formatted, as single-line literals usually can't contain
 * line breaks. Fragments containing escape sequences or interpolations are skipped, so the
 * raw host text can be rewritten without changing anything but whitespace around the code.
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
        val (injectedFile, shreds) = injections.singleOrNull() ?: return null

        // Only handle code that lives entirely inside this host and has no (non-whitespace) prefix or suffix.
        if (shreds.isEmpty() || shreds.any { it.host != host || it.prefix.isNotBlank() || it.suffix.isNotBlank() }) {
            return null
        }
        if (LanguageFormatting.INSTANCE.forContext(injectedFile) == null) return null

        val hostText = host.text
        val ranges = shreds.map { it.rangeInsideHost }
        var start = ranges.minOf { it.startOffset }
        val end = ranges.maxOf { it.endOffset }
        if (start >= end) return null

        // If only indentation precedes the code on its first line, include it, so it gets adjusted as well.
        val lineStart = hostText.lastIndexOf('\n', start - 1) + 1
        val startsAtLineStart = lineStart > 0 && hostText.substring(lineStart, start).isBlank()
        if (startsAtLineStart) start = lineStart

        val raw = hostText.substring(start, end)
        if ('\n' !in raw) return null

        // The raw text is written back as is, so it must not contain escape sequences, interpolations or margins.
        val injectedText = injectedFile.text
        if (raw.filterNot { it.isWhitespace() } != injectedText.filterNot { it.isWhitespace() }) return null

        val input = InjectedCodeLayout.normalize(injectedText) ?: return null
        val copy = PsiFileFactory.getInstance(project)
            .createFileFromText(injectedFile.language, input.joinToString("\n"))
            ?: return null
        val formatted = InjectedCodeLayout.normalize(CodeStyleManager.getInstance(project).reformat(copy).text)
            ?: return null

        val newRaw = InjectedCodeLayout.layout(raw, startsAtLineStart, formatted)
        if (newRaw == raw) return null

        val hostOffset = host.textRange.startOffset
        return Edit(TextRange(hostOffset + start, hostOffset + end), newRaw)
    }
}
