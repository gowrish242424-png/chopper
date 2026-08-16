import json
import re

from ollama import chat


MEMORY_MODEL = "qwen2.5:3b"


def extract_json(response_text):
    """
    Extract a JSON object from the model response.

    This handles cases where the model accidentally adds
    extra text before or after the JSON.
    """

    if not response_text:
        return {"remember": False}

    response_text = response_text.strip()

    # First try: response is already valid JSON
    try:
        result = json.loads(response_text)

        if isinstance(result, dict):
            return result

    except json.JSONDecodeError:
        pass

    # Second try: find the first JSON object
    match = re.search(
        r"\{.*?\}",
        response_text,
        re.DOTALL,
    )

    if match:
        try:
            result = json.loads(match.group())

            if isinstance(result, dict):
                return result

        except json.JSONDecodeError:
            pass

    return {"remember": False}


def normalize_memory(memory):
    """
    Validate and normalize the memory manager result.
    """

    if not isinstance(memory, dict):
        return {"remember": False}

    remember = memory.get("remember", False)

    if not isinstance(remember, bool):
        remember = str(remember).lower() == "true"

    if not remember:
        return {"remember": False}

    key = str(
        memory.get("key", "")
    ).strip()

    value = str(
        memory.get("value", "")
    ).strip()

    if not key or not value:
        return {"remember": False}

    # Store keys in a consistent format
    key = (
        key.lower()
        .replace(" ", "_")
        .replace("-", "_")
    )

    # Convert British spelling to the stored American spelling
    key = key.replace(
        "favourite",
        "favorite",
    )

    key_aliases = {
        "language": "favorite_language",
        "programming_language": "favorite_language",
        "favorite_programming_language": "favorite_language",
        "coding_language": "favorite_language",
        "favorite_coding_language": "favorite_language",

        "subject": "favorite_subject",

        "pokemon": "favorite_pokemon",

        "food": "favorite_food",

        "movie": "favorite_movie",
    }

    key = key_aliases.get(
        key,
        key,
    )

    return {
        "remember": True,
        "key": key,
        "value": value,
    }


def should_remember(user_message):
    """
    Decide whether a user message contains useful
    long-term personal information.

    Returns:
        {
            "remember": True,
            "key": "...",
            "value": "..."
        }

        or:

        {
            "remember": False
        }
    """

    user_message = user_message.strip()

    if not user_message:
        return {"remember": False}

    prompt = f"""
You are Chopper's memory manager.

Your only job is to detect important long-term personal
information stated by the user.

Remember information such as:
- Name
- Preferred title
- Age
- Birthday
- Favourite subject
- Favourite programming language
- Favourite food
- Favourite Pokémon
- Favourite movie
- Hobbies
- Goals
- Occupation
- Long-term projects
- Stable preferences

Do not remember:
- Greetings
- Questions
- Temporary events
- Things that happened only today
- Random statements
- One-time activities
- Assistant responses

Return only one valid JSON object.

Required formats:

When the message should be remembered:

{{"remember": true, "key": "memory_key", "value": "memory value"}}

When the message should not be remembered:

{{"remember": false}}

Use consistent keys:

- name
- title
- age
- birthday
- favorite_subject
- favorite_language
- favorite_food
- favorite_pokemon
- favorite_movie
- hobby
- goal
- occupation
- project

Examples:

User message:
My name is Gowrish

Output:
{{"remember": true, "key": "name", "value": "Gowrish"}}

User message:
My favourite subject is Maths

Output:
{{"remember": true, "key": "favorite_subject", "value": "Maths"}}

User message:
My favourite programming language is Python

Output:
{{"remember": true, "key": "favorite_language", "value": "Python"}}

User message:
My favourite Pokémon is Garchomp

Output:
{{"remember": true, "key": "favorite_pokemon", "value": "Garchomp"}}

User message:
I ate dosa today

Output:
{{"remember": false}}

User message:
What is recursion?

Output:
{{"remember": false}}

Analyze this user message:

{user_message}
"""

    try:
        response = chat(
            model=MEMORY_MODEL,
            messages=[
                {
                    "role": "user",
                    "content": prompt,
                }
            ],
            stream=False,
            options={
                "temperature": 0,
                "num_predict": 120,
                "num_ctx": 2048,
            },
        )

        response_text = response[
            "message"
        ].get(
            "content",
            "",
        )

        extracted_memory = extract_json(
            response_text
        )

        return normalize_memory(
            extracted_memory
        )

    except Exception as error:
        print(
            "Memory manager error: "
            f"{error}"
        )

        return {"remember": False}


if __name__ == "__main__":
    print(
        should_remember(
            "My favourite programming language is Python"
        )
    )

    print(
        should_remember(
            "I ate dosa today"
        )
    )