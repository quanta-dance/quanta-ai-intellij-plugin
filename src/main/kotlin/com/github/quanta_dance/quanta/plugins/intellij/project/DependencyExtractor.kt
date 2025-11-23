package com.github.quanta_dance.quanta.plugins.intellij.project

import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.LibraryOrderEntry
import com.intellij.openapi.roots.OrderEntry
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.search.GlobalSearchScope


object ImportToDependencyResolver {
    fun resolveImportsToDependencies(project: Project, psiFile: PsiFile?): Set<String> {
        val libs = HashSet<String>();

        try {
            if (psiFile is PsiJavaFile) {
                for (importStatement in psiFile.importList!!.importStatements) {
                    val importText = importStatement.qualifiedName
                    if (importText != null) {
                        val psiClass = JavaPsiFacade.getInstance(project)
                            .findClass(importText, GlobalSearchScope.allScope(project))

                        if (psiClass != null) {
                            val classFile = psiClass.containingFile.virtualFile
                            if (classFile != null) {
                                val dependency = getLibraryForClass(project, classFile)
                                if (dependency != null) {
                                    libs.add(dependency)
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            //
        }
        return libs

    }

    private fun getLibraryForClass(project: Project, classFile: VirtualFile): String? {
        val fileIndex = ProjectRootManager.getInstance(project).fileIndex
        val entries: MutableList<OrderEntry> = fileIndex.getOrderEntriesForFile(classFile)

        for (entry in entries) {
            if (entry is LibraryOrderEntry) {
                val library = entry.library
                if (library != null) {
                    return library.name // Returns dependency name (e.g., "Guava", "Commons Lang")
                }
            }
        }
        return null
    }
}