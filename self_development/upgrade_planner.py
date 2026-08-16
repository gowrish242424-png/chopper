from self_development.code_analyzer import (
    find_relevant_files,
)


def create_upgrade_plan(request):
    """
    Create a safe upgrade plan.

    This function only plans.
    It does NOT modify any source files.
    """

    matches = find_relevant_files(
        request
    )

    plan = {
        "request": request,
        "files": [],
        "steps": [],
    }

    # Keep only the strongest matching files
    for item in matches[:5]:
        plan["files"].append(
            {
                "file": item["file"],
                "functions": item.get(
                    "functions",
                    [],
                ),
                "score": item.get(
                    "score",
                    0,
                ),
            }
        )

    plan["steps"] = [
        "Inspect relevant source files",
        "Understand existing implementation",
        "Design proposed code changes",
        "Create a sandbox copy of Chopper",
        "Apply changes only to the sandbox",
        "Run syntax checks",
        "Run automated tests",
        "Compare sandbox with current version",
        "Prepare upgrade only if tests pass",
    ]

    return plan


def print_upgrade_plan(request):
    """Display the planned upgrade."""

    plan = create_upgrade_plan(
        request
    )

    print("\n==============================")
    print("🛠 Chopper Upgrade Plan")
    print("==============================")

    print(
        f"\nRequest:\n{plan['request']}"
    )

    print("\nRelevant files:")

    if not plan["files"]:
        print(
            "No relevant files found."
        )

    for item in plan["files"]:

        print(
            f"\n- {item['file']}"
        )

        print(
            f"  Match score: "
            f"{item['score']}"
        )

        functions = item.get(
            "functions",
            [],
        )

        if functions:
            print(
                "  Functions: "
                + ", ".join(
                    functions[:10]
                )
            )

    print("\nUpgrade steps:")

    for number, step in enumerate(
        plan["steps"],
        start=1,
    ):
        print(
            f"{number}. {step}"
        )


if __name__ == "__main__":

    print_upgrade_plan(
        "improve web search accuracy"
    )