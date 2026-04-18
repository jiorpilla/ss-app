package com.github.jiorpilla.ssapp.navigation

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.util.PsiTreeUtil
import com.jetbrains.php.PhpIndex
import com.jetbrains.php.lang.psi.elements.ArrayHashElement
import com.jetbrains.php.lang.psi.elements.MethodReference
import com.jetbrains.php.lang.psi.elements.PhpClass
import com.jetbrains.php.lang.psi.elements.StringLiteralExpression

/**
 * Provides "Go to Declaration" navigation for sv() and rs() method calls
 * from ServiceAwareTrait.
 *
 * 1st arg (resource): navigates to the Service/Repository class
 * 2nd arg (method):   navigates to the method inside that class
 *   - sv(): 'index' -> bizIndex()
 *   - rs(): 'index' -> index()
 * 3rd arg params['api']: navigates to the sub-method derived from the api value
 *   - 'show.by-info' -> showByInfo()
 */
class ResourceGotoDeclarationHandler : GotoDeclarationHandler {

    companion object {
        private val TARGET_METHODS = setOf("sv", "rs")
    }

    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor?
    ): Array<PsiElement>? {
        if (sourceElement == null) return null

        // The clicked element should be inside a string literal
        val stringLiteral = PsiTreeUtil.getParentOfType(sourceElement, StringLiteralExpression::class.java)
            ?: return null

        // Find the enclosing sv()/rs() method call
        val methodRef = PsiTreeUtil.getParentOfType(stringLiteral, MethodReference::class.java)
            ?: return null

        val calledMethod = methodRef.name ?: return null
        if (calledMethod !in TARGET_METHODS) return null

        val parameters = methodRef.parameters
        if (parameters.isEmpty()) return null

        // Determine which argument the clicked string belongs to
        val argIndex = getArgumentIndex(stringLiteral, parameters)

        return when (argIndex) {
            0 -> resolveResourceClass(stringLiteral, calledMethod)
            1 -> resolveMethod(parameters, calledMethod)
            2 -> resolveApiParam(stringLiteral, parameters, calledMethod)
            else -> null
        }
    }

    /**
     * Determines which top-level argument (0, 1, 2, ...) the given element is inside.
     */
    private fun getArgumentIndex(element: PsiElement, parameters: Array<PsiElement>): Int {
        for (i in parameters.indices) {
            if (PsiTreeUtil.isAncestor(parameters[i], element, false)) {
                return i
            }
        }
        return -1
    }

    /**
     * Arg 0: resolve resource string to the Service/Repository class.
     */
    private fun resolveResourceClass(
        stringLiteral: StringLiteralExpression,
        calledMethod: String
    ): Array<PsiElement>? {
        val resource = stringLiteral.contents
        if (resource.isBlank()) return null

        val classes = findClasses(stringLiteral.project, resource, calledMethod)
        return if (classes.isNotEmpty()) classes.toTypedArray() else null
    }

    /**
     * Arg 1: resolve method name to the actual method in the target class.
     */
    private fun resolveMethod(
        parameters: Array<PsiElement>,
        calledMethod: String
    ): Array<PsiElement>? {
        val resourceLiteral = parameters[0] as? StringLiteralExpression ?: return null
        val methodLiteral = parameters[1] as? StringLiteralExpression ?: return null

        val resource = resourceLiteral.contents
        val methodArg = methodLiteral.contents
        if (resource.isBlank() || methodArg.isBlank()) return null

        val targetMethodName = if (calledMethod == "sv") {
            "biz" + methodArg.replaceFirstChar { it.uppercaseChar() }
        } else {
            methodArg
        }

        val classes = findPhpClasses(methodLiteral.project, resource, calledMethod)
        val targets = findMethodInClasses(classes, targetMethodName)
        return if (targets.isNotEmpty()) targets.toTypedArray() else null
    }

    /**
     * Arg 2 params['api']: resolve api value to a sub-method in the target class.
     *
     * Given: rs('Developer/Example', 'index', ['api' => 'show.by-info'])
     * Navigates to: ExampleRepository::showByInfo()
     */
    private fun resolveApiParam(
        stringLiteral: StringLiteralExpression,
        parameters: Array<PsiElement>,
        calledMethod: String
    ): Array<PsiElement>? {
        // Check that the string is the value side of an 'api' => '...' pair
        val hashElement = PsiTreeUtil.getParentOfType(stringLiteral, ArrayHashElement::class.java)
            ?: return null

        // Get the key of this hash element
        val keyElement = hashElement.key
        val keyText = if (keyElement is StringLiteralExpression) keyElement.contents else return null
        if (keyText != "api") return null

        // Make sure the clicked string is the value, not the key
        if (PsiTreeUtil.isAncestor(hashElement.key, stringLiteral, false)) return null

        val resourceLiteral = parameters[0] as? StringLiteralExpression ?: return null
        val resource = resourceLiteral.contents
        if (resource.isBlank()) return null

        val apiValue = stringLiteral.contents
        if (apiValue.isBlank()) return null

        // Convert api value to method name: 'show.by-info' -> 'showByInfo'
        val targetMethodName = apiValueToMethodName(apiValue)

        val classes = findPhpClasses(stringLiteral.project, resource, calledMethod)
        val targets = findMethodInClasses(classes, targetMethodName)
        return if (targets.isNotEmpty()) targets.toTypedArray() else null
    }

    /**
     * Converts an api string like 'index.by-join' to camelCase method name 'indexByJoin'.
     */
    private fun apiValueToMethodName(apiValue: String): String {
        val parts = apiValue.split(".", "-")
        return parts.first() + parts.drop(1).joinToString("") { segment ->
            segment.replaceFirstChar { it.uppercaseChar() }
        }
    }

    // --- shared helpers ---

    private fun findPhpClasses(
        project: com.intellij.openapi.project.Project,
        resource: String,
        calledMethod: String
    ): List<PhpClass> {
        val phpIndex = PhpIndex.getInstance(project)
        val fqns = resolveResourceToFqns(resource, calledMethod)

        val classes = mutableListOf<PhpClass>()
        for (fqn in fqns) {
            classes.addAll(phpIndex.getClassesByFQN(fqn))
        }

        if (classes.isEmpty()) {
            val className = getClassName(resource, calledMethod)
            if (className != null) {
                classes.addAll(phpIndex.getClassesByName(className).filterIsInstance<PhpClass>())
            }
        }

        return classes
    }

    private fun findClasses(
        project: com.intellij.openapi.project.Project,
        resource: String,
        calledMethod: String
    ): List<PsiElement> {
        return findPhpClasses(project, resource, calledMethod)
    }

    private fun findMethodInClasses(classes: List<PhpClass>, methodName: String): List<PsiElement> {
        val targets = mutableListOf<PsiElement>()
        for (cls in classes) {
            val method = cls.findMethodByName(methodName)
            if (method != null) {
                targets.add(method)
            }
        }
        return targets
    }

    private fun resolveResourceToFqns(resource: String, methodName: String): List<String> {
        val fqns = mutableListOf<String>()
        val suffix = if (methodName == "sv") "Service" else "Repository"
        val subNamespace = if (methodName == "sv") "Services" else "Repositories"

        if (methodName == "sv" && resource.contains(":")) {
            val parts = resource.split(":", limit = 2)
            val modulePart = parts[0].replace("/", "\\")
            val classPart = parts[1].replace("/", "\\")
            fqns.add("\\App\\Modules\\$modulePart\\$subNamespace\\$classPart$suffix")
        } else {
            val parts = resource.split("/")
            if (parts.size >= 2) {
                val className = parts.last()
                val modulePath = parts.dropLast(1).joinToString("\\")
                fqns.add("\\App\\Modules\\$modulePath\\$subNamespace\\$className$suffix")
            }
        }

        return fqns
    }

    private fun getClassName(resource: String, methodName: String): String? {
        val suffix = if (methodName == "sv") "Service" else "Repository"

        return if (resource.contains(":")) {
            val parts = resource.split(":", limit = 2)
            parts[1].split("/").last() + suffix
        } else {
            val parts = resource.split("/")
            if (parts.size >= 2) parts.last() + suffix else null
        }
    }
}
