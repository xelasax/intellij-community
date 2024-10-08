// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.kotlin.idea.k2.codeinsight.inspections

import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.codeInspection.util.IntentionFamilyName
import com.intellij.modcommand.ModPsiUpdater
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.endOffset
import com.intellij.psi.util.prevLeafs
import com.intellij.psi.util.startOffset
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.analyze
import org.jetbrains.kotlin.analysis.api.resolution.successfulFunctionCallOrNull
import org.jetbrains.kotlin.idea.base.psi.imports.addImport
import org.jetbrains.kotlin.idea.base.psi.replaced
import org.jetbrains.kotlin.idea.base.resources.KotlinBundle
import org.jetbrains.kotlin.idea.codeinsight.api.applicable.inspections.KotlinModCommandQuickFix
import org.jetbrains.kotlin.idea.gradleJava.configuration.utils.Replacement
import org.jetbrains.kotlin.idea.gradleJava.configuration.utils.kotlinVersionIsEqualOrHigher
import org.jetbrains.kotlin.idea.gradleJava.configuration.utils.getReplacementForOldKotlinOptionIfNeeded
import org.jetbrains.kotlin.idea.k2.codeinsight.inspections.utils.AbstractKotlinGradleScriptInspection
import org.jetbrains.kotlin.idea.k2.codeinsight.quickFixes.createFromUsage.K2CreateFunctionFromUsageUtil.resolveExpression
import org.jetbrains.kotlin.idea.util.application.isUnitTestMode
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtReferenceExpression
import org.jetbrains.kotlin.psi.KtVisitorVoid

private val kotlinCompileTasksNames = setOf("org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile",
                                            "org.jetbrains.kotlin.gradle.tasks.KotlinCompile",
                                            "org.jetbrains.kotlin.gradle.tasks.Kotlin2JsCompile",
                                            "org.jetbrains.kotlin.gradle.dsl.KotlinJsCompile")

internal class KotlinOptionsToCompilerOptionsInGradleScriptInspection : AbstractKotlinGradleScriptInspection() {

    override fun isAvailableForFile(file: PsiFile): Boolean {
        if (super.isAvailableForFile(file)) {
            if (isUnitTestMode()) {
                // Inspection tests don't treat tested build script files properly, and thus they ignore Kotlin versions used in scripts
                return true
            } else {
                return kotlinVersionIsEqualOrHigher(major = 2, minor = 0, patch = 0, file)
            }
        } else {
            return false
        }
    }

    @OptIn(KaExperimentalApi::class)
    override fun buildVisitor(
        holder: ProblemsHolder,
        isOnTheFly: Boolean
    ) = object : KtVisitorVoid() {

        override fun visitReferenceExpression(expression: KtReferenceExpression) {
            if (expression.text.equals("kotlinOptions")) {

                if (isDescendantOfDslInWhichReplacementIsNotNeeded(expression)) return

                val expressionParent = expression.parent

                if (!isUnitTestMode()) { // ATM, we don't have proper dependencies for tests on Gradle build scripts
                    analyze(expression) {
                        val jvmClassForKotlinCompileTask = expression.resolveToCall()
                            ?.successfulFunctionCallOrNull()?.partiallyAppliedSymbol?.signature?.symbol?.containingJvmClassName
                            ?: expression.resolveExpression()?.containingSymbol?.importableFqName?.toString() ?: return
                        if (!kotlinCompileTasksNames.contains(jvmClassForKotlinCompileTask)) {
                            return
                        }
                    }
                }
                when (expressionParent) {
                    is KtDotQualifiedExpression -> { // like `kotlinOptions.sourceMapEmbedSources`
                        val parentOfExpressionParent = expressionParent.parent
                        if (parentOfExpressionParent !is KtBinaryExpression) return // like kotlinOptions.sourceMapEmbedSources = "inlining"
                    }

                    is KtCallExpression -> {
                        /*
                        Like the following. Raise a problem for this.
                        compileKotlin.kotlinOptions {
                            jvmTarget = "1.8"
                            freeCompilerArgs += listOf("-module-name", "TheName")
                            apiVersion = "1.9"
                        }
                         */
                    }

                    else -> return
                }

                holder.problem(
                    expression,
                    KotlinBundle.message("inspection.kotlin.options.to.compiler.options.display.name")
                )
                    .highlight(ProblemHighlightType.GENERIC_ERROR_OR_WARNING)
                    .range(
                        TextRange(
                            expression.startOffset,
                            expression.endOffset
                        ).shiftRight(-expression.startOffset)
                    )
                    .fix(
                        ReplaceKotlinOptionsWithCompilerOptionsFix()
                    ).register()
            }
        }
    }

