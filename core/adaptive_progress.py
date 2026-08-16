import json
from pathlib import Path


# ========================================
# Storage
# ========================================

PROJECT_ROOT = Path(__file__).resolve().parent.parent

TIMING_FILE = (
    PROJECT_ROOT
    / "memory_system"
    / "generation_timings.json"
)


# ========================================
# Defaults
# ========================================

DEFAULT_TIMES = {
    "qwen3:1.7b": {
        "easy": 8.0,
        "medium": 15.0,
        "hard": 25.0,
    },

    "qwen2.5:3b": {
        "easy": 15.0,
        "medium": 30.0,
        "hard": 50.0,
    },

    "qwen3:8b": {
        "easy": 60.0,
        "medium": 120.0,
        "hard": 210.0,
    },
}


LEARNING_RATE = 0.20

MAX_ESTIMATED_PROGRESS = 95.0


# ========================================
# Load timing data
# ========================================

def load_timings():
    """
    Load learned generation timings.

    If no timing file exists yet,
    start with default values.
    """

    if not TIMING_FILE.exists():
        return {
            model: values.copy()
            for model, values
            in DEFAULT_TIMES.items()
        }

    try:
        with open(
            TIMING_FILE,
            "r",
            encoding="utf-8",
        ) as file:
            data = json.load(file)

        return data

    except Exception:
        return {
            model: values.copy()
            for model, values
            in DEFAULT_TIMES.items()
        }


# ========================================
# Save timing data
# ========================================

def save_timings(data):
    """
    Save learned generation timings.
    """

    TIMING_FILE.parent.mkdir(
        parents=True,
        exist_ok=True,
    )

    with open(
        TIMING_FILE,
        "w",
        encoding="utf-8",
    ) as file:

        json.dump(
            data,
            file,
            indent=4,
        )


# ========================================
# Normalize difficulty
# ========================================

def normalize_difficulty(
    difficulty,
):
    """
    Convert difficulty to one of:

    easy
    medium
    hard
    """

    difficulty = str(
        difficulty
    ).lower().strip()

    if difficulty in {
        "easy",
        "simple",
        "low",
    }:
        return "easy"

    if difficulty in {
        "hard",
        "complex",
        "high",
    }:
        return "hard"

    return "medium"


# ========================================
# Expected generation time
# ========================================

def get_expected_time(
    model,
    difficulty="medium",
):
    """
    Return Chopper's learned expected
    generation time.
    """

    difficulty = normalize_difficulty(
        difficulty
    )

    data = load_timings()

    model_data = data.get(
        model
    )

    if model_data is None:

        return 30.0

    try:
        return float(
            model_data.get(
                difficulty,
                30.0,
            )
        )

    except (TypeError, ValueError):
        return 30.0


# ========================================
# Learn from actual generation
# ========================================

def record_generation_time(
    model,
    difficulty,
    actual_seconds,
):
    """
    Update expected generation time using
    an exponential moving average.

    Recent generations influence the
    estimate more than very old ones.
    """

    difficulty = normalize_difficulty(
        difficulty
    )

    try:
        actual_seconds = float(
            actual_seconds
        )

    except (TypeError, ValueError):
        return

    if actual_seconds <= 0:
        return

    data = load_timings()

    if model not in data:
        data[model] = {
            "easy": actual_seconds,
            "medium": actual_seconds,
            "hard": actual_seconds,
        }

    old_average = float(
        data[model].get(
            difficulty,
            actual_seconds,
        )
    )

    new_average = (
        old_average
        * (1.0 - LEARNING_RATE)
        + actual_seconds
        * LEARNING_RATE
    )

    data[model][difficulty] = round(
        new_average,
        2,
    )

    save_timings(
        data
    )


# ========================================
# Calculate percentage
# ========================================

def calculate_progress(
    elapsed_seconds,
    expected_seconds,
):
    """
    Estimate generation progress.

    Estimated progress never reaches
    100% until generation actually ends.
    """

    try:
        elapsed_seconds = max(
            0.0,
            float(elapsed_seconds),
        )

        expected_seconds = max(
            1.0,
            float(expected_seconds),
        )

    except (TypeError, ValueError):
        return 0.0

    ratio = (
        elapsed_seconds
        / expected_seconds
    )

    # Normal estimated progress
    if ratio <= 1.0:

        progress = (
            ratio
            * MAX_ESTIMATED_PROGRESS
        )

    else:

        extra_ratio = (
            ratio - 1.0
        )

        # Move more visibly from 95% toward 99%
        # when generation takes longer than expected.
        progress = (
            MAX_ESTIMATED_PROGRESS
            + min(
                extra_ratio * 12.0,
                4.0,
            )
        )

    return round(
        min(progress, 99.0),
        1,
    )


# ========================================
# Convenience function
# ========================================

def get_progress(
    model,
    difficulty,
    elapsed_seconds,
):
    """
    Get progress directly using Chopper's
    learned timing estimate.
    """

    expected = get_expected_time(
        model,
        difficulty,
    )

    return calculate_progress(
        elapsed_seconds,
        expected,
    )


# ========================================
# Test
# ========================================
def estimate_difficulty(
    user_message,
    mode="",
):
    """
    Estimate question difficulty for
    generation-time learning.
    """

    message = user_message.lower().strip()

    # -----------------------------
    # Hard modes
    # -----------------------------
    hard_modes = {
        "research",
        "builder",
        "teacher",
    }

    if str(mode).lower() in hard_modes:
        return "hard"

    # -----------------------------
    # Hard-question indicators
    # -----------------------------
    hard_words = [
        "architecture",
        "research",
        "detailed",
        "complete project",
        "deep learning",
        "machine learning",
        "analyze",
        "design",
        "develop",
        "debug",
        "compare",
        "explain in detail",
    ]

    if any(
        word in message
        for word in hard_words
    ):
        return "hard"

    # Long questions usually need
    # more reasoning/output.
    if len(message) > 250:
        return "hard"

    # -----------------------------
    # Easy questions
    # -----------------------------
    easy_words = [
        "hi",
        "hello",
        "hey",
        "thanks",
        "thank you",
        "yes",
        "no",
    ]

    if message in easy_words:
        return "easy"

    if (
        len(message) < 40
        and "explain" not in message
        and "why" not in message
        and "how" not in message
    ):
        return "easy"

    return "medium"

if __name__ == "__main__":

    model = "qwen2.5:3b"
    difficulty = "medium"

    expected = get_expected_time(
        model,
        difficulty,
    )

    print(
        f"Model      : {model}"
    )

    print(
        f"Difficulty : {difficulty}"
    )

    print(
        f"Expected   : {expected}s"
    )

    print()

    for elapsed in [
        0,
        expected * 0.25,
        expected * 0.50,
        expected * 0.75,
        expected,
        expected * 1.5,
        expected * 2,
    ]:

        percentage = get_progress(
            model,
            difficulty,
            elapsed,
        )

        print(
            f"{elapsed:6.1f}s -> "
            f"{percentage:5.1f}%"
        )