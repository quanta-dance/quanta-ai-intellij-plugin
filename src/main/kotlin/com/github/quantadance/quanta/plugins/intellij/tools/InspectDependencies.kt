// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quantadance.quanta.plugins.intellij.tools

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiPackage
import com.intellij.psi.search.GlobalSearchScope

@JsonClassDescription("Inspect available dependency classes and methods via PSI. Useful to discover SDK APIs by package.")
class InspectDependencies : ToolInterface<InspectDependencies.Result> {
    @JsonPropertyDescription("Root package to inspect, e.g., 'com.openai.models.responses'")
    var packageName: String? = null

    @JsonPropertyDescription("Optional substring to filter class names. Supports 'a|b|c' list (OR semantics).")
    var classNameContains: String? = null

    @JsonPropertyDescription(
        "Optional substring to filter method names. Supports 'a|b|c' list (OR semantics) or regex if useRegexForMethodFilter=true.",
    )
    var methodNameContains: String? = null

    @JsonPropertyDescription("Interpret methodNameContains as a regex (default false). If false, treats 'a|b|c' as OR list of substrings.")
    var useRegexForMethodFilter: Boolean = false

    @JsonPropertyDescription("Maximum number of classes to return. Default 200.")
    var limit: Int = 200

    data class ClassInfo(
        val qualifiedName: String,
        val methods: List<String>,
    )

    data class Result(
        val packageScanned: String,
        val totalClassesFound: Int,
        val classes: List<ClassInfo>,
    )

    override fun execute(project: Project): Result {
        val pkg = packageName?.trim().orEmpty()
        if (pkg.isEmpty()) return Result("", 0, emptyList())

        return try {
            ApplicationManager.getApplication().runReadAction<Result> {
                val facade = JavaPsiFacade.getInstance(project)
                val scope = GlobalSearchScope.allScope(project)
                val root =
                    try {
                        facade.findPackage(pkg)
                    } catch (_: Throwable) {
                        null
                    }
                        ?: return@runReadAction Result(pkg, 0, emptyList())

                val out = mutableListOf<ClassInfo>()
                val filterClass = classNameContains?.takeIf { it.isNotBlank() }?.lowercase()
                val filterMethodsRaw = methodNameContains?.takeIf { it.isNotBlank() }
                val methodRegex =
                    if (useRegexForMethodFilter && filterMethodsRaw != null) {
                        runCatching {
                            Regex(filterMethodsRaw, RegexOption.IGNORE_CASE)
                        }.getOrNull()
                    } else {
                        null
                    }
                val methodTokens =
                    if (!useRegexForMethodFilter && filterMethodsRaw != null) {
                        filterMethodsRaw.split('|').map {
                            it.trim().lowercase()
                        }.filter { it.isNotEmpty() }
                    } else {
                        emptyList()
                    }

                var visitedCount = 0
                val visited = HashSet<String>()

                fun methodMatches(sigLower: String): Boolean {
                    if (methodRegex != null) return methodRegex.containsMatchIn(sigLower)
                    if (methodTokens.isEmpty()) return true
                    return methodTokens.any { sigLower.contains(it) }
                }

                fun classMatches(qnLower: String): Boolean {
                    if (filterClass.isNullOrEmpty()) return true
                    // support OR list in classNameContains using same tokenization
                    val tokens = filterClass.split('|').map { it.trim() }.filter { it.isNotEmpty() }
                    if (tokens.isEmpty()) return true
                    return tokens.any { qnLower.contains(it) }
                }

                fun visitPackage(p: PsiPackage) {
                    // classes directly in this package
                    try {
                        p.getClasses(scope).forEach { psiClass ->
                            val qn = psiClass.qualifiedName ?: return@forEach
                            if (!visited.add(qn)) return@forEach
                            visitedCount++
                            val qnLower = qn.lowercase()
                            if (!classMatches(qnLower)) return@forEach
                            val methods =
                                psiClass.methods
                                    .asSequence()
                                    .map { it.renderSignature() }
                                    .filter { methodMatches(it.lowercase()) }
                                    .toList()
                            out.add(ClassInfo(qn, methods))
                            if (out.size >= limit) return
                        }
                    } catch (_: Throwable) {
                        // ignore and continue
                    }
                    if (out.size >= limit) return
                    // recurse into subpackages
                    try {
                        p.getSubPackages(scope).forEach { sp ->
                            if (out.size >= limit) return@forEach
                            visitPackage(sp)
                        }
                    } catch (_: Throwable) {
                        // ignore
                    }
                }

                visitPackage(root)
                Result(pkg, visitedCount, out)
            }
        } catch (e: NoClassDefFoundError) {
            // Likely PSI not available in current execution context/classloader
            Result(pkg, 0, emptyList())
        } catch (_: Throwable) {
            Result(pkg, 0, emptyList())
        }
    }
}

private fun PsiMethod.renderSignature(): String {
    val name = this.name
    val params =
        parameterList.parameters.joinToString(", ") { p ->
            val t = p.type.presentableText
            val n = p.name ?: "_"
            "$t $n"
        }
    val ret = returnType?.presentableText ?: "void"
    val modifiers = this.modifierList.text.trim().replace("\n", " ")
    val cls = (containingClass?.qualifiedName ?: containingClass?.name ?: "")
    return listOf(modifiers, ret, "$cls.$name($params)").filter { it.isNotBlank() }.joinToString(" ")
}
