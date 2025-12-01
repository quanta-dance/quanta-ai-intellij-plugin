// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.project

import com.github.quanta_dance.quanta.plugins.intellij.services.QDLog
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import java.nio.file.Paths

/**
 * Utility object that collects code references and definitions within a project.
 *
 * It provides methods to find usages of methods and classes, compute file-relative
 * paths and obtain line numbers for PSI elements.
 */
object CodeReferenceSelector {
    private val excludedPackages = setOf("java.lang", "java.util", "java.io", "java.net")
    private val logger = Logger.getInstance(CodeReferenceSelector::class.java)

    /**
     * Return the 1-based line number of the provided element using its text range and
     * the containing file's document. Returns -1 if the line number cannot be determined.
     *
     * @param method PSI element to query for line number
     * @return 1-based line number or -1 if unavailable
     */
    private fun getElementLineNumberOneBased(method: PsiElement): Int {
        val methodRange = method.textRange ?: return -1
        val startOffset = methodRange.startOffset
        val containingFile = method.containingFile ?: return -1
        val document = containingFile.viewProvider.document
        return document?.let { it.getLineNumber(startOffset) + 1 } ?: -1
    }

    /**
     * Return the 1-based line number for an arbitrary PSI element. Uses
     * PsiDocumentManager to find the document for the containing file.
     * Returns -1 if the document or file is not available.
     *
     * @param element PSI element to query
     * @return 1-based line number or -1 if unavailable
     */
    private fun getLineNumberOneBased(element: PsiElement): Int {
        val containingFile = element.containingFile ?: return -1
        val document = PsiDocumentManager.getInstance(element.project).getDocument(containingFile)
        return document?.let {
            val offset = element.textOffset
            it.getLineNumber(offset) + 1
        } ?: -1
    }

    /**
     * Find usages of a given method across the project using ReferencesSearch.
     *
     * The result is a list of human-readable strings describing where the method
     * is used. Each entry contains the method name, the line in the method file,
     * the class where it is used, the (relative when possible) file path and the
     * line number inside that file.
     *
     * The implementation is defensive: it handles missing project base path,
     * missing virtual files or documents, and returns an empty list if the search
     * fails. Standard library usages (based on excludedPackages) are filtered out.
     *
     * @param method method element to search usages for
     * @param project IntelliJ project instance used to compute relative paths
     * @return list of formatted usage descriptions
     */
    fun getMethodUsagesWithReferencesSearch(
        method: PsiMethod,
        project: Project,
    ): List<String> {
        val methodUsages = mutableListOf<String>()
        try {
            val references = ReferencesSearch.search(method, method.useScope)
            val currFileLine = getElementLineNumberOneBased(method)
            val basePath = project.basePath
            references.forEach { reference ->
                val element = reference.element ?: return@forEach
                val containingFile = element.containingFile ?: return@forEach
                val virtualFile = containingFile.virtualFile ?: return@forEach
                val containingFilePath = virtualFile.path
                if (basePath.isNullOrBlank()) {
                    // If no base path, we still want relative behaviour to fail gracefully
                    QDLog.debug(logger) { "Project base path is null or blank; skipping relative path computation." }
                }
                val relativeFilePath =
                    try {
                        if (!basePath.isNullOrBlank()) {
                            Paths.get(basePath).relativize(Paths.get(containingFilePath)).toString()
                        } else {
                            containingFilePath
                        }
                    } catch (e: Exception) {
                        // Fallback to absolute path if relativize fails
                        QDLog.debug(logger) { "Failed to relativize path: ${e.message}" }
                        containingFilePath
                    }

                val containingClass = PsiTreeUtil.getParentOfType(element, PsiClass::class.java)
                val qualifiedName = containingClass?.qualifiedName.orEmpty()
                if (containingClass != null && !isStandardLibrary(qualifiedName)) {
                    val lineNumber = getLineNumberOneBased(element)
                    methodUsages.add(
                        "Method: '${method.name}' Line ${currFileLine}\n" +
                            "Used in Class: '${containingClass.name}'\n" +
                            "File: '$relativeFilePath'\n" +
                            "Line: $lineNumber\n",
                    )
                }
            }
        } catch (e: Exception) {
            QDLog.debug(logger) { "Error searching references for method ${method.name}: ${e.message}" }
        }
        return methodUsages
    }

