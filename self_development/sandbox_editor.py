from pathlib import Path
import shutil

from self_development.sandbox_manager import (
    SANDBOX_ROOT,
    sandbox_exists,
    sandbox_file,
)


ALLOWED_EXTENSIONS = {
    ".py",
    ".json",
    ".txt",
    ".md",
}


def validate_sandbox():
    """Ensure a sandbox exists before editing."""

    if not sandbox_exists():
        raise RuntimeError(
            "Sandbox does not exist. "
            "Create it before editing."
        )


def read_sandbox_file(relative_path):
    """
    Read a file from the sandbox.
    """

    validate_sandbox()

    path = sandbox_file(
        relative_path
    )

    if not path.exists():
        raise FileNotFoundError(
            f"Sandbox file not found: "
            f"{relative_path}"
        )

    if not path.is_file():
        raise ValueError(
            "Requested path is not a file."
        )

    return path.read_text(
        encoding="utf-8"
    )


def write_sandbox_file(
    relative_path,
    content,
):
    """
    Write a file ONLY inside the sandbox.
    """

    validate_sandbox()

    path = sandbox_file(
        relative_path
    )

    if path.suffix.lower() not in ALLOWED_EXTENSIONS:
        raise ValueError(
            f"Editing {path.suffix} files "
            "is not allowed."
        )

    path.parent.mkdir(
        parents=True,
        exist_ok=True,
    )

    path.write_text(
        content,
        encoding="utf-8",
    )

    return path


def replace_in_sandbox(
    relative_path,
    old_text,
    new_text,
):
    """
    Replace exact text inside a sandbox file.

    Fails safely if the target text does not
    appear exactly once.
    """

    content = read_sandbox_file(
        relative_path
    )

    count = content.count(
        old_text
    )

    if count == 0:
        raise ValueError(
            "Target text was not found."
        )

    if count > 1:
        raise ValueError(
            "Target text appears multiple times. "
            "Refusing ambiguous replacement."
        )

    updated = content.replace(
        old_text,
        new_text,
        1,
    )

    return write_sandbox_file(
        relative_path,
        updated,
    )


def create_sandbox_file(
    relative_path,
    content="",
):
    """
    Create a new file inside the sandbox.
    """

    validate_sandbox()

    path = sandbox_file(
        relative_path
    )

    if path.exists():
        raise FileExistsError(
            f"File already exists: "
            f"{relative_path}"
        )

    return write_sandbox_file(
        relative_path,
        content,
    )


def delete_sandbox_file(
    relative_path,
):
    """
    Delete a file ONLY from the sandbox.
    """

    validate_sandbox()

    path = sandbox_file(
        relative_path
    )

    if not path.exists():
        raise FileNotFoundError(
            f"Sandbox file not found: "
            f"{relative_path}"
        )

    if not path.is_file():
        raise ValueError(
            "Only files can be deleted."
        )

    path.unlink()

    return True


def copy_sandbox_file(
    source_relative_path,
    destination_relative_path,
):
    """
    Copy a file inside the sandbox.
    """

    validate_sandbox()

    source = sandbox_file(
        source_relative_path
    )

    destination = sandbox_file(
        destination_relative_path
    )

    if not source.exists():
        raise FileNotFoundError(
            f"Source file not found: "
            f"{source_relative_path}"
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
        "Testing sandbox editor..."
    )

    test_path = (
        "self_development/"
        "_sandbox_editor_test.txt"
    )

    try:
        path = create_sandbox_file(
            test_path,
            "Chopper sandbox test"
        )

        print(
            f"Created: {path}"
        )

        content = read_sandbox_file(
            test_path
        )

        print(
            f"Read: {content}"
        )

        replace_in_sandbox(
            test_path,
            "sandbox test",
            "sandbox editing test",
        )

        print(
            "Modified:"
        )

        print(
            read_sandbox_file(
                test_path
            )
        )

        delete_sandbox_file(
            test_path
        )

        print(
            "Deleted test file."
        )

        print(
            "✅ Sandbox editor works."
        )

    except Exception as error:
        print(
            f"❌ Sandbox editor failed: "
            f"{error}"
        )