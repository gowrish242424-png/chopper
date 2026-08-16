from pathlib import Path


# Chopper project root
PROJECT_ROOT = Path(__file__).resolve().parent.parent


# Folders/files that Chopper should ignore
IGNORE_NAMES = {
    "__pycache__",
    ".git",
    ".vscode",
    "memory.db",
}


def should_ignore(path):
    """Return True for files/folders we don't need to scan."""

    return any(
        part in IGNORE_NAMES
        for part in path.parts
    )


def scan_project():
    """
    Scan Chopper's source code structure.

    Read-only:
    This function does not modify any files.
    """

    files = []

    for path in PROJECT_ROOT.rglob("*.py"):

        if should_ignore(path):
            continue

        relative_path = path.relative_to(
            PROJECT_ROOT
        )

        files.append(
            str(relative_path)
        )

    return sorted(files)


def read_source(relative_path):
    """
    Read one Chopper source file safely.
    """

    requested_path = (
        PROJECT_ROOT / relative_path
    ).resolve()

    # Prevent reading outside Chopper directory
    try:
        requested_path.relative_to(
            PROJECT_ROOT
        )
    except ValueError:
        raise ValueError(
            "Access outside the Chopper project "
            "is not allowed."
        )

    if not requested_path.exists():
        raise FileNotFoundError(
            f"File not found: {relative_path}"
        )

    if not requested_path.is_file():
        raise ValueError(
            "Requested path is not a file."
        )

    if requested_path.suffix != ".py":
        raise ValueError(
            "Only Python source files can be read."
        )

    return requested_path.read_text(
        encoding="utf-8"
    )


def project_summary():
    """
    Return a simple overview of Chopper's source files.
    """

    files = scan_project()

    lines = [
        "Chopper Project",
        "===============",
        f"Project root: {PROJECT_ROOT}",
        f"Python files: {len(files)}",
        "",
    ]

    for file in files:
        lines.append(file)

    return "\n".join(lines)


if __name__ == "__main__":
    print(
        project_summary()
    )