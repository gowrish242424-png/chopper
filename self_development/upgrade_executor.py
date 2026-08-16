from self_development.sandbox_manager import (
    create_sandbox,
)

from self_development.sandbox_editor import (
    replace_in_sandbox,
)

from self_development.test_runner import (
    run_all_tests,
    print_test_report,
)


def execute_replacement_upgrade(
    relative_path,
    old_text,
    new_text,
):
    """
    Apply one exact replacement to a fresh
    sandbox and test the upgraded copy.

    The live Chopper project is never modified.
    """

    print(
        "\n========================================"
    )
    print(
        "🔧 Chopper Sandbox Upgrade"
    )
    print(
        "========================================"
    )

    print(
        "\n1. Creating fresh sandbox..."
    )

    sandbox = create_sandbox()

    print(
        f"✅ Sandbox created:\n{sandbox}"
    )

    print(
        "\n2. Applying proposed change..."
    )

    try:
        changed_file = replace_in_sandbox(
            relative_path,
            old_text,
            new_text,
        )

        print(
            f"✅ Modified sandbox file:\n"
            f"{changed_file}"
        )

    except Exception as error:

        print(
            f"❌ Upgrade edit failed:\n"
            f"{error}"
        )

        return {
            "ready": False,
            "stage": "editing",
            "error": str(error),
        }

    print(
        "\n3. Running sandbox tests..."
    )

    report = run_all_tests()

    print_test_report(
        report
    )

    if not report["passed"]:

        print(
            "\n❌ Upgrade rejected."
        )

        print(
            "The live Chopper was not changed."
        )

        return {
            "ready": False,
            "stage": "testing",
            "tests": report,
        }

    print(
        "\n========================================"
    )

    print(
        "✅ UPGRADE READY"
    )

    print(
        "All sandbox tests passed."
    )

    print(
        "The live Chopper is still unchanged."
    )

    print(
        "========================================"
    )

    return {
        "ready": True,
        "stage": "ready",
        "file": relative_path,
        "tests": report,
    }


if __name__ == "__main__":

    # Safe demonstration:
    # modify only a comment in the sandbox.

    result = execute_replacement_upgrade(
        "core/brain.py",
        "# Step 5: Build messages",
        "# Step 5: Build Chopper messages",
    )

    print(
        "\nUpgrade result:"
    )

    print(
        result["ready"]
    )