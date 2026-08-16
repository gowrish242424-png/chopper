from datetime import datetime
from zoneinfo import ZoneInfo

from rapidfuzz import fuzz, process

from memory_system.memory import load_memory
from core.mode_manager import (
    show_modes,
    set_mode,
    get_current_mode,
)

COMMANDS = {
    "hi": "greeting",
    "hello": "greeting",
    "hey": "greeting",

    "bye": "bye",
    "goodbye": "bye",

    "thanks": "thanks",
    "thank you": "thanks",

    "what is my name": "name",
    "who am i": "name",

    "what is my favourite language": "language",
    "what is my favorite language": "language",

    "what is my favourite programming language": "language",
    "what is my favorite programming language": "language",
    
    "what is my favourite coding language": "language",
    "what is my favorite coding language": "language",

    "what is my favourite subject": "subject",
    "what is my favorite subject": "subject",

    "what time is it": "time",
    "what is time now": "time",
    "what is the time now": "time",
    "tell me the time": "time",
    "current time": "time",
    "time now": "time",

    "what is today's date": "date",
    "what is todays date": "date",
    "current date": "date",
    "date today": "date",
    "today's date": "date",
}


def get_india_time():
    """Return the current date and time in India."""

    return datetime.now(
        ZoneInfo("Asia/Kolkata")
    )


def handle_command(user_message):
    """Handle instant commands without calling an AI model."""

    message = (
        user_message.lower()
        .strip()
        .replace("?", "")
    )
    # -------------------------
    # Mode Menu
    # -------------------------

    if message == "mode":
        return show_modes()

    # -------------------------
    # Change Mode
    # -------------------------

    mode_names = [
        "quick",
        "balanced",
        "research",
        "builder",
        "solve",
        "teacher",
        "auto",
    ]

    if message in mode_names:

        set_mode(message)

        return (
            f"✅ Chopper mode changed to:\n\n"
            f"{get_current_mode().capitalize()}"
        )

    if message == "cancel":
        return "Mode selection cancelled."

    # Exact match
    if message in COMMANDS:
        command = COMMANDS[message]

    else:
        result = process.extractOne(
            message,
            COMMANDS.keys(),
            scorer=fuzz.ratio,
        )

        if result is None:
            return None

        best_match, score, _ = result

        # Prevent long unrelated questions from matching commands
        if len(message.split()) > 7:
            return None

        # High threshold prevents incorrect fuzzy matches
        if score < 92:
            return None

        command = COMMANDS[best_match]

    if command == "greeting":
        title = load_memory("title")

        if title:
            return (
                f"Hello, {title}! 😊 "
                "How can I help you today?"
            )

        return "Hello! 😊 How can I help you today?"

    if command == "bye":
        title = load_memory("title")

        if title:
            return f"Goodbye, {title}! 👋"

        return "Goodbye! 👋"

    if command == "thanks":
        title = load_memory("title")

        if title:
            return f"You're welcome, {title}! 😊"

        return "You're welcome! 😊"

    if command == "name":
        name = load_memory("name")

        if name:
            return f"Your name is {name}."

        return "I don't know your name yet."

    if command == "language":
        language = load_memory("favorite_language")

        if language:
            return (
                f"Your favourite language is "
                f"{language}."
            )

        return "I don't know your favourite language."

    if command == "subject":
        subject = load_memory("favorite_subject")

        if subject:
            return (
                f"Your favourite subject is "
                f"{subject}."
            )

        return "I don't know your favourite subject."

    if command == "time":
        india_time = get_india_time()

        return india_time.strftime(
            "The current time is %I:%M %p."
        )

    if command == "date":
        india_time = get_india_time()

        return india_time.strftime(
            "Today's date is %d-%m-%Y."
        )

    return None