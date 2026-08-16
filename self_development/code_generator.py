import json
import re

from ollama import chat

from self_development.code_analyzer import (
    find_relevant_files,
)

from self_development.upgrade_memory import (
    find_relevant_experiences,
)

from self_development.project_scanner import (
    read_source,
)

from self_development.function_editor import (
    get_function_source,
)


UPGRADE_MODEL = "qwen2.5:3b"

MAX_FILES = 1
MAX_SOURCE_CHARS = 7000
MAX_GENERATION_ATTEMPTS = 3


def clean_json_response(text):
    """
    Extract one JSON object from model output.
    """

    text = text.strip()

    text = re.sub(
        r"^```(?:json)?",
        "",
        text,
        flags=re.IGNORECASE,
    )

    text = re.sub(
        r"```$",
        "",
        text,
    )

    text = text.strip()

    try:
        return json.loads(
            text
        )

    except json.JSONDecodeError:
        pass

    start = text.find("{")
    end = text.rfind("}")

    if start == -1 or end == -1:
        raise ValueError(
            "Model did not return JSON."
        )

    candidate = text[
        start:end + 1
    ]

    try:
        return json.loads(
            candidate
        )

    except json.JSONDecodeError as error:
        raise ValueError(
            "Could not parse upgrade JSON."
        ) from error


def collect_relevant_code(request):
    """
    Collect the strongest matching source file.
    """

    matches = find_relevant_files(
        request
    )

    if not matches:
        return []

    selected = matches[
        :MAX_FILES
    ]

    files = []

    for item in selected:

        relative_path = item["file"]

        try:
            source = read_source(
                relative_path
            )

        except Exception:
            continue

        if len(source) > MAX_SOURCE_CHARS:
            source = source[
                :MAX_SOURCE_CHARS
            ]

        files.append(
            {
                "file": relative_path,
                "score": item["score"],
                "functions": item.get(
                    "functions",
                    [],
                ),
                "source": source,
            }
        )

    return files


def build_upgrade_prompt(
    request,
    files,
):
    """
    Ask the model for a function-level upgrade.
    """

    # --------------------------------
    # Build source-code context
    # --------------------------------

    blocks = []

    for item in files:

        function_names = ", ".join(
            item.get(
                "functions",
                [],
            )
        )

        blocks.append(
            f"""
FILE:
{item['file']}

AVAILABLE FUNCTIONS:
{function_names}

SOURCE:
{item['source']}
"""
        )

    source_context = "\n".join(
        blocks
    )

    # --------------------------------
    # Load previous upgrade lessons
    # --------------------------------

    experiences = find_relevant_experiences(
        request,
        limit=5,
    )

    memory_lines = []

    for experience in experiences:

        problems = experience.get(
            "problems",
            [],
        )

        suggestion = experience.get(
            "suggestion",
            "",
        )

        approved = experience.get(
            "approved",
            False,
        )

        result_type = experience.get(
            "result_type",
            (
                "successful_upgrade"
                if approved
                else "rejected_upgrade"
            ),
        )

        # Older memories may not contain
        # result_type, so approved is used
        # as a fallback.
        if result_type == "successful_upgrade":
            lesson_type = (
                "SUCCESSFUL UPGRADE - "
                "useful approach to learn from"
            )
        else:
            lesson_type = (
                "REJECTED UPGRADE - "
                "mistakes to avoid"
            )

        memory_lines.append(
            f"""
    Previous upgrade experience:
    Type: {lesson_type}
    File: {experience.get("file", "")}
    Function: {experience.get("function", "")}
    Approved: {approved}
    Score: {experience.get("score", 0)}
    Problems: {problems}
    Lesson: {suggestion}
    """.strip()
        )

    if memory_lines:

        upgrade_memory_text = "\n\n".join(
            memory_lines
        )

    else:

        upgrade_memory_text = (
            "No relevant previous upgrade "
            "experiences found."
        )

    # --------------------------------
    # Build AI upgrade prompt
    # --------------------------------

    return f"""
You are Chopper's software upgrade engineer.

USER UPGRADE REQUEST:

{request}

Your task is to propose ONE small,
safe function-level improvement.

IMPORTANT RULES:

- Select exactly one supplied Python file.
- Select exactly one existing function.
- Do not invent a function name.
- Return the COMPLETE replacement function.
- The replacement must begin with def or async def.

- Preserve ALL existing useful behavior unless the
  upgrade specifically requires changing it.
- Prefer the smallest possible modification.
- Do not delete existing branches, special cases,
  validations, or fallback behavior unnecessarily.
- Preserve existing inputs, outputs, side effects,
  and edge-case handling.
- Keep the new function structurally similar to the
  original whenever possible.

- For performance upgrades, first look for repeated
  computation, inefficient loops, duplicate work,
  unnecessary conversions, caching opportunities,
  or better data structures.
- Do not replace the entire algorithm unless there
  is a clear technical reason.
- Do not claim an optimization unless the proposed
  change can reasonably improve performance.

- Before generating the replacement:
  1. Identify the actual bottleneck.
  2. Identify behavior that must be preserved.
  3. Modify only what is necessary.

- Preserve compatibility with the existing project.
- Do not remove safety checks.
- Do not modify unrelated behavior.

PREVIOUS SELF-DEVELOPMENT LESSONS:

{upgrade_memory_text}

Use these lessons to avoid repeating previous
upgrade mistakes.

IMPORTANT:
- Previous rejected upgrades are examples of what
  NOT to repeat.
- Preserve behavior that previous reviewers said
  was accidentally removed.
- Apply useful reviewer suggestions when relevant.
- Do not blindly copy a previous failed proposal.

OUTPUT RULES:

- Do not write markdown.
- Do not explain outside JSON.
- Return valid JSON only.

Return exactly this structure:

{{
    "file": "relative/path.py",
    "function": "existing_function_name",
    "reason": "short explanation",
    "new_code": "complete replacement function"
}}

AVAILABLE CHOPPER CODE:

{source_context}
""".strip()


