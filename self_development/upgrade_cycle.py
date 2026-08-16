from self_development.code_generator import (
    generate_upgrade,
)

from self_development.upgrade_critic import (
    review_upgrade,
    print_review,
)
from self_development.upgrade_memory import (
    save_upgrade_experience,
)

MAX_REVIEW_CYCLES = 3


def run_upgrade_cycle(request):
    """
    Generate an upgrade, review it,
    and retry using critic feedback.

    Nothing is modified yet.
    """

    feedback_text = ""

    # Remember proposals rejected during
    # this complete review cycle.
    rejected_codes = set()

    for cycle in range(
        1,
        MAX_REVIEW_CYCLES + 1,
    ):

        print(
            "\n========================================"
        )
        print(
            f"🔄 Upgrade Cycle "
            f"{cycle}/{MAX_REVIEW_CYCLES}"
        )
        print(
            "========================================"
        )

        # --------------------------------
        # 1. Generate proposal
        # --------------------------------
        result = generate_upgrade(
            request,
            feedback=feedback_text,
            rejected_codes=rejected_codes,
        )

        if not result["success"]:

            print(
                "\n❌ Generator failed:"
            )

            print(
                result.get(
                    "error",
                    "Unknown error",
                )
            )

            continue

        proposal = result[
            "proposal"
        ]

        print(
            "\n✅ Proposal generated"
        )

        print(
            f"File     : "
            f"{proposal['file']}"
        )

        print(
            f"Function : "
            f"{proposal['function']}"
        )

        # --------------------------------
        # 2. Critic review
        # --------------------------------
        review = review_upgrade(
            request,
            proposal,
        )

        print_review(
            review
        )

        # --------------------------------
        # Remember genuine review results
        # --------------------------------

        review_reason = review.get(
            "reason",
            "",
        )

        critic_failed = (
            "Critic execution failed"
            in review_reason
        )

        if not critic_failed:

            save_upgrade_experience(
                request=request,
                file_path=proposal["file"],
                function_name=proposal["function"],
                approved=review["approved"],
                score=review.get(
                    "score",
                    0,
                ),
                problems=review.get(
                    "problems",
                    [],
                ),
                suggestion=review.get(
                    "suggestion",
                    "",
                ),
            )

        else:

            print(
                "\n⚠️ Critic system failure "
                "was not saved as an "
                "upgrade lesson."
            )

        # --------------------------------
        # 3. Accepted
        # --------------------------------
        if review["approved"]:

            print(
                "\n========================================"
            )
            print(
                "✅ HIGH-QUALITY PROPOSAL READY"
            )
            print(
                "========================================"
            )

            print(
                "Nothing has been modified yet."
            )

            return {
                "success": True,
                "proposal": proposal,
                "review": review,
                "cycle": cycle,
            }
        # --------------------------------
        # Remember rejected proposal code
        # --------------------------------

        rejected_key = (
            proposal["file"],
            proposal["function"],
            proposal["new_code"].strip(),
        )

        rejected_codes.add(
            rejected_key
        )
        # --------------------------------
        # 4. Build retry feedback
        # --------------------------------
        problems = "\n".join(
            f"- {problem}"
            for problem in review[
                "problems"
            ]
        )

        feedback_text = f"""
        Previous proposal was rejected.

        Problems:
        {problems}

        Reviewer suggestion:
        {review["suggestion"]}
        """.strip()

        print(
            "\n🔁 Proposal rejected. "
            "Using reviewer feedback "
            "for the next attempt."
        )

    # --------------------------------
    # Failed all cycles
    # --------------------------------
    print(
        "\n========================================"
    )
    print(
        "❌ NO ACCEPTABLE UPGRADE FOUND"
    )
    print(
        "========================================"
    )

    print(
        "Live Chopper remains unchanged."
    )

    return {
        "success": False,
        "error": (
            "No proposal passed quality "
            "review."
        ),
    }


if __name__ == "__main__":

    result = run_upgrade_cycle(
        "Improve web search speed without "
        "reducing result quality."
    )

    print(
        "\nFinal result:"
    )

    print(
        result["success"]
    )