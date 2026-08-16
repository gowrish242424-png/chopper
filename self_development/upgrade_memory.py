import json
import re
from pathlib import Path
from datetime import datetime


PROJECT_ROOT = Path(__file__).resolve().parent.parent

MEMORY_FILE = (
    PROJECT_ROOT
    / "upgrade_workspace"
    / "upgrade_memory.json"
)


STOP_WORDS = {
    "improve",
    "upgrade",
    "make",
    "better",
    "the",
    "a",
    "an",
    "and",
    "or",
    "to",
    "of",
    "for",
    "in",
    "with",
    "without",
}


def load_upgrade_memory():
    """Load previous self-development experiences."""

    if not MEMORY_FILE.exists():
        return []

    try:
        with open(
            MEMORY_FILE,
            "r",
            encoding="utf-8",
        ) as file:
            data = json.load(file)

        if isinstance(data, list):
            return data

    except Exception as error:
        print(
            f"Upgrade memory load error: {error}"
        )

    return []


def save_upgrade_experience(
    request,
    file_path,
    function_name,
    approved,
    score,
    problems=None,
    suggestion="",
):
    """Remember the result of an upgrade attempt."""

    memories = load_upgrade_memory()
    # Avoid storing the exact same failure repeatedly.
    for old in reversed(memories):
        same_experience = (
            old.get("request") == request
            and old.get("file") == file_path
            and old.get("function") == function_name
            and old.get("approved") == bool(approved)
            and old.get("score") == score
            and old.get("problems") == (problems or [])
            and old.get("suggestion") == suggestion
        )

        if same_experience:
            return old

    result_type = (
        "successful_upgrade"
        if approved
        else "rejected_upgrade"
    )

    experience = {
        "timestamp": datetime.now().isoformat(
            timespec="seconds"
        ),
        "request": request,
        "file": file_path,
        "function": function_name,
        "approved": bool(approved),
        "result_type": result_type,
        "score": score,
        "problems": problems or [],
        "suggestion": suggestion,
    }

    memories.append(experience)

    MEMORY_FILE.parent.mkdir(
        parents=True,
        exist_ok=True,
    )
    STOP_WORDS = {
        "improve",
        "upgrade",
        "make",
        "better",
        "the",
        "a",
        "an",
        "and",
        "or",
        "to",
        "of",
        "for",
        "in",
        "with",
        "without",
    }

    with open(
        MEMORY_FILE,
        "w",
        encoding="utf-8",
    ) as file:
        json.dump(
            memories,
            file,
            indent=2,
            ensure_ascii=False,
        )

    return experience

def find_relevant_experiences(
    request,
    limit=5,
):
    """
    Find useful previous upgrade experiences
    related to the current request.
    """

    memories = load_upgrade_memory()

    if not memories:
        return []

    # --------------------------------
    # Normalize current request
    # --------------------------------

    request_tokens = re.findall(
        r"[a-z0-9_+.-]+",
        request.lower(),
    )

    request_words = {
        word
        for word in request_tokens
        if word not in STOP_WORDS
    }

    scored = []

    for experience in memories:

        # --------------------------------
        # Ignore infrastructure failures
        # --------------------------------

        problems = experience.get(
            "problems",
            [],
        )

        reason_text = " ".join(
            str(problem)
            for problem in problems
        ).lower()

        if (
            "critic did not return json"
            in reason_text
            or "critic execution failed"
            in reason_text
        ):
            continue

        # --------------------------------
        # Normalize previous request
        # --------------------------------

        old_request = experience.get(
            "request",
            "",
        )

        old_tokens = re.findall(
            r"[a-z0-9_+.-]+",
            old_request.lower(),
        )

        old_words = {
            word
            for word in old_tokens
            if word not in STOP_WORDS
        }

        # --------------------------------
        # Calculate relevance
        # --------------------------------

        overlap = len(
            request_words & old_words
        )

        if overlap == 0:
            continue

        relevance_score = overlap

        # Rejected upgrades contain useful
        # mistakes that Chopper should avoid.
        if not experience.get(
            "approved",
            False,
        ):
            relevance_score += 1

        scored.append(
            (
                relevance_score,
                experience,
            )
        )

    # Highest relevance first.
    scored.sort(
        key=lambda item: item[0],
        reverse=True,
    )

    return [
        experience
        for _, experience
        in scored[:limit]
    ]

if __name__ == "__main__":

    memories = load_upgrade_memory()

    print(
        "Chopper Upgrade Memory"
    )

    print(
        f"\nTotal experiences: "
        f"{len(memories)}"
    )

    if memories:

        print(
            "\nRecent experiences:"
        )

        for experience in memories[-5:]:

            print(
                f"\n- Request: "
                f"{experience.get('request', '')}"
            )

            print(
                f"  File: "
                f"{experience.get('file', '')}"
            )

            print(
                f"  Function: "
                f"{experience.get('function', '')}"
            )

            print(
                f"  Score: "
                f"{experience.get('score', 0)}"
            )

            print(
                f"  Approved: "
                f"{experience.get('approved', False)}"
            )