def validate_new_function(
    function_name,
    new_code,
    old_code=None,
):
    """
    Validate an AI-generated replacement function.

    Checks:
    - valid Python syntax
    - exactly one top-level function
    - correct function name
    - no unrelated top-level statements
    - preserves function signature when old_code
      is available
    """

    import ast

    # --------------------------------
    # Parse new function
    # --------------------------------

    try:
        new_tree = ast.parse(
            new_code
        )

    except SyntaxError as error:
        return (
            False,
            f"Generated function has "
            f"invalid syntax: {error}",
        )

    # --------------------------------
    # Only one top-level function
    # --------------------------------

    if len(new_tree.body) != 1:
        return (
            False,
            "new_code must contain exactly "
            "one top-level function and no "
            "extra top-level statements.",
        )

    new_function = new_tree.body[0]

    if not isinstance(
        new_function,
        (
            ast.FunctionDef,
            ast.AsyncFunctionDef,
        ),
    ):
        return (
            False,
            "new_code must contain one "
            "Python function.",
        )

    # --------------------------------
    # Check function name
    # --------------------------------

    if new_function.name != function_name:
        return (
            False,
            "Generated function name does "
            "not match selected function.",
        )

    # --------------------------------
    # Preserve existing signature
    # --------------------------------

    if old_code is not None:

        try:
            old_tree = ast.parse(
                old_code
            )

        except SyntaxError:
            return (
                False,
                "Existing function could "
                "not be parsed.",
            )

        if not old_tree.body:
            return (
                False,
                "Existing function is empty.",
            )

        old_function = old_tree.body[0]

        if not isinstance(
            old_function,
            (
                ast.FunctionDef,
                ast.AsyncFunctionDef,
            ),
        ):
            return (
                False,
                "Existing source is not "
                "a function.",
            )

        old_args = ast.dump(
            old_function.args,
            include_attributes=False,
        )

        new_args = ast.dump(
            new_function.args,
            include_attributes=False,
        )

        if old_args != new_args:
            return (
                False,
                "Generated function changed "
                "the existing function "
                "signature.",
            )

    return True, ""


