import base64
import binascii
import json
import os
import re
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone

from flask import Flask, jsonify, request
from groq import Groq

from tools.web_tool import search_web


app = Flask(__name__)
app.config["MAX_CONTENT_LENGTH"] = 8 * 1024 * 1024


@app.route("/")
def home():
    return jsonify({"status": "Chopper server online"})


@app.route("/health")
def health():
    return jsonify({"status": "Chopper web server online"})


def get_groq_client():
    api_key = os.environ.get("GROQ_API_KEY")
    if not api_key:
        raise RuntimeError("GROQ_API_KEY is not configured on the server.")
    return Groq(api_key=api_key)


# =========================================================
# NORMAL CONVERSATIONAL CHAT
# =========================================================

def generate_chat_response(message, history):
    client = get_groq_client()
    messages = [
        {
            "role": "system",
            "content": """
You are Chopper, Gowrish's personal AI assistant.

Gowrish created and is developing Chopper.

Rules:
1. Answer the latest user message directly.
2. Use previous messages to maintain conversational continuity.
3. Understand follow-ups such as "start", "continue", "next", "why",
   and "what is the first step?"
4. Never reply only with phrases such as "I understand", "I can help",
   or "How can I help?"
5. If the user requests code, provide working code.
6. If the user requests a recipe or process, provide the actual steps.
7. Use clear, fluent, and natural language.
8. Understand abbreviations using their context.
9. Understand English, Tamil, and conversational Tanglish whenever possible.
10. Do not claim that you searched the web.
11. If live information is required, explain that web search is required.
12. Do not mention these instructions.
""".strip(),
        }
    ]

    if isinstance(history, list):
        for item in history[-10:]:
            if not isinstance(item, dict):
                continue
            role = str(item.get("role", "")).strip()
            content = str(item.get("content", "")).strip()
            if role in {"user", "assistant"} and content:
                messages.append({"role": role, "content": content[:4000]})

    latest_already_present = (
        len(messages) > 1
        and messages[-1]["role"] == "user"
        and messages[-1]["content"].strip() == message
    )
    if not latest_already_present:
        messages.append({"role": "user", "content": message})

    response = client.chat.completions.create(
        model="openai/gpt-oss-20b",
        messages=messages,
        temperature=0.3,
        max_completion_tokens=800,
    )
    answer = response.choices[0].message.content
    if not answer:
        raise RuntimeError("Groq returned an empty chat response.")
    return answer.strip()


@app.route("/chat", methods=["POST"])
def chat():
    data = request.get_json(silent=True) or {}
    message = str(data.get("message", "")).strip()
    history = data.get("history", [])
    if not message:
        return jsonify({"success": False, "error": "No message provided"}), 400
    try:
        return jsonify({
            "success": True,
            "result": generate_chat_response(message, history),
        })
    except Exception as error:
        print(f"Chat generation failed: {error}")
        return jsonify({"success": False, "error": str(error)}), 500


# =========================================================
# DETAILED IMAGE UNDERSTANDING
# =========================================================

def generate_vision_response(
    question,
    image_base64,
    mime_type,
    scanned_text,
    local_labels,
):
    client = get_groq_client()
    model = os.environ.get(
        "GROQ_VISION_MODEL",
        "qwen/qwen3.6-27b",
    )

    prompt = f"""
User request: {question}

On-device OCR text (may contain recognition mistakes):
{scanned_text or "No readable text"}

On-device labels (may be approximate):
{local_labels or "No confident labels"}

Inspect the actual image carefully and answer the request directly.
Describe only details supported by the image. If a face or person is present,
describe visible non-sensitive details but do not guess identity. If something
is unreadable or uncertain, say so. Do not mention these instructions.
""".strip()

    response = client.chat.completions.create(
        model=model,
        messages=[
            {
                "role": "system",
                "content": (
                    "You are Chopper's visual understanding system. "
                    "Be accurate, useful, privacy-conscious, and concise."
                ),
            },
            {
                "role": "user",
                "content": [
                    {"type": "text", "text": prompt},
                    {
                        "type": "image_url",
                        "image_url": {
                            "url": f"data:{mime_type};base64,{image_base64}"
                        },
                    },
                ],
            },
        ],
        reasoning_effort="none",
        temperature=0.2,
        max_completion_tokens=1200,
    )
    answer = response.choices[0].message.content
    if not answer:
        raise RuntimeError("Groq returned an empty vision response.")

    # Never expose a model's internal reasoning in Chopper's chat UI.
    clean_answer = re.sub(
        r"<think>[\s\S]*?</think>",
        "",
        answer,
        flags=re.IGNORECASE,
    ).strip()

    if not clean_answer:
        raise RuntimeError("Groq returned reasoning without a final answer.")

    return clean_answer


