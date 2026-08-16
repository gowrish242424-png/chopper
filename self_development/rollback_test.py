from pathlib import Path

from self_development.backup_manager import (
    PROJECT_ROOT,
    create_backup,
    restore_file_from_backup,
)


TEST_FILE = (
    PROJECT_ROOT
    / "rollback_test_temp.txt"
)


def test_rollback():
    print("\n1. Creating temporary file...")

    TEST_FILE.write_text(
        "ORIGINAL VERSION",
        encoding="utf-8",
    )

    print("   ✅ Original file created")

    print("\n2. Creating backup...")

    backup = create_backup()

    print(
        f"   ✅ Backup created:\n"
        f"   {backup}"
    )

    print("\n3. Modifying live temporary file...")

    TEST_FILE.write_text(
        "BROKEN VERSION",
        encoding="utf-8",
    )

    current = TEST_FILE.read_text(
        encoding="utf-8",
    )

    print(
        f"   Current content: {current}"
    )

    print("\n4. Restoring from backup...")

    restore_file_from_backup(
        backup,
        "rollback_test_temp.txt",
    )

    restored = TEST_FILE.read_text(
        encoding="utf-8",
    )

    print(
        f"   Restored content: {restored}"
    )

    print("\n5. Checking rollback...")

    if restored == "ORIGINAL VERSION":
        print(
            "   ✅ ROLLBACK TEST PASSED"
        )
        success = True
    else:
        print(
            "   ❌ ROLLBACK TEST FAILED"
        )
        success = False

    # Remove temporary live test file
    if TEST_FILE.exists():
        TEST_FILE.unlink()

    return success


if __name__ == "__main__":
    result = test_rollback()

    print(
        f"\nRollback result: {result}"
    )