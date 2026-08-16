import json
import re

from ollama import chat

from core.config import (
    MODEL_FAST,
    MODEL_SMART,
)

from core.mode_manager import get_current_mode


def _extract_json_object(text):
    """
    Try to recover a JSON object from model output.
    """

    if not text:
        return None

    text = text.strip()

    # Remove markdown fences
    text = text.replace("```json", "")
    text = text.replace("```", "")
    text = text.strip()

    # First attempt: direct JSON
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        pass

    # Second attempt: extract first {...} block
    match = re.search(
        r"\{.*\}",
        text,
        re.DOTALL,
    )

    if not match:
        return None

    candidate = match.group()

    try:
        return json.loads(candidate)
    except json.JSONDecodeError:
        return None


def _safe_evidence_result(result):
    """
    Validate and normalize extracted evidence.
    """

    if not isinstance(result, dict):
        return {
            "claims": [],
            "summary": "",
        }

    raw_claims = result.get(
        "claims",
        [],
    )

    clean_claims = []

    if isinstance(raw_claims, list):

        for item in raw_claims:

            if not isinstance(item, dict):
                continue

            claim = str(
                item.get("claim", "")
            ).strip()

            if not claim:
                continue

            raw_sources = item.get(
                "sources",
                [],
            )

            clean_sources = []

            if isinstance(raw_sources, list):

                for source in raw_sources:

                    if not isinstance(source, dict):
                        continue

                    name = str(
                        source.get("name", "")
                    ).strip()

                    url = str(
                        source.get("url", "")
                    ).strip()

                    if not name and not url:
                        continue

                    clean_sources.append(
                        {
                            "name": name,
                            "url": url,
                        }
                    )

            try:
                confidence = float(
                    item.get(
                        "confidence",
                        0.5,
                    )
                )
            except Exception:
                confidence = 0.5

            confidence = max(
                0.0,
                min(
                    confidence,
                    1.0,
                ),
            )

            clean_claims.append(
                {
                    "claim": claim,
                    "sources": clean_sources,
                    "confidence": confidence,
                }
            )

    summary = str(
        result.get(
            "summary",
            "",
        )
    ).strip()

    return {
        "claims": clean_claims,
        "summary": summary,
    }


def extract_evidence(
    user_query,
    web_context,
):
    """
    Convert ranked web results into grounded evidence.
    """

    if not web_context.strip():
        return {
            "claims": [],
            "summary": "",
        }

    prompt = f"""
You are Chopper's evidence extraction system.

Do NOT answer the user.

Read the supplied web results and extract only factual claims
that are directly supported by those results.

Rules:
- Use only the supplied WEB RESULTS.
- Do not invent facts.
- Every claim must have at least one supporting source.
- Prefer stronger and more recent sources.
- Ignore irrelevant results.
- Merge duplicate reports of the same event.
- If several independent sources support one claim,
  include all of them.
- Keep claims concise.
- Do not include speculation unless the source clearly labels it.
- Return JSON only.
- Do not use markdown.
- Do not add text before or after the JSON.

Return exactly this structure:

{{
  "claims": [
    {{
      "claim": "supported factual statement",
      "sources": [
        {{
          "name": "source domain",
          "url": "https://example.com/article"
        }}
      ],
      "confidence": 0.95
    }}
  ],
  "summary": "short overall evidence summary"
}}

USER QUERY:
{user_query}

WEB RESULTS:
{web_context}
"""

    try:
        current_mode = get_current_mode()

        if current_mode == "research":
            evidence_model = MODEL_SMART
        else:
            evidence_model = MODEL_FAST
        response = chat(
            model=evidence_model,
            messages=[
                {
                    "role": "user",
                    "content": prompt,
                }
            ],
            stream=False,
            format="json",
            options={
                "temperature": 0,
                "num_predict": 350,
                "num_ctx": 3072,
            },
        )

        text = (
            response["message"]
            .get("content", "")
            .strip()
        )

        result = _extract_json_object(
            text
        )

        if result is None:
            print(
                "Evidence extraction warning: "
                "model returned invalid JSON."
            )

            return {
                "claims": [],
                "summary": "",
            }

        return _safe_evidence_result(
            result
        )

    except Exception as error:
        print(
            f"Evidence extraction error: {error}"
        )

        return {
            "claims": [],
            "summary": "",
        }


def format_evidence(evidence):
    """
    Convert structured evidence into text
    for Chopper's final model.
    """

    claims = evidence.get(
        "claims",
        [],
    )

    if not claims:
        return ""

    blocks = []

    for index, item in enumerate(
        claims,
        start=1,
    ):

        claim = item.get(
            "claim",
            "",
        )

        confidence = item.get(
            "confidence",
            0,
        )

        sources = item.get(
            "sources",
            [],
        )

        source_lines = []

        for source in sources:

            name = source.get(
                "name",
                "",
            )

            url = source.get(
                "url",
                "",
            )

            if name and url:
                source_lines.append(
                    f"- {name}: {url}"
                )

            elif url:
                source_lines.append(
                    f"- {url}"
                )

            elif name:
                source_lines.append(
                    f"- {name}"
                )

        source_text = (
            "\n".join(source_lines)
            if source_lines
            else "- No source supplied"
        )

        blocks.append(
            f"Claim {index}:\n"
            f"{claim}\n"
            f"Confidence: {confidence:.2f}\n"
            f"Sources:\n"
            f"{source_text}"
        )

    return "\n\n".join(
        blocks
    )