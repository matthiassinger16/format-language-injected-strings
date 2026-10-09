package com.github.mattfromger.formatlanguageinjectedstrings.formatting

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.codeStyle.CodeStyleSettings
import com.intellij.psi.impl.source.codeStyle.PostFormatProcessor
import com.intellij.psi.util.PsiTreeUtil

/**
 * Hooks into every reformat of a file (Reformat Code, Reformat File dialog, reformat on save, ...).
 *
 * The host file is still being formatted while this processor runs, so the injected fragments
 * are only collected here and formatted afterwards by [InjectedCodeFormatter].
 */
class ReformatInjectedCodePostFormatProcessor : PostFormatProcessor {
    override fun processElement(source: PsiElement, settings: CodeStyleSettings): PsiElement = source

    override fun processText(source: PsiFile, rangeToReformat: TextRange, settings: CodeStyleSettings): TextRange {
        // Skip the light copies we format ourselves and fragment editors of injected code.
        if (!source.isPhysical || InjectedLanguageManager.getInstance(source.project).isInjectedFragment(source)) {
            return rangeToReformat
        }

        val hosts = PsiTreeUtil.findChildrenOfType(source, PsiLanguageInjectionHost::class.java)
            .filter { it.textRange.intersects(rangeToReformat) }

        if (hosts.isNotEmpty()) {
            InjectedCodeFormatter.scheduleReformat(source, hosts)
        }

        return rangeToReformat
    }
}
