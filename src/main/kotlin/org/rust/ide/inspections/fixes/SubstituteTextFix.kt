/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.inspections.fixes

import com.intellij.codeInsight.intention.FileModifier.SafeFieldForPreview
import com.intellij.codeInspection.LocalQuickFix
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.util.IntentionName
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import org.rust.lang.core.psi.ext.findPreviewCopyIfNeeded
import org.rust.openapiext.document

/**
 * Fix that removes the given range from the document and places a text onto its place.
 * @param fixName The name to use for the fix instead of the default one to better fit the inspection.
 * @param file
 * @param range The range *inside element* that will be removed from the document.
 * @param substitution The text that will be placed starting from `range.startOffset`. If `null`, no text will be inserted.
 */
class SubstituteTextFix private constructor(
    @IntentionName private val fixName: String = "Substitute",
    file: PsiFile,
    range: TextRange,
    private val substitution: String?
) : LocalQuickFix {

    @SafeFieldForPreview
    private val fileWithRange = SmartPointerManager.getInstance(file.project)
        .createSmartPsiFileRangePointer(file, range)

    override fun getName(): String = fixName
    override fun getFamilyName() = "Substitute one text to another"

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val file = fileWithRange.containingFile?.findPreviewCopyIfNeeded(descriptor.startElement.containingFile) ?: return
        val range = fileWithRange.range ?: return
        val document = file.document
        document?.deleteString(range.startOffset, range.endOffset)
        if (substitution != null) {
            document?.insertString(range.startOffset, substitution)
        }
    }

    companion object {
        fun delete(@IntentionName fixName: String, file: PsiFile, range: TextRange) =
            SubstituteTextFix(fixName, file, range, null)

        fun insert(@IntentionName fixName: String, file: PsiFile, offsetInElement: Int, text: String) =
            SubstituteTextFix(fixName, file, TextRange(offsetInElement, offsetInElement), text)

        fun replace(@IntentionName fixName: String, file: PsiFile, range: TextRange, text: String) =
            SubstituteTextFix(fixName, file, range, text)
    }
}
