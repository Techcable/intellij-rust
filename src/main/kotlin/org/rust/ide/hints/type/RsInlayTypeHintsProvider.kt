/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.ide.hints.type

import com.intellij.codeInsight.hints.*
import com.intellij.codeInsight.hints.ImmediateConfigurable.Case
import com.intellij.codeInsight.hints.presentation.InlayPresentation
import com.intellij.codeInsight.hints.presentation.InsetPresentation
import com.intellij.codeInsight.hints.presentation.MenuOnClickPresentation
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.SyntaxTraverser
import com.intellij.psi.util.parentOfTypes
import org.rust.RsBundle
import org.rust.lang.RsLanguage
import org.rust.lang.core.macros.*
import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.*
import org.rust.lang.core.resolve.DEFAULT_RECURSION_LIMIT
import org.rust.lang.core.types.declaration
import org.rust.lang.core.types.implLookup
import org.rust.lang.core.types.infer.collectInferTys
import org.rust.lang.core.types.rawType
import org.rust.lang.core.types.ty.TyInfer
import org.rust.lang.core.types.ty.TyUnknown
import org.rust.lang.core.types.type
import org.rust.openapiext.testAssert
import javax.swing.JComponent
import javax.swing.JPanel

@Suppress("UnstableApiUsage")
class RsInlayTypeHintsProvider : InlayHintsProvider<RsInlayTypeHintsProvider.Settings> {
    override val key: SettingsKey<Settings> get() = KEY

    override val name: String get() = RsBundle.message("settings.rust.inlay.hints.title.types")

    override val previewText: String
        get() = """
            struct Foo<T1, T2, T3> { x: T1, y: T2, z: T3 }

            fn main() {
                let foo = Foo { x: 1, y: "abc", z: true };
            }
            """.trimIndent()

    override val group: InlayGroup
        get() = InlayGroup.TYPES_GROUP

    override fun createConfigurable(settings: Settings): ImmediateConfigurable = object : ImmediateConfigurable {

        override val mainCheckboxText: String
            get() = RsBundle.message("settings.rust.inlay.hints.for")

        /**
         * Each case may have:
         *  * Description provided by [InlayHintsProvider.getProperty].
         *  Property key has `inlay.%[InlayHintsProvider.key].id%.%case.id%` structure
         *
         *  * Preview taken from `resource/inlayProviders/%[InlayHintsProvider.key].id%/%case.id%.rs` file
         */
        override val cases: List<Case>
            get() = listOf(
                Case(RsBundle.message("settings.rust.inlay.hints.for.variables"), "variables", settings::showForVariables),
                Case(RsBundle.message("settings.rust.inlay.hints.for.closures"), "closures", settings::showForLambdas),
                Case(RsBundle.message("settings.rust.inlay.hints.for.loop.variables"), "loop_variables", settings::showForIterators),
                Case(RsBundle.message("settings.rust.inlay.hints.for.type.placeholders"), "type_placeholders", settings::showForPlaceholders),
                Case(RsBundle.message("settings.rust.inlay.hints.for.obvious.types"), "obvious_types", settings::showObviousTypes)
            )

        override fun createComponent(listener: ChangeListener): JComponent = JPanel()
    }

    override fun createSettings(): Settings = Settings()

    override fun getCollectorFor(file: PsiFile, editor: Editor, settings: Settings, sink: InlayHintsSink): InlayHintsCollector {
        val project = file.project
        val crate = (file as? RsFile)?.crate

        return object : FactoryInlayHintsCollector(editor) {

            val typeHintsFactory = RsTypeHintsPresentationFactory(factory, settings.showObviousTypes)

            override fun collect(element: PsiElement, editor: Editor, sink: InlayHintsSink): Boolean {
                if (project.service<DumbService>().isDumb) return true
                doCollect(element, depth = 0)
                return true
            }

            private fun doCollect(element: PsiElement, depth: Int) {
                if (element !is RsElement) return

                if (element is RsPossibleMacroCall) {
                    processMacroCall(element, depth)
                    return
                }

                if (settings.showForVariables) {
                    presentVariable(element)
                }
                if (settings.showForLambdas) {
                    presentLambda(element)
                }
                if (settings.showForIterators) {
                    presentIterator(element)
                }
            }

            private fun processMacroCall(call: RsPossibleMacroCall, depth: Int) {
                if (depth >= DEFAULT_RECURSION_LIMIT) return
                val expansion = call.expansion ?: return
                if (call.bodyTextRange?.isEmpty == true) return
                val traverser = SyntaxTraverser.psiTraverser(expansion.file)
                for (element in traverser.preOrderDfsTraversal()) {
                    doCollect(element, depth + 1)
                }
            }

            private fun presentVariable(element: RsElement) {
                when (element) {
                    is RsLetDecl -> {
                        if (settings.showForPlaceholders) {
                            presentTypePlaceholders(element)
                        }

                        if (element.typeReference != null) return

                        val pat = element.pat ?: return
                        presentTypeForPat(pat, element.expr)
                    }
                    is RsLetExpr -> {
                        val pat = element.pat ?: return
                        presentTypeForPat(pat, element.expr)
                    }
                    is RsMatchExpr -> {
                        for (arm in element.arms) {
                            presentTypeForPat(arm.pat, element.expr)
                        }
                    }
                }
            }

            private fun presentTypePlaceholders(declaration: RsLetDecl) {
                if (!declaration.existsAfterExpansion(crate)) return
                val inferredType = declaration.pat?.type ?: return
                val formalType = declaration.typeReference?.rawType ?: return
                val placeholders = formalType.collectInferTys()
                    .mapNotNull {
                        if (it is TyInfer.TyVar && it.origin is RsInferType) {
                            it to it.origin
                        } else {
                            null
                        }
                    }

                val infer = declaration.implLookup.ctx
                infer.combineTypes(inferredType, formalType)

                for ((rawType, typeElement) in placeholders) {
                    val type = infer.resolveTypeVarsIfPossible(rawType)
                    if (type is TyInfer || type is TyUnknown) continue

                    val offset = findOriginalOffset(typeElement, file) ?: continue
                    val presentation = typeHintsFactory.typeHint(type)
                    val finalPresentation = presentation.withDisableAction(declaration.project)
                    sink.addInlineElement(offset, false, finalPresentation, false)
                }
            }

            private fun presentLambda(element: RsElement) {
                if (element !is RsLambdaExpr) return

                for (parameter in element.valueParameterList.valueParameterList) {
                    if (parameter.typeReference != null) continue
                    val pat = parameter.pat ?: continue
                    presentTypeForPat(pat)
                }
            }

            private fun presentIterator(element: RsElement) {
                if (element !is RsForExpr) return

                val pat = element.pat ?: return
                presentTypeForPat(pat)
            }

            private fun presentTypeForPat(pat: RsPat, expr: RsExpr? = null) {
                if (!settings.showObviousTypes && isObvious(pat, expr?.declaration)) return

                for (binding in pat.descendantsOfType<RsPatBinding>()) {
                    if (binding.referenceName.startsWith("_")) continue
                    if (binding.reference.resolve()?.isConstantLike == true) continue
                    if (binding.type is TyUnknown) continue

                    presentTypeForBinding(binding)
                }
            }

            private fun presentTypeForBinding(binding: RsPatBinding) {
                val offset = findOriginalOffset(binding, file) ?: return
                if (!binding.existsAfterExpansion(crate)) return
                val presentation = typeHintsFactory.typeHint(binding.type)
                val finalPresentation = presentation.withDisableAction(project)
                sink.addInlineElement(offset, false, finalPresentation, false)
            }
        }
    }

