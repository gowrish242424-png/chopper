from core.config import (
    MODEL_FAST,
    MODEL_SMART,
    MODEL_DEEP,
)

from core.mode_manager import get_current_mode


def choose_model(user_message, query_info=None):
    message = user_message.lower().strip()
    current_mode = get_current_mode()

    # -----------------------------
    # Context-aware model selection
    # -----------------------------
    if query_info is not None:

        # Personal-memory questions
        if query_info.get("needs_memory", False):
            return MODEL_SMART, "Personal"

        # Web / current-information questions
        if query_info.get("needs_web", False):
            return MODEL_SMART, "Web Research"

    # -----------------------------
    # Manually selected modes
    # -----------------------------
    if current_mode == "quick":
        return MODEL_FAST, "Quick"

    if current_mode == "balanced":
        return MODEL_SMART, "Balanced"

    if current_mode == "research":
        return MODEL_DEEP, "Research"

    if current_mode == "builder":
        return MODEL_DEEP, "Builder"

    if current_mode == "solve":
        return MODEL_SMART, "Solve"

    if current_mode == "teacher":
        return MODEL_DEEP, "Teacher"

    # -----------------------------
    # Auto mode
    # -----------------------------
    deep_words = [
        "architecture",
        "research",
        "complete project",
        "detailed analysis",
        "machine learning",
        "deep learning",
        "artificial intelligence",
    ]

    smart_words = [
        "python",
        "java",
        "code",
        "program",
        "debug",
        "error",
        "function",
        "class",
        "algorithm",
        "compare",
        "explain",
    ]

    if any(word in message for word in deep_words):
        return MODEL_DEEP, "Research (Auto)"

    if any(word in message for word in smart_words):
        return MODEL_SMART, "Balanced (Auto)"

    return MODEL_FAST, "Quick (Auto)"