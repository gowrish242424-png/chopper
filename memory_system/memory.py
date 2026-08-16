import sqlite3
from pathlib import Path


DATABASE_PATH = Path("memory.db")


def get_connection():
    """
    Create a fresh SQLite connection for the current thread.
    """

    return sqlite3.connect(
        DATABASE_PATH,
        timeout=10,
    )


def initialize_database():
    """Create the memories table if it does not exist."""

    with get_connection() as connection:
        connection.execute(
            """
            CREATE TABLE IF NOT EXISTS memories (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )
            """
        )


def save_memory(key, value):
    """Save or update a memory."""

    with get_connection() as connection:
        connection.execute(
            """
            INSERT INTO memories (key, value)
            VALUES (?, ?)
            ON CONFLICT(key)
            DO UPDATE SET value = excluded.value
            """,
            (key, value),
        )


def load_memory(key):
    """Load one memory by key."""

    with get_connection() as connection:
        cursor = connection.execute(
            """
            SELECT value
            FROM memories
            WHERE key = ?
            """,
            (key,),
        )

        row = cursor.fetchone()

    if row:
        return row[0]

    return None


def get_all_memories():
    """Return all stored memories."""

    with get_connection() as connection:
        cursor = connection.execute(
            """
            SELECT key, value
            FROM memories
            ORDER BY key
            """
        )

        return cursor.fetchall()


def search_memories(query):
    """
    Search memories using simple keyword matching.
    """

    query = query.lower().strip()

    if not query:
        return []

    with get_connection() as connection:
        cursor = connection.execute(
            """
            SELECT key, value
            FROM memories
            WHERE LOWER(key) LIKE ?
               OR LOWER(value) LIKE ?
            ORDER BY key
            """,
            (
                f"%{query}%",
                f"%{query}%",
            ),
        )

        return cursor.fetchall()


initialize_database()