from pathlib import Path
import ast

from self_development.project_scanner import (
    scan_project,
    read_source,
)


def extract_structure(relative_path):
    """
    Extract classes, functions and imports
    from one Python source file.

    This is read-only.
    """

    source = read_source(relative_path)

    try:
        tree = ast.parse(source)
    except SyntaxError as error:
        return {
            "file": relative_path,
            "error": str(error),
            "functions": [],
            "classes": [],
            "imports": [],
        }

    functions = []
    classes = []
    imports = []

    for node in ast.walk(tree):

        if isinstance(
            node,
            (ast.FunctionDef, ast.AsyncFunctionDef),
        ):
            functions.append(node.name)

        elif isinstance(node, ast.ClassDef):
            classes.append(node.name)

        elif isinstance(node, ast.Import):

            for item in node.names:
                imports.append(item.name)

        elif isinstance(node, ast.ImportFrom):

            module = node.module or ""

            for item in node.names:
                imports.append(
                    f"{module}.{item.name}"
                )

    return {
        "file": relative_path,
        "functions": sorted(set(functions)),
        "classes": sorted(set(classes)),
        "imports": sorted(set(imports)),
    }


def analyze_project():
    """
    Build a structural map of Chopper.
    """

    project = []

    for relative_path in scan_project():

        try:
            information = extract_structure(
                relative_path
            )

            project.append(information)

        except Exception as error:

            project.append(
                {
                    "file": relative_path,
                    "error": str(error),
                    "functions": [],
                    "classes": [],
                    "imports": [],
                }
            )

    return project


def find_relevant_files(request):
    """
    Find source files relevant to an upgrade request.

    Uses capability knowledge plus structural matching.
    This function is read-only.
    """

    request_lower = request.lower()

    project = analyze_project()

    # --------------------------------
    # Known Chopper capabilities
    # --------------------------------
    capability_files = {
        "web": {
            "tools\\web_tool.py": 10,
            "core\\parallel_context.py": 8,
            "core\\query_understander.py": 5,
            "core\\tool_router.py": 3,
        },

        "search": {
            "tools\\web_tool.py": 10,
            "core\\parallel_context.py": 8,
            "core\\query_understander.py": 5,
        },

        "memory": {
            "memory_system\\memory.py": 10,
            "memory_system\\personal_memory.py": 8,
            "memory_system\\working_memory.py": 7,
            "memory_manager.py": 7,
        },

        "gui": {
            "gui.py": 10,
            "core\\progress.py": 5,
            "core\\response_engine.py": 4,
        },

        "model": {
            "core\\ai_router.py": 10,
            "core\\config.py": 8,
            "core\\brain.py": 6,
        },

        "routing": {
            "core\\router.py": 10,
            "core\\ai_router.py": 9,
            "core\\tool_router.py": 8,
            "core\\query_understander.py": 6,
        },

        "prompt": {
            "core\\system_prompt.py": 10,
            "core\\brain.py": 8,
            "core\\personality.py": 4,
        },

        "tool": {
            "core\\tool_router.py": 9,
            "tools\\tool_manager.py": 9,
        },
    }

    scores = {}

    # --------------------------------
    # Capability-based scoring
    # --------------------------------
    for capability, files in capability_files.items():

        if capability in request_lower:

            for file, score in files.items():

                scores[file] = max(
                    scores.get(file, 0),
                    score,
                )

    # --------------------------------
    # Structural fallback
    # --------------------------------
    request_words = {
        word.lower()
        for word in request.replace(
            "_",
            " ",
        ).split()
        if len(word) >= 4
    }

    for item in project:

        relative_path = item["file"]

        searchable = [
            relative_path.lower()
        ]

        searchable.extend(
            function.lower()
            for function in item.get(
                "functions",
                [],
            )
        )

        searchable.extend(
            class_name.lower()
            for class_name in item.get(
                "classes",
                [],
            )
        )

        searchable_text = (
            " ".join(searchable)
            .replace("_", " ")
            .replace("\\", " ")
        )

        structural_score = sum(
            1
            for word in request_words
            if word in searchable_text
        )

        if structural_score:

            scores[relative_path] = max(
                scores.get(
                    relative_path,
                    0,
                ),
                structural_score,
            )

    # --------------------------------
    # Build final results
    # --------------------------------
    project_map = {
        item["file"]: item
        for item in project
    }

    matches = []

    for file, score in scores.items():
        if score < 3:
            continue
        item = project_map.get(file)

        if item is None:
            continue

        matches.append(
            {
                "file": file,
                "score": score,
                "functions": item.get(
                    "functions",
                    [],
                ),
                "classes": item.get(
                    "classes",
                    [],
                ),
            }
        )

    matches.sort(
        key=lambda item: item["score"],
        reverse=True,
    )

    return matches


def print_matches(request):
    """Display relevant files."""

    matches = find_relevant_files(
        request
    )

    print(
        f"\nUpgrade request: {request}"
    )

    print(
        "Relevant files:"
    )

    if not matches:
        print("No matching files found.")
        return

    for item in matches[:10]:

        print(
            f"\n- {item['file']} "
            f"(score: {item['score']})"
        )

        if item["functions"]:
            print(
                "  Functions: "
                + ", ".join(
                    item["functions"][:12]
                )
            )

        if item["classes"]:
            print(
                "  Classes: "
                + ", ".join(
                    item["classes"][:12]
                )
            )


if __name__ == "__main__":

    print_matches(
        "improve web search accuracy"
    )