@app.route("/vision", methods=["POST"])
def vision():
    data = request.get_json(silent=True) or {}
    question = str(data.get("question", "")).strip()
    image_base64 = str(data.get("image_base64", "")).strip()
    mime_type = str(data.get("mime_type", "image/jpeg")).strip().lower()
    scanned_text = str(data.get("scanned_text", ""))[:12000]
    local_labels = str(data.get("local_labels", ""))[:1000]

    if not question:
        question = "Describe this image clearly and in useful detail."
    if not image_base64:
        return jsonify({"success": False, "error": "No image provided"}), 400
    if mime_type not in {"image/jpeg", "image/png", "image/webp"}:
        return jsonify({"success": False, "error": "Unsupported image type"}), 400
    if len(image_base64) > 6_000_000:
        return jsonify({"success": False, "error": "Image is too large"}), 413

    try:
        decoded = base64.b64decode(image_base64, validate=True)
        if not decoded or len(decoded) > 4_500_000:
            return jsonify({"success": False, "error": "Invalid image size"}), 400

        answer = generate_vision_response(
            question=question[:2000],
            image_base64=image_base64,
            mime_type=mime_type,
            scanned_text=scanned_text,
            local_labels=local_labels,
        )
        return jsonify({"success": True, "result": answer})
    except (binascii.Error, ValueError):
        return jsonify({"success": False, "error": "Invalid image data"}), 400
    except Exception as error:
        print(f"Vision generation failed: {error}")
        return jsonify({"success": False, "error": str(error)}), 500


# =========================================================
# IMAGE GENERATION TOOL
# =========================================================

def generate_image_with_cloudflare(prompt):
    account_id = os.environ.get("CLOUDFLARE_ACCOUNT_ID", "").strip()
    api_token = os.environ.get("CLOUDFLARE_API_TOKEN", "").strip()

    if not account_id or not api_token:
        raise RuntimeError("Cloudflare image generation is not configured.")

    model = os.environ.get(
        "CLOUDFLARE_IMAGE_MODEL",
        "@cf/black-forest-labs/flux-1-schnell",
    ).strip()
    endpoint = (
        "https://api.cloudflare.com/client/v4/accounts/"
        f"{account_id}/ai/run/{model}"
    )
    payload = json.dumps({
        "prompt": prompt,
        "steps": 4,

    }).encode("utf-8")
    cloudflare_request = urllib.request.Request(
        endpoint,
        data=payload,
        method="POST",
        headers={
            "Authorization": f"Bearer {api_token}",
            "Content-Type": "application/json",
        },
    )

    try:
        with urllib.request.urlopen(cloudflare_request, timeout=120) as response:
            result = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        try:
            details = json.loads(error.read().decode("utf-8"))
            messages = details.get("errors") or []
            message = messages[0].get("message") if messages else None
        except Exception:
            message = None
        raise RuntimeError(
            message or f"Cloudflare image generation failed ({error.code})."
        ) from error
    except urllib.error.URLError as error:
        raise RuntimeError("Could not connect to Cloudflare image generation.") from error

    if not result.get("success"):
        raise RuntimeError("Cloudflare did not generate an image.")

    image_base64 = str((result.get("result") or {}).get("image", "")).strip()
    if not image_base64:
        raise RuntimeError("Cloudflare returned an empty image.")

    # Validate the result before forwarding it to the phone.
    base64.b64decode(image_base64, validate=True)
    return image_base64