def generate_upgrade(
    request,
    feedback="",
    rejected_codes=None,
):
    """
    Generate one validated function-level
    upgrade proposal.

    This function does NOT modify files.
    """
    if rejected_codes is None:
        rejected_codes = set()

    files = collect_relevant_code(
        request
    )

    if not files:
        return {
            "success": False,
            "error": (
                "No relevant source files "
                "were found."
            ),
        }

    base_prompt = build_upgrade_prompt(
        request,
        files,
    )
    if feedback:
        base_prompt += f"""

    QUALITY REVIEW FEEDBACK:

    {feedback}

    Use this feedback to improve the proposal,
    but keep the original user request unchanged.
    """

    selected_files = {
        item["file"].replace(
            "\\",
            "/",
        ): item
        for item in files
    }

    feedback = ""
    last_error = ""

    # Remember proposals generated during
    # this generation session so Chopper
    # does not repeat the same failed code.
    seen_proposals = set()

    for attempt in range(
        1,
        MAX_GENERATION_ATTEMPTS + 1,
    ):

        print(
            f"\n🧠 Generating upgrade proposal "
            f"(attempt {attempt}/"
            f"{MAX_GENERATION_ATTEMPTS})..."
        )

        prompt = base_prompt

        if feedback:
            prompt += f"""

PREVIOUS ATTEMPT FAILED.

ERROR:
{feedback}

Generate a corrected proposal.
Return JSON only.
"""

        try:
            response = chat(
                model=UPGRADE_MODEL,
                messages=[
                    {
                        "role": "user",
                        "content": prompt,
                    }
                ],
                stream=False,
                format="json",
                options={
                    "temperature": 0.1,
                    "num_ctx": 6144,
                    "num_predict": 1000,
                },
            )

            content = (
                response["message"][
                    "content"
                ]
            )

            proposal = clean_json_response(
                content
            )

        except Exception as error:

            last_error = str(
                error
            )

            feedback = last_error
            continue

        required = {
            "file",
            "function",
            "reason",
            "new_code",
        }

        if not required.issubset(
            proposal
        ):
            last_error = (
                "Proposal is missing "
                "required fields."
            )

            feedback = last_error
            continue

        proposed_file = str(
            proposal["file"]
        ).replace(
            "\\",
            "/",
        )

        if proposed_file not in selected_files:

            last_error = (
                "Model selected a file "
                "that was not supplied."
            )

            feedback = last_error
            continue

        function_name = str(
            proposal["function"]
        ).strip()

        available_functions = {
            name
            for name in selected_files[
                proposed_file
            ].get(
                "functions",
                [],
            )
        }

        if function_name not in available_functions:

            last_error = (
                "Model selected a function "
                "that does not exist."
            )

            feedback = last_error
            continue

        new_code = str(
            proposal["new_code"]
        ).strip()

        # --------------------------------
        # Reject repeated proposals
        # --------------------------------

        proposal_key = (
            proposed_file,
            function_name,
            new_code,
        )
        if proposal_key in rejected_codes:

            last_error = (
                "This exact proposal was rejected "
                "by the critic in an earlier review "
                "cycle. Generate a different "
                "implementation."
            )

            feedback = last_error
            continue

        if proposal_key in seen_proposals:

            last_error = (
                "This exact proposal was already "
                "generated during this upgrade "
                "attempt. Generate a different "
                "implementation."
            )

            feedback = last_error
            continue

        seen_proposals.add(
            proposal_key
        )

        try:
            old_code = get_function_source(
                proposed_file,
                function_name,
            )

        except Exception as error:

            last_error = str(
                error
            )

            feedback = last_error
            continue


        valid, validation_error = (
            validate_new_function(
                function_name,
                new_code,
                old_code=old_code,
            )
        )

        if not valid:

            last_error = (
                validation_error
            )

            feedback = last_error
            continue

        if (
            old_code.strip()
            == new_code.strip()
        ):

            last_error = (
                "Generated function is "
                "identical to existing code."
            )

            feedback = last_error
            continue

        print(
            "✅ Valid function-level "
            "proposal generated."
        )

        return {
            "success": True,
            "request": request,
            "attempt": attempt,
            "proposal": {
                "file": proposed_file,
                "function": function_name,
                "reason": proposal[
                    "reason"
                ],
                "old_code": old_code,
                "new_code": new_code,
            },
        }

    return {
        "success": False,
        "error": (
            "Could not generate a valid "
            "function-level proposal after "
            f"{MAX_GENERATION_ATTEMPTS} "
            f"attempts. Last error: "
            f"{last_error}"
        ),
    }


def print_proposal(result):
    """
    Display proposal without modifying code.
    """

    print(
        "\n========================================"
    )
    print(
        "🧠 Chopper Upgrade Proposal"
    )
    print(
        "========================================"
    )

    if not result["success"]:

        print(
            "❌ Proposal generation failed."
        )

        print(
            result.get(
                "error",
                "Unknown error",
            )
        )

        return

    proposal = result[
        "proposal"
    ]

    print(
        f"\nFile:\n"
        f"{proposal['file']}"
    )

    print(
        f"\nFunction:\n"
        f"{proposal['function']}"
    )

    print(
        f"\nReason:\n"
        f"{proposal['reason']}"
    )

    print(
        "\nExisting function:"
    )

    print(
        proposal["old_code"]
    )

    print(
        "\nProposed function:"
    )

    print(
        proposal["new_code"]
    )

    print(
        "\n⚠️ Nothing has been modified."
    )


if __name__ == "__main__":

    result = generate_upgrade(
        "Improve web search speed without "
        "reducing result quality."
    )

    print_proposal(
        result
    )