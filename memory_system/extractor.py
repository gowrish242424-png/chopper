import re


def clean_value(value):
    """
    Clean extracted memory values.

    Examples:
    "sir."      -> "Sir"
    "python!"   -> "Python"
    "gowrish?"  -> "Gowrish"
    """

    value = value.strip()
    value = value.rstrip(".,!?;:")
    return value.title()


def extract_memories(text):
    """
    Extract personal information from the user's message.

    Returns:
        A list of dictionaries containing memory keys and values.

    Example:
        Input:
            "Call me Sir"

        Output:
            [
                {
                    "key": "title",
                    "value": "Sir"
                }
            ]
    """

    memories = []

    patterns = [
        # Name
        (r"\bmy name is (.+)", "name"),

        # Preferred title
        # Keep "call me as" before "call me"
        (r"\bcall me as (.+)", "title"),
        (r"\bcall me (.+)", "title"),
        (r"\bmy title is (.+)", "title"),
        (r"\baddress me as (.+)", "title"),

        # Favourite programming language
        (
            r"\bmy favourite language is (.+)",
            "favorite_language",
        ),
        (
            r"\bmy favorite language is (.+)",
            "favorite_language",
        ),

        # Favourite subject
        (
            r"\bmy favourite subject is (.+)",
            "favorite_subject",
        ),
        (
            r"\bmy favorite subject is (.+)",
            "favorite_subject",
        ),

        # Favourite colour
        (
            r"\bmy favourite colou?r is (.+)",
            "favorite_color",
        ),
        (
            r"\bmy favorite colou?r is (.+)",
            "favorite_color",
        ),

        # Favourite food
        (
            r"\bmy favourite food is (.+)",
            "favorite_food",
        ),
        (
            r"\bmy favorite food is (.+)",
            "favorite_food",
        ),

        # Favourite movie
        (
            r"\bmy favourite movie is (.+)",
            "favorite_movie",
        ),
        (
            r"\bmy favorite movie is (.+)",
            "favorite_movie",
        ),

        # Friend
        (
            r"\bmy friend(?:'s name)? is (.+)",
            "friend",
        ),
        (
            r"\bi have a friend(?: named| name)? (.+)",
            "friend",
        ),

        # Goal
        (r"\bmy goal is (.+)", "goal"),

        # Project
        (r"\bmy project is (.+)", "project"),

        # Course
        (r"\bi study (.+)", "course"),
    ]

    normalized_text = text.lower().strip()

    for pattern, key in patterns:
        match = re.search(
            pattern,
            normalized_text,
            flags=re.IGNORECASE,
        )

        if not match:
            continue

        value = clean_value(match.group(1))

        if not value:
            continue

        memories.append(
            {
                "key": key,
                "value": value,
            }
        )

        # Prevent similar patterns from saving the same sentence twice
        break

    return memories