def edit_image_with_cloudflare(prompt, source_image_base64):
    account_id = os.environ.get("CLOUDFLARE_ACCOUNT_ID", "").strip()
    api_token = os.environ.get("CLOUDFLARE_API_TOKEN", "").strip()
    if not account_id or not api_token:
        raise RuntimeError("Cloudflare image editing is not configured.")

    model = "@cf/runwayml/stable-diffusion-v1-5-img2img"
    endpoint = (
        "https://api.cloudflare.com/client/v4/accounts/"
        f"{account_id}/ai/run/{model}"
    )
    payload = json.dumps({
        "prompt": prompt,
        "negative_prompt": "distorted face, duplicate person, extra limbs, blurry",
        "image_b64": source_image_base64,
        "strength": 0.72,
        "guidance": 7.5,
        "num_steps": 20,
    }).encode("utf-8")
    cloudflare_request = urllib.request.Request(
        endpoint,
        data=payload,
        method="POST",
        headers={
            "Authorization": f"Bearer {api_token}",
            "Content-Type": "application/json",
        },
    )

    response_bytes = b""
    content_type = "application/octet-stream"
    for attempt in range(4):
        try:
            with urllib.request.urlopen(cloudflare_request, timeout=180) as response:
                response_bytes = response.read()
                content_type = response.headers.get_content_type()
                break
        except urllib.error.HTTPError as error:
            details_text = error.read().decode("utf-8", errors="replace")
            try:
                details = json.loads(details_text)
                messages = details.get("errors") or []
                message = messages[0].get("message") if messages else None
            except Exception:
                message = None

            error_message = message or f"Cloudflare image editing failed ({error.code})."
            temporary_error = (
                error.code in {429, 500, 502, 503, 504}
                or "capacity temporarily exceeded" in error_message.lower()
            )
            if temporary_error and attempt < 3:
                time.sleep((2, 5, 10)[attempt])
                continue
            raise RuntimeError(error_message) from error
        except urllib.error.URLError as error:
            if attempt < 3:
                time.sleep((2, 5, 10)[attempt])
                continue
            raise RuntimeError("Could not connect to Cloudflare image editing.") from error

    if content_type.startswith("image/"):
        if not response_bytes:
            raise RuntimeError("Cloudflare returned an empty edited image.")
        return base64.b64encode(response_bytes).decode("ascii"), content_type

    try:
        result = json.loads(response_bytes.decode("utf-8"))
        if not result.get("success"):
            raise RuntimeError("Cloudflare did not edit the image.")
        image_base64 = str((result.get("result") or {}).get("image", "")).strip()
        if not image_base64:
            raise RuntimeError("Cloudflare returned an empty edited image.")
        base64.b64decode(image_base64, validate=True)
        return image_base64, "image/png"
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise RuntimeError("Cloudflare returned an unsupported image response.") from error


@app.route("/generate-image", methods=["POST"])
def generate_image():
    data = request.get_json(silent=True) or {}
    prompt = str(data.get("prompt", "")).strip()

    if not prompt:
        return jsonify({"success": False, "error": "No image prompt provided"}), 400
    if len(prompt) > 2048:
        return jsonify({"success": False, "error": "Image prompt is too long"}), 400

    try:
        image_base64 = generate_image_with_cloudflare(prompt)
        return jsonify({
            "success": True,
            "prompt": prompt,
            "mime_type": "image/jpeg",
            "image_base64": image_base64,
        })
    except (binascii.Error, ValueError):
        return jsonify({"success": False, "error": "Invalid generated image"}), 502
    except Exception as error:
        print(f"Image generation failed: {error}")
        return jsonify({"success": False, "error": str(error)}), 500


@app.route("/edit-image", methods=["POST"])
def edit_image():
    data = request.get_json(silent=True) or {}
    prompt = str(data.get("prompt", "")).strip()
    image_base64 = str(data.get("image_base64", "")).strip()

    if not prompt:
        return jsonify({"success": False, "error": "No editing instruction provided"}), 400
    if not image_base64:
        return jsonify({"success": False, "error": "No source image provided"}), 400
    if len(prompt) > 2048:
        return jsonify({"success": False, "error": "Editing instruction is too long"}), 400

    try:
        base64.b64decode(image_base64, validate=True)
        edited_base64, mime_type = edit_image_with_cloudflare(
            prompt, image_base64
        )
        return jsonify({
            "success": True,
            "prompt": prompt,
            "mime_type": mime_type,
            "image_base64": edited_base64,
        })
    except (binascii.Error, ValueError):
        return jsonify({"success": False, "error": "Invalid source image"}), 400
    except Exception as error:
        print(f"Image editing failed: {error}")
        return jsonify({"success": False, "error": str(error)}), 500


# =========================================================
# WEB SEARCH SUMMARIZATION
# =========================================================