    private fun InlayPresentation.withDisableAction(project: Project): InsetPresentation = InsetPresentation(
        MenuOnClickPresentation(this, project) {
            listOf(InlayProviderDisablingAction(name, RsLanguage, project, key))
        }, left = 1
    )

    data class Settings(
        var showForVariables: Boolean = true,
        var showForLambdas: Boolean = true,
        var showForIterators: Boolean = true,
        var showForPlaceholders: Boolean = true,
        var showObviousTypes: Boolean = false
    )

    companion object {
        private val KEY: SettingsKey<Settings> = SettingsKey("rust.type.hints")
    }
}

/**
 * Don't show hints in such cases:
 *
 * `let a = MyEnum::A(42);`
 * `let b = MyStruct { x: 42 };`
 */
private fun isObvious(pat: RsPat, declaration: RsElement?): Boolean =
    when (declaration) {
        is RsStructItem, is RsEnumVariant -> pat is RsPatIdent
        else -> false
    }

/**
 * When [anchor] is expanded, finds corresponding offset in [originalFile],
 * but only if macro body has enough context for showing hints:
 *
 * `foo1! { let x = 1; }` expanded to `let x = 1;` - show hints
 * `foo2!(x, 1)` expanded to `let x = 1;` - don't show hints
 */
private fun findOriginalOffset(anchor: RsElement, originalFile: PsiFile): Int? {
    if (anchor.containingFile == originalFile) return anchor.endOffset

    /** else [anchor] is expanded */
    val parent = anchor.parentOfTypes(RsStmt::class, RsLambdaExpr::class, RsMatchArm::class) ?: return null
    return findOriginalOffset(anchor, anchor.endOffset, parent.textRange, originalFile)
}

/**
 * Expansion:
 * ... let x = 1; ...
 * ~~~~~~~~ offset2
 *         ^ anchor2
 *     ~~~~~~~~~~ range2
 *
 * Original file:
 * ... foo! { let x = 1; } ...
 * ~~~~~~~~~~~~~~~ offset1
 *            ~~~~~~~~~~ range1
 *
 */
private fun findOriginalOffset(anchor2: PsiElement, offset2: Int, range2: TextRange, originalFile: PsiFile): Int? {
    testAssert { range2.contains(offset2) }
    val call = anchor2.findMacroCallExpandedFromNonRecursive() ?: return null
    val ranges = call.expansion?.ranges ?: return null
    val fileOffset = call.expansionContext.expansionFileStartOffset
    val bodyRelativeOffset = call.bodyTextRange?.startOffset ?: return null
    val offset1 = ranges.mapOffsetFromExpansionToCallBody(offset2 - fileOffset)
        ?.let { it + bodyRelativeOffset } ?: return null

    val range2Adjusted = range2.shiftLeft(fileOffset)
    val rangeBodyRelative1 = ranges.ranges.singleOrNull { it.dstRange.contains(range2Adjusted) }
        ?.let { range2Adjusted.shiftRight(it.srcOffset - it.dstOffset) } ?: return null
    if (ranges.ranges.count { it.srcRange.intersects(rangeBodyRelative1) } != 1) return null
    val range1 = rangeBodyRelative1.shiftRight(bodyRelativeOffset)
    testAssert { range1.contains(offset1) }

    return if (call.containingFile == originalFile) {
        offset1
    } else {
        findOriginalOffset(call, offset1, range1, originalFile)
    }
}
