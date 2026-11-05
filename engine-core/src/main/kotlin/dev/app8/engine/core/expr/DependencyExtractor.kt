package dev.app8.engine.core.expr

/** Extracts the variable names an expression depends on (Swift `DependencyExtractor`). */
public class DependencyExtractor {

    public fun extractDependencies(node: ExpressionNode): Set<String> {
        val dependencies = LinkedHashSet<String>()
        extract(node, dependencies)
        return dependencies
    }

    private fun extract(node: ExpressionNode, into: MutableSet<String>) {
        when (node) {
            is ExpressionNode.Variable -> into.add(node.name)
            is ExpressionNode.BinaryOperation -> {
                extract(node.left, into)
                extract(node.right, into)
            }
            is ExpressionNode.UnaryOperation -> extract(node.operand, into)
            is ExpressionNode.FunctionCall -> {
                if (node.name in HIGHER_ORDER_FUNCTIONS && node.arguments.size >= 2) {
                    extract(node.arguments[0], into)
                    // Predicate: exclude the synthetic loop variable `item`. Arguments past the
                    // predicate are ignored, as on iOS.
                    val predicateDeps = LinkedHashSet<String>()
                    extract(node.arguments[1], predicateDeps)
                    predicateDeps.remove("item")
                    into.addAll(predicateDeps)
                } else {
                    for (arg in node.arguments) extract(arg, into)
                }
            }
            is ExpressionNode.MemberAccess -> extract(node.obj, into)
            is ExpressionNode.ArrayAccess -> {
                extract(node.array, into)
                extract(node.index, into)
            }
            is ExpressionNode.Ternary -> {
                extract(node.condition, into)
                extract(node.trueValue, into)
                extract(node.falseValue, into)
            }
            is ExpressionNode.Literal -> Unit
        }
    }

    private companion object {
        // `last` is intentionally absent, matching iOS.
        val HIGHER_ORDER_FUNCTIONS = setOf("filter", "map", "find", "first")
    }
}
