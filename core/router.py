from core.config import (
    MODEL_FAST,
    MODEL_SMART,
    MODEL_DEEP,
)

from core.mode_manager import get_current_mode


def choose_model(user_message):
    """
    Select the best AI model based on the
    current response mode.

    If mode is AUTO, detect automatically
    from the user's question.
    """

    message = user_message.lower()

    # ----------------------------------
    # Current Mode
    # ----------------------------------

    mode = get_current_mode()

    # ----------------------------------
    # Manual Modes
    # ----------------------------------

    if mode == "quick":
        return MODEL_FAST, "Quick"

    if mode == "balanced":
        return MODEL_SMART, "Balanced"

    if mode == "research":
        return MODEL_DEEP, "Research"

    if mode == "builder":
        return MODEL_DEEP, "Builder"

    if mode == "solve":
        return MODEL_SMART, "Solve"

    if mode == "teacher":
        return MODEL_DEEP, "Teacher"

    # ----------------------------------
    # AUTO MODE
    # ----------------------------------

    deep_words = [

        "architecture",
        "design",
        "complete project",
        "research",
        "latest",
        "compare",
        "comparison",
        "deep learning",
        "machine learning",
        "artificial intelligence",
        "thesis",
        "report",
        "essay",
        "project",
        "workflow",
        "analysis",

    ]

    smart_words = [

        "python",
        "java",
        "c++",
        "c#",
        "javascript",
        "code",
        "coding",
        "program",
        "bug",
        "debug",
        "error",
        "function",
        "class",
        "algorithm",
        "database",
        "sql",

    ]

    quick_words = [

        "hi",
        "hello",
        "hey",
        "thanks",
        "bye",
        "time",
        "date",

    ]

    if any(word in message for word in quick_words):
        return MODEL_FAST, "Quick (Auto)"

    if any(word in message for word in smart_words):
        return MODEL_SMART, "Balanced (Auto)"

    if any(word in message for word in deep_words):
        return MODEL_DEEP, "Research (Auto)"

    # Default
    return MODEL_FAST, "Auto"