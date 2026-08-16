import json
import re

from ollama import chat


# qwen3:1.7b is very fast but was not reliable enough
# for semantic intent understanding.
QUERY_MODEL = "qwen2.5:3b"


def clean_json(text):
    """Extract and parse JSON returned by the model."""

    if not text:
        return None

    text = text.strip()

    # Remove Markdown code fences
    text = text.replace("```json", "")
    text = text.replace("```", "")
    text = text.strip()

    # First try normal JSON
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        pass

    # Fallback: locate JSON object
    match = re.search(
        r"\{.*\}",
        text,
        re.DOTALL,
    )

    if not match:
        return None

    try:
        return json.loads(match.group())
    except json.JSONDecodeError:
        return None


def detect_user_reference(message):
    """
    Detect whether the user refers to themselves.

    This is a universal language rule, not a list
    of specific questions.
    """

    text = message.lower()

    patterns = [
        r"\bi\b",
        r"\bme\b",
        r"\bmy\b",
        r"\bmine\b",
        r"\bmyself\b",
    ]

    return any(
        re.search(pattern, text)
        for pattern in patterns
    )


def detect_assistant_reference(message):
    """
    Detect whether the user refers to Chopper.
    """

    text = message.lower()

    patterns = [
        r"\byou\b",
        r"\byour\b",
        r"\byours\b",
        r"\byourself\b",
        r"\bchopper\b",
    ]

    return any(
        re.search(pattern, text)
        for pattern in patterns
    )


def explicitly_needs_web(message):
    """
    Detect requests that require fresh/current information.
    """

    text = message.lower().strip()

    # --------------------------------
    # Explicit freshness requests
    # --------------------------------

    freshness_terms = [
        "latest",
        "today",
        "currently",
        "current ",
        "recent",
        "right now",
        "this week",
        "this month",
        "news",
        "live",
        "updated",
        "newest",
        "search the web",
        "search internet",
        "search online",
    ]

    if any(term in text for term in freshness_terms):
        return True

    # --------------------------------
    # Current office-holder questions
    # --------------------------------

    current_roles = [
        "chief minister",
        "cm of",
        "prime minister",
        "president",
        "governor",
        "mayor",
        "ceo",
        "chairman",
        "minister",
    ]

    role_question = any(
        role in text
        for role in current_roles
    )

    if role_question:
        return True

    return False

