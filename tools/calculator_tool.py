import ast
import operator


OPERATORS = {
    ast.Add: operator.add,
    ast.Sub: operator.sub,
    ast.Mult: operator.mul,
    ast.Div: operator.truediv,
    ast.FloorDiv: operator.floordiv,
    ast.Mod: operator.mod,
    ast.Pow: operator.pow,
    ast.USub: operator.neg,
    ast.UAdd: operator.pos,
}


def calculate(expression):
    """Safely evaluate a mathematical expression."""

    def evaluate(node):
        if isinstance(node, ast.Constant):
            if isinstance(node.value, (int, float)):
                return node.value
            raise ValueError("Only numbers are allowed.")

        if isinstance(node, ast.BinOp):
            operation = OPERATORS.get(type(node.op))

            if operation is None:
                raise ValueError("Unsupported operator.")

            return operation(
                evaluate(node.left),
                evaluate(node.right),
            )

        if isinstance(node, ast.UnaryOp):
            operation = OPERATORS.get(type(node.op))

            if operation is None:
                raise ValueError("Unsupported operator.")

            return operation(evaluate(node.operand))

        raise ValueError("Invalid expression.")

    expression = expression.replace("^", "**")
    tree = ast.parse(expression, mode="eval")
    return evaluate(tree.body)


def run_calculator(user_message):
    try:
        return str(calculate(user_message))

    except (
        SyntaxError,
        ValueError,
        ZeroDivisionError,
        OverflowError,
    ):
        return "I couldn't calculate that expression."