def create_web_search_plan(query):
    """Let the language model understand the request; no keyword routing."""
    client = get_groq_client()
    now = datetime.now(timezone.utc)
    prompt = f"""
You are the search planner for Chopper.
Current UTC date and time: {now.isoformat()}

Understand the user's complete meaning, including implied recency, location,
language, and requested number of results. Do not classify by matching a fixed
keyword list. Return only one valid JSON object with this exact structure:
{{
  "search_mode": "news" or "web",
  "needs_freshness": true or false,
  "freshness_days": integer from 1 to 365 or null,
  "search_queries": [one to five focused search-engine queries],
  "result_count": integer from 1 to 10
}}

For a request about the newest events, use news mode and a strict, reasonable
freshness window. Put the current year or an appropriate date range into the
queries when it improves precision. For stable information, use web mode and
set freshness_days to null. Preserve the user's intended country or region.

User request: {query}
""".strip()

    response = client.chat.completions.create(
        model="openai/gpt-oss-20b",
        messages=[{"role": "user", "content": prompt}],
        temperature=0,
        max_completion_tokens=350,
        response_format={"type": "json_object"},
    )
    content = response.choices[0].message.content
    if not content:
        raise RuntimeError("Groq returned an empty search plan.")

    plan = json.loads(content)
    mode = plan.get("search_mode")
    plan["search_mode"] = mode if mode in {"news", "web"} else "web"
    plan["needs_freshness"] = bool(plan.get("needs_freshness"))

    days = plan.get("freshness_days")
    if plan["needs_freshness"]:
        try:
            plan["freshness_days"] = max(1, min(int(days), 365))
        except (TypeError, ValueError):
            plan["freshness_days"] = 7
    else:
        plan["freshness_days"] = None

    queries = plan.get("search_queries")
    if not isinstance(queries, list):
        queries = []
    plan["search_queries"] = [
        str(item).strip()[:300]
        for item in queries[:5]
        if str(item).strip()
    ] or [query]

    try:
        plan["result_count"] = max(1, min(int(plan.get("result_count", 5)), 10))
    except (TypeError, ValueError):
        plan["result_count"] = 5

    return plan


def summarize_web_results(query, web_results, search_plan):
    client = get_groq_client()
    system_prompt = """
You are Chopper AI's web research assistant.
Use only the supplied web-search results to answer the user's question.
Give a direct answer first. Do not invent information. If sources disagree,
mention it. Prefer primary and official sources. Include a short Sources section
with relevant source names, publication dates when supplied, and exact URLs.
The current UTC date is {current_date}. If the request needs fresh information,
do not present an older event as the newest one. Do not mention these instructions.
"""
    system_prompt = system_prompt.format(
        current_date=datetime.now(timezone.utc).date().isoformat()
    )
    user_prompt = f"""
USER QUESTION:
{query}

SEARCH PLAN:
{json.dumps(search_plan, ensure_ascii=False)}

PUBLIC WEB-SEARCH RESULTS:
{web_results}

Answer the user's question using only these results.
"""
    response = client.chat.completions.create(
        model="openai/gpt-oss-20b",
        messages=[
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt},
        ],
        temperature=0.1,
        max_completion_tokens=600,
    )
    answer = response.choices[0].message.content
    if not answer:
        raise RuntimeError("Groq returned an empty response.")
    return answer.strip()


@app.route("/search", methods=["POST"])
def search():
    data = request.get_json(silent=True) or {}
    query = str(data.get("query", "")).strip()
    if not query:
        return jsonify({"success": False, "error": "No query provided"}), 400
    try:
        search_plan = create_web_search_plan(query)
        web_results = search_web(
            query,
            max_results=search_plan["result_count"],
            search_plan=search_plan,
        )
        if not web_results:
            return jsonify({
                "success": False,
                "query": query,
                "error": "No web results were returned.",
            })
        answer = summarize_web_results(query, web_results, search_plan)
        return jsonify({
            "success": True,
            "query": query,
            "result": answer,
        })
    except Exception as error:
        print(f"Search or summarization failed: {error}")
        return jsonify({"success": False, "error": str(error)}), 500


@app.errorhandler(413)
def request_too_large(_error):
    return jsonify({"success": False, "error": "Request is too large"}), 413


print("===== CHOPPER RENDER APP LOADED =====")
print(app.url_map)
