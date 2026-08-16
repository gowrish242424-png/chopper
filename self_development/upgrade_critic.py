import json
import re

from ollama import chat


CRITIC_MODEL = "qwen3:8b"
MAX_CRITIC_ATTEMPTS = 3


def clean_json_response(text):
    """
    Extract and parse JSON from the critic response.

    Handles:
    - normal JSON
    - ```json code fences
    - extra text before/after JSON
    - nested JSON braces safely
    """

    if not isinstance(text, str):
        raise ValueError(
            "Critic response is not text."
        )

    text = text.strip()

    if not text:
        raise ValueError(
            "Critic returned an empty response."
        )

    # --------------------------------
    # 1. Remove markdown code fences
    # --------------------------------

    text = re.sub(
        r"^\s*```(?:json)?\s*",
        "",
        text,
        flags=re.IGNORECASE,
    )

    text = re.sub(
        r"\s*```\s*$",
        "",
        text,
    ).strip()

    # --------------------------------
    # 2. Try complete response first
    # --------------------------------

    try:
        result = json.loads(text)

        if isinstance(result, dict):
            return result

    except json.JSONDecodeError:
        pass

    # --------------------------------
    # 3. Search for a valid JSON object
    # --------------------------------

    decoder = json.JSONDecoder()

    for index, character in enumerate(text):

        if character != "{":
            continue

        try:
            result, _ = decoder.raw_decode(
                text[index:]
            )

            if isinstance(result, dict):
                return result

        except json.JSONDecodeError:
            continue

    # --------------------------------
    # 4. Nothing recoverable
    # --------------------------------

    raise ValueError(
        "Critic did not return valid JSON."
    ) 


def build_critic_prompt(
    request,
    proposal,
):
    """Build a focused code-review prompt."""

    return f"""
You are the quality-control reviewer for
Chopper's self-development system.

USER REQUEST:
{request}

FILE:
{proposal["file"]}

FUNCTION:
{proposal["function"]}

GENERATOR'S REASON:
{proposal["reason"]}

CURRENT FUNCTION:
{proposal["old_code"]}

PROPOSED FUNCTION:
{proposal["new_code"]}

Review whether the proposed function is
actually an improvement over the current
function for the user's request.

Check carefully for:

1. Loss of existing behavior
2. Incorrect logic
3. Undefined variables or invalid references
4. Recursion mistakes
5. API compatibility
6. Changed return types
7. Security or safety regressions
8. Performance regressions
9. Unnecessary complexity
10. Whether it really satisfies the request

IMPORTANT:

- Syntax validity alone is NOT enough.
- Reject changes that remove useful existing
  behavior without a strong reason.
- Reject fake optimizations.
- Reject changes whose benefit is unclear.
- Prefer preserving correct existing code over
  accepting a risky change.
- Do not modify code.
- Return JSON only.

Return exactly:

{{
    "approved": false,
    "score": 0,
    "reason": "short review",
    "problems": [
        "problem 1"
    ],
    "suggestion": "specific guidance for a better proposal"
}}

score must be an integer from 0 to 100.

Only set approved to true when:
- the change is clearly correct,
- existing important behavior is preserved,
- it meaningfully addresses the request,
- and score is at least 85.
""".strip()


def review_upgrade(
    request,
    proposal,
):
    """
    Review an AI-generated upgrade.

    Retry if the critic fails to return
    valid structured output.

    This function never modifies files.
    """

    prompt = build_critic_prompt(
        request,
        proposal,
    )

    last_error = ""

    for attempt in range(
        1,
        MAX_CRITIC_ATTEMPTS + 1,
    ):

        print(
            f"\n🔍 Reviewing proposed upgrade "
            f"(attempt {attempt}/"
            f"{MAX_CRITIC_ATTEMPTS})..."
        )

        try:
            response = chat(
                model=CRITIC_MODEL,
                messages=[
                    {
                        "role": "user",
                        "content": prompt,
                    }
                ],
                stream=False,
                format="json",
                options={
                    "temperature": 0.0,
                    "num_ctx": 4096,
                    "num_predict": 800,
                },
            )

            content = response[
                "message"
            ][
                "content"
            ]

            review = clean_json_response(
                content
            )

        except Exception as error:

            last_error = str(error)

            print(
                "⚠️ Critic response invalid. "
                "Retrying..."
            )

            continue

        # -----------------------------
        # Validate score
        # -----------------------------

        try:
            score = int(
                review.get(
                    "score",
                    0,
                )
            )

        except (TypeError, ValueError):
            score = 0

        score = max(
            0,
            min(
                score,
                100,
            ),
        )

        # -----------------------------
        # Validate problems
        # -----------------------------

        problems = review.get(
            "problems",
            [],
        )

        if not isinstance(
            problems,
            list,
        ):
            problems = [
                str(problems)
            ]

        # Remove empty problem entries.
        problems = [
            str(problem).strip()
            for problem in problems
            if str(problem).strip()
        ]

        # -----------------------------
        # Validate approval
        # -----------------------------

        model_approved = (
            review.get(
                "approved"
            ) is True
        )

        # Chopper itself enforces the
        # final approval requirements.
        approved = (
            model_approved
            and score >= 85
            and len(problems) == 0
        )

        return {
            "approved": approved,
            "score": score,
            "reason": str(
                review.get(
                    "reason",
                    "",
                )
            ),
            "problems": problems,
            "suggestion": str(
                review.get(
                    "suggestion",
                    "",
                )
            ),
        }

    # --------------------------------
    # All critic attempts failed
    # --------------------------------

    return {
        "approved": False,
        "score": 0,
        "reason": (
            "Critic execution failed after "
            f"{MAX_CRITIC_ATTEMPTS} attempts."
        ),
        "problems": [
            last_error
        ],
        "suggestion": (
            "Do not apply the upgrade."
        ),
    }


def print_review(review):
    """Display critic result."""

    print(
        "\n========================================"
    )
    print(
        "🔍 Chopper Upgrade Review"
    )
    print(
        "========================================"
    )

    print(
        f"\nScore: "
        f"{review['score']}/100"
    )

    print(
        f"\nApproved: "
        f"{review['approved']}"
    )

    print(
        f"\nReason:\n"
        f"{review['reason']}"
    )

    problems = review[
        "problems"
    ]

    if problems:

        print(
            "\nProblems:"
        )

        for problem in problems:
            print(
                f"- {problem}"
            )

    suggestion = review[
        "suggestion"
    ]

    if suggestion:

        print(
            f"\nSuggestion:\n"
            f"{suggestion}"
        )

    if review["approved"]:

        print(
            "\n✅ Proposal passed "
            "quality review."
        )

    else:

        print(
            "\n❌ Proposal rejected."
        )


if __name__ == "__main__":

    # Deliberately bad proposal for testing.
    test_proposal = {
        "file": "tools/web_tool.py",
        "function": "build_search_queries",
        "reason": (
            "Improve web search speed."
        ),
        "old_code": """
def build_search_queries(query):
    queries = [query]
    return queries
""".strip(),
        "new_code": """
def build_search_queries(query):
    return list(set(query))
""".strip(),
    }

    review = review_upgrade(
        "Improve web search speed without "
        "reducing result quality.",
        test_proposal,
    )

    print_review(
        review
    )