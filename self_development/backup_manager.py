from pathlib import Path
from datetime import datetime
import os
import shutil
import stat


PROJECT_ROOT = Path(__file__).resolve().parent.parent

BACKUP_ROOT = (
    PROJECT_ROOT
    / "upgrade_workspace"
    / "backups"
)


IGNORE_NAMES = {
    "__pycache__",
    ".git",
    ".vscode",
    "upgrade_workspace",
}


def should_ignore(path):
    """Return True for items that should not be backed up."""

    return any(
        part in IGNORE_NAMES
        for part in path.parts
    )


def remove_readonly(func, path, _):
    """Handle Windows read-only files."""

    os.chmod(
        path,
        stat.S_IWRITE,
    )

    func(path)


def create_backup():
    """
    Create a timestamped backup of Chopper.

    The backup is stored inside:
    upgrade_workspace/backups/
    """

    BACKUP_ROOT.mkdir(
        parents=True,
        exist_ok=True,
    )

    timestamp = datetime.now().strftime(
        "%Y%m%d_%H%M%S"
    )

    backup_path = (
        BACKUP_ROOT
        / f"chopper_{timestamp}"
    )

    # Prevent unlikely timestamp collision
    counter = 1

    while backup_path.exists():
        backup_path = (
            BACKUP_ROOT
            / f"chopper_{timestamp}_{counter}"
        )

        counter += 1

    backup_path.mkdir(
        parents=True,
        exist_ok=False,
    )

    for source_path in PROJECT_ROOT.rglob("*"):

        relative_path = source_path.relative_to(
            PROJECT_ROOT
        )

        if should_ignore(relative_path):
            continue

        destination = (
            backup_path
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

    return backup_path


def list_backups():
    """Return available Chopper backups."""

    if not BACKUP_ROOT.exists():
        return []

    return sorted(
        [
            path
            for path in BACKUP_ROOT.iterdir()
            if path.is_dir()
        ],
        key=lambda path: path.name,
        reverse=True,
    )


def latest_backup():
    """Return the newest backup."""

    backups = list_backups()

    if not backups:
        return None

    return backups[0]


def restore_file_from_backup(
    backup_path,
    relative_path,
):
    """
    Restore ONE file from a backup.

    This function can modify the live project,
    so it should only be used during rollback.
    """

    backup_path = Path(
        backup_path
    ).resolve()

    try:
        backup_path.relative_to(
            BACKUP_ROOT.resolve()
        )

    except ValueError:
        raise ValueError(
            "Invalid backup path."
        )

    source = (
        backup_path
        / relative_path
    ).resolve()

    destination = (
        PROJECT_ROOT
        / relative_path
    ).resolve()

    try:
        source.relative_to(
            backup_path
        )

        destination.relative_to(
            PROJECT_ROOT
        )

    except ValueError:
        raise ValueError(
            "Unsafe restore path detected."
        )

    if not source.exists():
        raise FileNotFoundError(
            f"Backup file not found: "
            f"{relative_path}"
        )

    destination.parent.mkdir(
        parents=True,
        exist_ok=True,
    )

    shutil.copy2(
        source,
        destination,
    )

    return destination


if __name__ == "__main__":

    print(
        "Creating Chopper backup..."
    )

    backup = create_backup()

    print(
        f"✅ Backup created:\n{backup}"
    )

    backups = list_backups()

    print(
        f"Available backups: "
        f"{len(backups)}"
    )

    newest = latest_backup()

    if newest:
        print(
            f"Latest backup:\n{newest}"
        )