    /**
     * Check whether the given class name belongs to a set of excluded (standard)
     * packages. Returns false for empty class names.
     *
     * @param className fully qualified class name
     * @return true if the class name starts with any of the excluded package prefixes
     */
    private fun isStandardLibrary(className: String): Boolean = className.isNotEmpty() && excludedPackages.any { className.startsWith(it) }

    /**
     * Public entry to find references of a class. This method prepares a visited
     * set to avoid infinite recursion and duplicates, then delegates to the
     * internal recursive implementation.
     *
     * @param psiClass class to find references for
     * @return list of formatted class usage descriptions
     */
    fun findClassReferences(psiClass: PsiClass): List<String> {
        // Use a visited set to avoid recursion and duplicates
        val visited = mutableSetOf<String>()
        return findClassReferencesInternal(psiClass, visited)
    }

    /**
     * Internal recursive implementation that searches for references of a class,
     * traverses superclasses and interfaces, and accumulates formatted results.
     *
     * The method guards against missing virtual files and document data and will
     * continue working in the presence of those issues. Returns an empty list on errors.
     *
     * @param psiClass class to search references for
     * @param visited mutable set of visited class keys to avoid cycles
     * @return list of usage descriptions
     */
    private fun findClassReferencesInternal(
        psiClass: PsiClass,
        visited: MutableSet<String>,
    ): List<String> {
        val references = mutableListOf<String>()
        val classKey = psiClass.qualifiedName ?: psiClass.name ?: psiClass.hashCode().toString()
        if (visited.contains(classKey)) return references
        visited.add(classKey)

        try {
            val project = psiClass.project
            val searchScope: SearchScope = GlobalSearchScope.projectScope(project)
            val classSearchResults = ReferencesSearch.search(psiClass, searchScope)
            val basePath = project.basePath
            for (reference in classSearchResults.findAll().iterator()) {
                val psiElement = reference.element
                val file = psiElement.containingFile ?: continue
                val virtual = file.virtualFile ?: continue
                val relativeFilePath =
                    try {
                        if (!basePath.isNullOrBlank()) {
                            Paths.get(basePath).relativize(Paths.get(virtual.path)).toString()
                        } else {
                            virtual.path
                        }
                    } catch (e: Exception) {
                        virtual.path
                    }
                if (relativeFilePath.isNotEmpty()) {
                    val lineNumber = getLineNumberOneBased(psiElement)
                    references.add("Class '${psiClass.name}' used in $relativeFilePath, Line: $lineNumber")
                }
            }
        } catch (e: Exception) {
            QDLog.debug(logger) { "Error finding references for class ${psiClass.name}: ${e.message}" }
        }

        val superClass = psiClass.superClass
        if (superClass != null) {
            references.addAll(findClassReferencesInternal(superClass, visited))
        }
        for (interfaceType in psiClass.interfaces) {
            references.addAll(findClassReferencesInternal(interfaceType, visited))
        }
        return references
    }

    /**
     * Return all PsiClass instances found in the given PsiFile.
     *
     * @param file PSI file to scan
     * @return list of PsiClass elements in the file
     */
    fun getClassesFromPsiFile(file: PsiFile): List<PsiClass> = PsiTreeUtil.findChildrenOfType(file, PsiClass::class.java).toList()

    /**
     * Gather both method usages and class references present in a file.
     *
     * This method collects method declarations and classes from the provided
     * psiFile and uses search utilities to produce a combined list of reference
     * and definition descriptions.
     *
     * @param psiFile file to analyze
     * @param project current IntelliJ project used for relative path computation
     * @return list of formatted reference and definition descriptions
     */
    fun getAllReferencesAndDefinitions(
        psiFile: PsiFile,
        project: Project,
    ): List<String> {
        val methodUsages = mutableListOf<String>()
        val methodDeclarations = PsiTreeUtil.collectElementsOfType(psiFile, PsiMethod::class.java)
        val allClassReferences = mutableListOf<String>()
        val classes = getClassesFromPsiFile(psiFile)
        classes.forEach { psiClass ->
            QDLog.debug(logger) { "Found class: ${psiClass.name}" }
            allClassReferences.addAll(findClassReferences(psiClass))
        }
        methodDeclarations.forEach { method ->
            methodUsages.addAll(getMethodUsagesWithReferencesSearch(method, project))
        }
        val result = mutableListOf<String>()
        result.addAll(methodUsages)
        result.addAll(allClassReferences)
        return result
    }
}
