from pathlib import Path
import os
import shutil
import stat
import time

PROJECT_ROOT = Path(__file__).resolve().parent.parent

SANDBOX_ROOT = (
    PROJECT_ROOT
    / "upgrade_workspace"
    / "sandbox"
)

IGNORE_NAMES = {
    "__pycache__",
    ".git",
    ".vscode",
    "upgrade_workspace",
}


def should_ignore(path):
    """
    Return True for folders/files
    that should not be copied.
    """

    return any(
        part in IGNORE_NAMES
        for part in path.parts
    )
def remove_readonly(func, path, _):
    """
    Handle Windows/OneDrive read-only files
    while deleting the old sandbox.
    """

    try:
        os.chmod(
            path,
            stat.S_IWRITE,
        )

        func(path)

    except Exception as error:
        raise RuntimeError(
            f"Could not remove sandbox item: "
            f"{path}\n{error}"
        )


def create_sandbox():
    """
    Create a fresh copy of Chopper.

    The real project is never modified.
    """

    if SANDBOX_ROOT.exists():

        try:
            shutil.rmtree(
                SANDBOX_ROOT
            )

        except PermissionError:

            shutil.rmtree(
                SANDBOX_ROOT,
                onexc=remove_readonly,
            )

    SANDBOX_ROOT.mkdir(
        parents=True,
        exist_ok=True,
    )

    for source_path in PROJECT_ROOT.rglob("*"):

        relative_path = (
            source_path.relative_to(
                PROJECT_ROOT
            )
        )

        if should_ignore(
            relative_path
        ):
            continue

        destination = (
            SANDBOX_ROOT
            / relative_path
        )

        if source_path.is_dir():

            destination.mkdir(
                parents=True,
                exist_ok=True,
            )

        elif source_path.is_file():

            destination.parent.mkdir(
                parents=True,
                exist_ok=True,
            )

            shutil.copy2(
                source_path,
                destination,
            )

    return SANDBOX_ROOT


def sandbox_exists():
    """Check whether a sandbox exists."""

    return SANDBOX_ROOT.exists()


def sandbox_file(relative_path):
    """
    Return a safe path inside the sandbox.
    """

    path = (
        SANDBOX_ROOT
        / relative_path
    ).resolve()

    try:
        path.relative_to(
            SANDBOX_ROOT.resolve()
        )

    except ValueError:
        raise ValueError(
            "Sandbox path escape detected."
        )

    return path


def sandbox_summary():
    """Return basic information about sandbox."""

    if not sandbox_exists():
        return (
            "No sandbox currently exists."
        )

    python_files = list(
        SANDBOX_ROOT.rglob("*.py")
    )

    return (
        f"Sandbox: {SANDBOX_ROOT}\n"
        f"Python files: "
        f"{len(python_files)}"
    )


if __name__ == "__main__":

    print(
        "Creating Chopper sandbox..."
    )

    start = time.perf_counter()

    sandbox = create_sandbox()

    elapsed = (
        time.perf_counter()
        - start
    )

    print(
        f"Sandbox created:\n{sandbox}"
    )

    print(
        sandbox_summary()
    )

    print(
        f"Creation time: "
        f"{elapsed:.3f}s"
    )