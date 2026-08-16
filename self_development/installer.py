from pathlib import Path
import shutil
import subprocess
import sys

from self_development.sandbox_manager import (
    SANDBOX_ROOT,
    sandbox_exists,
    sandbox_file,
)

from self_development.backup_manager import (
    PROJECT_ROOT,
    create_backup,
    restore_file_from_backup,
)

from self_development.test_runner import (
    run_all_tests,
)


ALLOWED_EXTENSIONS = {
    ".py",
    ".json",
    ".txt",
    ".md",
}


def live_file(relative_path):
    """
    Return a safe path inside the live
    Chopper project.
    """

    path = (
        PROJECT_ROOT
        / relative_path
    ).resolve()

    try:
        path.relative_to(
            PROJECT_ROOT.resolve()
        )

    except ValueError:
        raise ValueError(
            "Live project path escape detected."
        )

    return path


def verify_live_project():
    """
    Compile the live Chopper project after
    installation.
    """

    try:
        result = subprocess.run(
            [
                sys.executable,
                "-m",
                "compileall",
                ".",
                "-q",
            ],
            cwd=PROJECT_ROOT,
            capture_output=True,
            text=True,
            timeout=60,
        )

        return {
            "passed": result.returncode == 0,
            "stdout": result.stdout.strip(),
            "stderr": result.stderr.strip(),
        }

    except Exception as error:
        return {
            "passed": False,
            "stdout": "",
            "stderr": str(error),
        }


def install_sandbox_file(relative_path):
    """
    Install ONE tested sandbox file into
    the live Chopper project.

    Safety order:

    1. Verify sandbox exists
    2. Run sandbox tests
    3. Create live backup
    4. Install sandbox file
    5. Verify live project
    6. Roll back automatically on failure
    """

    print(
        "\n========================================"
    )
    print(
        "🚀 Chopper Controlled Installer"
    )
    print(
        "========================================"
    )

    # --------------------------------
    # 1. Validate sandbox
    # --------------------------------

    if not sandbox_exists():
        raise RuntimeError(
            "Sandbox does not exist."
        )

    source = sandbox_file(
        relative_path
    )

    destination = live_file(
        relative_path
    )

    if not source.exists():
        raise FileNotFoundError(
            f"Sandbox file not found: "
            f"{relative_path}"
        )

    if not source.is_file():
        raise ValueError(
            "Only files can be installed."
        )

    if source.suffix.lower() not in ALLOWED_EXTENSIONS:
        raise ValueError(
            f"Installing {source.suffix} "
            "files is not allowed."
        )

    # For the first version, only replace
    # files that already exist in live Chopper.
    if not destination.exists():
        raise FileNotFoundError(
            "Live destination does not exist. "
            "Creating new live files is not "
            "enabled yet."
        )

    print(
        f"\nTarget:\n{relative_path}"
    )

    # --------------------------------
    # 2. Test sandbox
    # --------------------------------

    print(
        "\n1. Running sandbox tests..."
    )

    sandbox_report = run_all_tests()

    if not sandbox_report["passed"]:

        print(
            "❌ Sandbox tests failed."
        )

        print(
            "Installation cancelled."
        )

        return {
            "installed": False,
            "stage": "sandbox_testing",
            "tests": sandbox_report,
        }

    print(
        "✅ Sandbox tests passed."
    )

    # --------------------------------
    # 3. Backup live Chopper
    # --------------------------------

    print(
        "\n2. Creating live backup..."
    )

    backup = create_backup()

    print(
        f"✅ Backup created:\n{backup}"
    )

    # --------------------------------
    # 4. Install file
    # --------------------------------

    print(
        "\n3. Installing tested file..."
    )

    try:
        destination.parent.mkdir(
            parents=True,
            exist_ok=True,
        )

        shutil.copy2(
            source,
            destination,
        )

        print(
            f"✅ Installed:\n{destination}"
        )

    except Exception as error:

        print(
            f"❌ Installation failed:\n"
            f"{error}"
        )

        return {
            "installed": False,
            "stage": "installation",
            "backup": str(backup),
            "error": str(error),
        }

    # --------------------------------
    # 5. Verify live project
    # --------------------------------

    print(
        "\n4. Verifying live Chopper..."
    )

    verification = verify_live_project()

    if verification["passed"]:

        print(
            "✅ Live Chopper verification passed."
        )

        print(
            "\n========================================"
        )
        print(
            "✅ UPGRADE INSTALLED SUCCESSFULLY"
        )
        print(
            "========================================"
        )

        return {
            "installed": True,
            "stage": "complete",
            "file": relative_path,
            "backup": str(backup),
        }

    # --------------------------------
    # 6. Automatic rollback
    # --------------------------------

    print(
        "❌ Live verification failed."
    )

    if verification["stderr"]:
        print(
            verification["stderr"]
        )

    print(
        "\n5. Rolling back..."
    )

    try:
        restore_file_from_backup(
            backup,
            relative_path,
        )

        rollback_verification = (
            verify_live_project()
        )

        if rollback_verification["passed"]:

            print(
                "✅ Rollback successful."
            )

        else:

            print(
                "⚠️ File restored, but live "
                "verification still failed."
            )

        return {
            "installed": False,
            "stage": "rolled_back",
            "backup": str(backup),
            "verification": verification,
            "rollback_verification":
                rollback_verification,
        }

    except Exception as error:

        print(
            "❌ CRITICAL: Automatic rollback "
            "failed."
        )

        print(error)

        return {
            "installed": False,
            "stage": "rollback_failed",
            "backup": str(backup),
            "error": str(error),
        }


if __name__ == "__main__":
    print(
        "Controlled installer loaded."
    )

    print(
        "No live upgrade was performed."
    )