from pathlib import Path
import subprocess
import sys

from self_development.sandbox_manager import (
    SANDBOX_ROOT,
    sandbox_exists,
)


def run_command(command, timeout=60):
    """
    Run a command inside the Chopper sandbox.
    """

    try:
        result = subprocess.run(
            command,
            cwd=SANDBOX_ROOT,
            capture_output=True,
            text=True,
            timeout=timeout,
        )

        return {
            "passed": result.returncode == 0,
            "returncode": result.returncode,
            "stdout": result.stdout.strip(),
            "stderr": result.stderr.strip(),
        }

    except subprocess.TimeoutExpired:
        return {
            "passed": False,
            "returncode": -1,
            "stdout": "",
            "stderr": (
                f"Test timed out after "
                f"{timeout} seconds."
            ),
        }

    except Exception as error:
        return {
            "passed": False,
            "returncode": -1,
            "stdout": "",
            "stderr": str(error),
        }


def compile_sandbox():
    """
    Compile every Python file in the sandbox.
    """

    return run_command(
        [
            sys.executable,
            "-m",
            "compileall",
            ".",
            "-q",
        ],
        timeout=60,
    )


def test_imports():
    """
    Test important Chopper modules.
    """

    command = (
        "import core.brain; "
        "import core.query_understander; "
        "import core.ai_router; "
        "import tools.web_tool; "
        "import memory_system.memory; "
        "print('IMPORT_TEST_OK')"
    )

    return run_command(
        [
            sys.executable,
            "-c",
            command,
        ],
        timeout=60,
    )


def run_memory_test():
    """
    Run the existing non-interactive memory test.
    """

    test_file = (
        SANDBOX_ROOT
        / "test_memory.py"
    )

    if not test_file.exists():
        return {
            "passed": False,
            "returncode": -1,
            "stdout": "",
            "stderr": (
                "test_memory.py was not found."
            ),
        }

    return run_command(
        [
            sys.executable,
            "test_memory.py",
        ],
        timeout=60,
    )


def run_retrieval_test():
    """
    Run the existing retrieval test.
    """

    test_file = (
        SANDBOX_ROOT
        / "test_retrieval.py"
    )

    if not test_file.exists():
        return {
            "passed": False,
            "returncode": -1,
            "stdout": "",
            "stderr": (
                "test_retrieval.py was not found."
            ),
        }

    return run_command(
        [
            sys.executable,
            "test_retrieval.py",
        ],
        timeout=60,
    )


def run_all_tests():
    """
    Run Chopper sandbox safety checks.
    """

    if not sandbox_exists():
        return {
            "passed": False,
            "tests": {},
            "error": (
                "Sandbox does not exist."
            ),
        }

    tests = {
        "compile": compile_sandbox(),
        "imports": test_imports(),
        "memory": run_memory_test(),
        "retrieval": run_retrieval_test(),
    }

    passed = all(
        result["passed"]
        for result in tests.values()
    )

    return {
        "passed": passed,
        "tests": tests,
    }


def print_test_report(report):
    """
    Print a readable test report.
    """

    print(
        "\n========================================"
    )
    print(
        "🧪 Chopper Sandbox Test Report"
    )
    print(
        "========================================"
    )

    if "error" in report:
        print(
            f"❌ {report['error']}"
        )
        return

    for name, result in report["tests"].items():

        if result["passed"]:
            status = "✅ PASS"
        else:
            status = "❌ FAIL"

        print(
            f"\n{name.upper()}: {status}"
        )

        if result["stdout"]:
            print(
                result["stdout"]
            )

        if result["stderr"]:
            print(
                "Error:"
            )
            print(
                result["stderr"]
            )

    print(
        "\n========================================"
    )

    if report["passed"]:
        print(
            "✅ SANDBOX PASSED ALL TESTS"
        )
    else:
        print(
            "❌ SANDBOX FAILED"
        )

    print(
        "========================================"
    )


if __name__ == "__main__":

    report = run_all_tests()

    print_test_report(
        report
    )