    private fun isDescendantOfDslInWhichReplacementIsNotNeeded(ktExpression: KtExpression): Boolean {
        val scriptText = ktExpression.containingFile.text
            if (scriptText.contains("android")) {
                ktExpression.prevLeafs.forEach {
                    if ("android" == it.text) {
                        println("android")
                        return true
                    }
                }
            }
        return false
    }
}

private class ReplaceKotlinOptionsWithCompilerOptionsFix() : KotlinModCommandQuickFix<KtExpression>() {
    override fun getFamilyName(): @IntentionFamilyName String {
        return KotlinBundle.message("replace.kotlin.options.with.compiler.options")
    }

    override fun applyFix(
        project: Project,
        element: KtExpression,
        updater: ModPsiUpdater
    ) {

        val expressionsToFix = mutableListOf<Replacement>()
        val expressionParent = element.parent
        when (expressionParent) {
            is KtDotQualifiedExpression -> { // for sth like `kotlinOptions.sourceMapEmbedSources`
                val parentOfExpressionParent = expressionParent.parent
                if (parentOfExpressionParent is KtBinaryExpression) { // for sth like `kotlinOptions.sourceMapEmbedSources = "inlining"`
                    getReplacementForOldKotlinOptionIfNeeded(parentOfExpressionParent)?.let { expressionsToFix.add(it) }
                }
            }

            is KtCallExpression -> {
                /* Example:
                compileKotlin.kotlinOptions {
                    jvmTarget = "1.8"
                    freeCompilerArgs += listOf("-module-name", "TheName")
                    apiVersion = "1.9"
                }

                OR
                tasks.withType<KotlinCompile> {
                    kotlinOptions {
                        freeCompilerArgs += listOf("-module-name", "TheName")
                    }
                }
                */

                expressionsToFix.add(Replacement(element, "compilerOptions"))

                val lambdaStatements = expressionParent.lambdaArguments.getOrNull(0)
                    ?.getLambdaExpression()?.bodyExpression?.statements?.requireNoNulls()

                /**
                 * Test case:
                 * K2LocalInspectionTestGenerated.InspectionsLocal.KotlinOptionsToCompilerOptions#testLambdaWithSeveralStatements_gradle())
                 */
                if (lambdaStatements?.isNotEmpty() == true) { // compileKotlin.kotlinOptions { .. }
                    lambdaStatements.forEach {
                        searchAndProcessBinaryExpressionChildren(it, expressionsToFix)
                    }
                }
            }
        }

        expressionsToFix.forEach {
            val newExpression = KtPsiFactory(project).createExpression(it.replacement)

            val replacedElement = it.expressionToReplace.replaced(newExpression)

            val classToImport = it.classToImport
            if (classToImport != null) {
                (replacedElement.containingFile as? KtFile)?.addImport(classToImport)
            }
        }
    }

    /**
     * Test case:
     * K2LocalInspectionTestGenerated.InspectionsLocal.KotlinOptionsToCompilerOptions#testDontMergeConvertedOptionsToAnotherCompilerOptions_gradle
     */
    private fun searchAndProcessBinaryExpressionChildren(element: PsiElement, expressionsToFix: MutableList<Replacement>) {
        if (element is KtBinaryExpression) { // for sth like `kotlinOptions.sourceMapEmbedSources = "inlining"`
            getReplacementForOldKotlinOptionIfNeeded(element)?.let { expressionsToFix.add(it) }
        } else {
            element.children.forEach {
                searchAndProcessBinaryExpressionChildren(it, expressionsToFix)
            }
        }
    }
}

