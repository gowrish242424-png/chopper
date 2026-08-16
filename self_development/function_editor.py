import ast

from self_development.project_scanner import (
    read_source,
)

from self_development.sandbox_editor import (
    replace_in_sandbox,
)


def get_function_source(
    relative_path,
    function_name,
):
    """
    Return the exact source text of one function
    from a Python file.
    """

    source = read_source(
        relative_path
    )

    tree = ast.parse(
        source
    )

    lines = source.splitlines(
        keepends=True
    )

    for node in ast.walk(tree):

        if isinstance(
            node,
            (
                ast.FunctionDef,
                ast.AsyncFunctionDef,
            ),
        ):

            if node.name == function_name:

                if not hasattr(
                    node,
                    "end_lineno",
                ):
                    raise RuntimeError(
                        "Python could not determine "
                        "the end of this function."
                    )

                start = node.lineno - 1
                end = node.end_lineno

                return "".join(
                    lines[start:end]
                )

    raise ValueError(
        f"Function not found: "
        f"{function_name}"
    )


def replace_sandbox_function(
    relative_path,
    function_name,
    new_function_code,
):
    """
    Replace one function inside the sandbox.

    Python extracts the old function exactly,
    so the AI does not need to copy old_text.
    """

    old_function = get_function_source(
        relative_path,
        function_name,
    )

    return replace_in_sandbox(
        relative_path,
        old_function,
        new_function_code,
    )


if __name__ == "__main__":

    function_text = get_function_source(
        "tools/web_tool.py",
        "rank_results",
    )

    print(
        function_text
    )