def understand_query(user_message):
    """
    Understand what the user actually means.

    This does NOT answer the question.
    It produces structured semantic information
    for Chopper's router/context system.
    """

    user_message = user_message.strip()

    if not user_message:
        return {
            "intent": "empty",
            "subject": "none",
            "references_user": False,
            "references_assistant": False,
            "needs_memory": False,
            "needs_web": False,
            "needs_tool": False,
            "needs_general_knowledge": False,
            "normalized_query": "",
            "confidence": 1.0,
        }

    system_prompt = """
You are the semantic understanding engine for Chopper.

You do NOT answer the user's request.

Your only job is to understand the meaning of the message and
describe what information Chopper needs in order to answer it.

Analyze meaning rather than matching exact words.

REFERENCE RULES

The speaker is the user.

Therefore:
- I, me, my, mine, myself = USER
- you, your, yours, yourself, Chopper = ASSISTANT

Examples of concepts, not sentence templates:

A request concerning information ABOUT THE USER may require
personal memory.

A request concerning something Chopper previously learned about
the user's preferences, interests, identity, goals, projects,
history, choices, or characteristics requires memory.

If the user asks for a recommendation BASED ON information known
about them, memory is required.

Internet access is required only when answering depends on
fresh, changing, recent, live, current, or explicitly online
information.

Do NOT request web access simply because a recommendation is
being made.

A tool is required when the user wants an action performed,
such as reading/running/creating/deleting a file, calculating,
executing code, or another external operation.

General knowledge is used for concepts, explanations, reasoning,
coding knowledge, mathematics, and ordinary questions that do
not require personal memory or fresh information.

Multiple information sources can be required simultaneously.

Return ONLY JSON.

Schema:

{
    "intent": "short description of what the user wants",
    "subject": "main subject",
    "references_user": true,
    "references_assistant": false,
    "needs_memory": false,
    "needs_web": false,
    "needs_tool": false,
    "needs_general_knowledge": true,
    "normalized_query": "clear unambiguous version of the request",
    "confidence": 0.95
}

Do not include explanations outside the JSON.
"""

    try:
        response = chat(
            model=QUERY_MODEL,
            messages=[
                {
                    "role": "system",
                    "content": system_prompt,
                },
                {
                    "role": "user",
                    "content": user_message,
                },
            ],
            stream=False,
            options={
                "temperature": 0,
                "num_predict": 220,
                "num_ctx": 2048,
            },
        )

        content = (
            response["message"]
            .get("content", "")
            .strip()
        )

        result = clean_json(content)

        if result is None:
            raise ValueError(
                "Query model did not return valid JSON."
            )

        # --------------------------------
        # Universal reference resolution
        # --------------------------------

        user_reference = detect_user_reference(
            user_message
        )

        assistant_reference = (
            detect_assistant_reference(
                user_message
            )
        )

        # The deterministic language parser can correct
        # obvious pronoun mistakes made by the small LLM.
        references_user = (
            user_reference
            or bool(
                result.get(
                    "references_user",
                    False,
                )
            )
        )

        references_assistant = (
            assistant_reference
            or bool(
                result.get(
                    "references_assistant",
                    False,
                )
            )
        )

        # --------------------------------
        # Memory decision
        # --------------------------------

        needs_memory = bool(
            result.get(
                "needs_memory",
                False,
            )
        )

        intent = str(
            result.get(
                "intent",
                "general",
            )
        ).lower()

        normalized = str(
            result.get(
                "normalized_query",
                user_message,
            )
        ).lower()

        # If the request is ABOUT the user or asks Chopper
        # to use what it knows about the user, memory is needed.
        personal_meaning_terms = [
            "about me",
            "about the user",
            "know about me",
            "known about me",
            "remember about me",
            "my preference",
            "my preferences",
            "my interest",
            "my interests",
            "my profile",
            "my information",
            "based on what you know",
            "based on my",
        ]

        personal_request = any(
            term in user_message.lower()
            or term in normalized
            or term in intent
            for term in personal_meaning_terms
        )

        if references_user and personal_request:
            needs_memory = True

        # --------------------------------
        # Web decision
        # --------------------------------

        model_needs_web = bool(
            result.get(
                "needs_web",
                False,
            )
        )

        fresh_request = explicitly_needs_web(
            user_message
        )

        # Freshness words always enable web.
        #
        # For non-fresh requests, trust the semantic model
        # only when it explicitly identifies online/current
        # information as necessary.
        if fresh_request:
            needs_web = True
        else:
            needs_web = (
                model_needs_web
                and not needs_memory
            )

        return {
            "intent": result.get(
                "intent",
                "general",
            ),

            "subject": result.get(
                "subject",
                "general",
            ),

            "references_user": references_user,

            "references_assistant": references_assistant,

            "needs_memory": needs_memory,

            "needs_web": needs_web,

            "needs_tool": bool(
                result.get(
                    "needs_tool",
                    False,
                )
            ),

            "needs_general_knowledge": bool(
                result.get(
                    "needs_general_knowledge",
                    True,
                )
            ),

            "normalized_query": result.get(
                "normalized_query",
                user_message,
            ),

            "confidence": float(
                result.get(
                    "confidence",
                    0.5,
                )
            ),
        }

    except Exception as error:
        print(
            f"Query understanding error: {error}"
        )

        return {
            "intent": "general",
            "subject": "general",

            "references_user":
                detect_user_reference(
                    user_message
                ),

            "references_assistant":
                detect_assistant_reference(
                    user_message
                ),

            "needs_memory": False,

            "needs_web":
                explicitly_needs_web(
                    user_message
                ),

            "needs_tool": False,
            "needs_general_knowledge": True,

            "normalized_query":
                user_message,

            "confidence": 0.0,
        }