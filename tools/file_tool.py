from pathlib import Path


SUPPORTED_EXTENSIONS = {
    ".txt",
    ".md",
    ".py",
    ".java",
    ".json",
    ".csv",
}


def create_file(filename, content=""):
    try:
        Path(filename).write_text(
            content,
            encoding="utf-8",
        )
        return f"✅ File created: {filename}"

    except Exception as e:
        return f"❌ {e}"


def read_file(filename):
    path = Path(filename)

    if not path.exists():
        return "❌ File not found."

    if path.suffix.lower() not in SUPPORTED_EXTENSIONS:
        return (
            f"❌ Unsupported file type: "
            f"{path.suffix}"
        )

    try:
        return path.read_text(
            encoding="utf-8"
        )

    except Exception as e:
        return f"❌ {e}"


def delete_file(filename):
    path = Path(filename)

    if not path.exists():
        return "❌ File not found."

    try:
        path.unlink()
        return f"✅ Deleted {filename}"

    except Exception as e:
        return f"❌ {e}"


def file_exists(filename):
    return Path(filename).exists()


def list_files(folder="."):
    try:
        return [
            file.name
            for file in Path(folder).iterdir()
            if file.is_file()
        ]

    except Exception as e:
        return f"❌ {e}"