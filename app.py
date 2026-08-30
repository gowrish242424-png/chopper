import base64
import binascii
import io
import json
import os
import re
import secrets
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone

from flask import Flask, jsonify, request
from groq import Groq
from PIL import Image

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


def message_needs_live_search(message):
    """Detect live-information questions received through the sidebar chat."""
    text = message.lower().strip()

    live_phrases = (
        "weather",
        "climate",
        "temperature",
        "forecast",
        "latest",
        "news",
        "breaking",
        "current time",
        "time now",
        "what time",
        "today's date",
        "todays date",
        "current date",
        "date today",
        "right now",
        "currently",
        "current prime minister",
        "current chief minister",
        "who is the prime minister",
        "who is the pm",
        "who is the chief minister",
        "who is the cm",
        "current president",
        "current governor",
        "current ceo",
    )

    return any(phrase in text for phrase in live_phrases)


def generate_live_search_response(query):
    """Run the same web-search pipeline used by the full Chopper screen."""
    search_plan = create_web_search_plan(query)
    web_results = search_web(
        query,
        max_results=search_plan["result_count"],
        search_plan=search_plan,
    )
    if not web_results:
        raise RuntimeError("No web results were returned.")
    return summarize_web_results(query, web_results, search_plan)


@app.route("/chat", methods=["POST"])
def chat():
    data = request.get_json(silent=True) or {}
    message = str(data.get("message", "")).strip()
    history = data.get("history", [])
    if not message:
        return jsonify({"success": False, "error": "No message provided"}), 400
    try:
        # The sidebar uses /chat for every message. Route live questions through
        # the same web pipeline as the full Chopper screen.
        if message_needs_live_search(message):
            return jsonify({
                "success": True,
                "result": generate_live_search_response(message),
            })

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

CLOUDFLARE_IMAGE_MODEL = os.environ.get(
    "CLOUDFLARE_IMAGE_MODEL",
    "@cf/black-forest-labs/flux-2-klein-4b",
).strip()
CLOUDFLARE_GENERATION_FALLBACK_MODEL = (
    "@cf/black-forest-labs/flux-1-schnell"
)
CLOUDFLARE_EDIT_FALLBACK_MODEL = (
    "@cf/stabilityai/stable-diffusion-xl-base-1.0"
)
SUPPORTED_IMAGE_QUALITIES = {"low", "medium", "high", "auto"}
SUPPORTED_IMAGE_CATEGORIES = {
    "portrait",
    "vehicle",
    "anime",
    "landscape",
    "product",
    "typography",
    "architecture",
    "food",
    "general",
}
SUPPORTED_IMAGE_STYLES = {
    "photorealistic",
    "cinematic",
    "anime",
    "illustration",
    "watercolor",
    "oil-painting",
    "3d-render",
    "pixel-art",
    "minimalist",
    "unspecified",
}

IMAGE_CATEGORY_PATTERNS = (
    ("typography", ("poster", "logo", "typography", "flyer", "book cover")),
    (
        "vehicle",
        (
            "car", "bike", "motorcycle", "truck", "bus", "vehicle",
            "nissan", "ferrari", "lamborghini", "porsche", "bmw",
            "mercedes", "audi", "tesla",
        ),
    ),
    (
        "anime",
        (
            "anime", "manga", "naruto", "one piece", "dragon ball",
            "cartoon character",
        ),
    ),
    (
        "landscape",
        (
            "landscape", "mountain", "beach", "forest", "waterfall",
            "sunset", "sunrise", "ocean", "river", "countryside",
            "cityscape", "skyline",
        ),
    ),
    (
        "portrait",
        (
            "portrait", "headshot", "selfie", "person", "man", "woman",
            "boy", "girl", "actor", "actress", "singer", "celebrity",
            "politician", "cricketer", "footballer",
        ),
    ),
    (
        "product",
        (
            "product photo", "product shot", "packaging", "advertisement",
            "commercial photo",
        ),
    ),
    (
        "architecture",
        (
            "building", "house", "villa", "temple", "church", "mosque",
            "architecture", "interior", "room",
        ),
    ),
    ("food", ("food", "dish", "meal", "cake", "pizza", "biryani", "drink")),
)

IMAGE_STYLE_PATTERNS = (
    ("cinematic", ("cinematic", "movie still", "film still")),
    ("photorealistic", ("photorealistic", "photo realistic", "realistic photo")),
    ("anime", ("anime", "manga")),
    ("watercolor", ("watercolor", "watercolour")),
    ("oil-painting", ("oil painting", "painted canvas")),
    ("3d-render", ("3d render", "3d-rendered", "cgi")),
    ("pixel-art", ("pixel art", "8-bit art", "16-bit art")),
    ("minimalist", ("minimalist", "minimal style")),
    ("illustration", ("illustration", "digital art", "concept art", "cartoon")),
)

CATEGORY_PROMPT_REQUIREMENTS = {
    "portrait": (
        "Use believable facial structure, natural skin texture, symmetrical eyes, "
        "realistic hands when visible, and flattering subject-focused lighting."
    ),
    "vehicle": (
        "Keep the requested make, model, body shape, paint color, wheel count, "
        "perspective, reflections, road contact, and motion physically coherent."
    ),
    "anime": (
        "Keep the character design, silhouette, clothing, pose, line work, and "
        "anime visual language internally consistent."
    ),
    "landscape": (
        "Use a clear foreground, middle ground, and background with natural depth, "
        "consistent atmosphere, horizon, shadows, and environmental lighting."
    ),
    "product": (
        "Keep the product geometry, material, branding request, clean edges, and "
        "studio lighting suitable for a professional catalogue image."
    ),
    "typography": (
        "Treat every quoted word as exact text, spell it correctly, keep it legible, "
        "and use a balanced layout with clear visual hierarchy."
    ),
    "architecture": (
        "Use structurally coherent geometry, straight lines, consistent perspective, "
        "realistic materials, scale, and lighting."
    ),
    "food": (
        "Use appetizing natural texture, accurate ingredients, realistic portions, "
        "clean plating, and food-photography lighting."
    ),
    "general": (
        "Use coherent geometry, clean edges, consistent lighting, natural depth, "
        "and a clear focal point."
    ),
}

STYLE_PROMPT_REQUIREMENTS = {
    "photorealistic": "Render as a believable photograph with natural materials and optics.",
    "cinematic": "Use cinematic framing, motivated lighting, controlled contrast, and depth.",
    "anime": "Render with polished anime line work, expressive shapes, and coherent cel shading.",
    "illustration": "Render as a polished professional illustration with deliberate shapes and color.",
    "watercolor": "Use layered watercolor washes, paper texture, and controlled soft edges.",
    "oil-painting": "Use convincing oil-paint texture, brushwork, color mixing, and canvas depth.",
    "3d-render": "Use physically coherent 3D geometry, materials, lighting, and camera perspective.",
    "pixel-art": "Use a consistent pixel grid, limited palette, crisp clusters, and no soft blur.",
    "minimalist": "Use simple intentional forms, restrained colors, and generous negative space.",
    "unspecified": "Choose one coherent visual style appropriate for the requested subject.",
}


def get_cloudflare_credentials():
    account_id = os.environ.get("CLOUDFLARE_ACCOUNT_ID", "").strip()
    api_token = os.environ.get("CLOUDFLARE_API_TOKEN", "").strip()
    if not account_id or not api_token:
        raise RuntimeError(
            "Cloudflare image generation is not configured on the Render server."
        )
    return account_id, api_token


def _requested_image_quality(prompt, requested_quality=None):
    """Use medium normally and high only when the request needs extra detail."""
    quality = str(requested_quality or "").strip().lower()
    if quality in SUPPORTED_IMAGE_QUALITIES:
        return quality

    high_quality_phrases = (
        "high quality",
        "high-quality",
        "high detail",
        "high-detail",
        "high resolution",
        "ultra detailed",
        "photorealistic",
        "photo realistic",
        "professional",
        "cinematic",
        "intricate",
        "accurate text",
        "exact text",
        "typography",
        "poster",
        "logo",
        "product photo",
        "product shot",
        "4k",
        "8k",
    )
    lowered = prompt.lower()
    return "high" if any(item in lowered for item in high_quality_phrases) else "medium"


def _requested_image_dimensions(prompt, requested_size=None, source_size=None):
    """Choose a useful Cloudflare aspect ratio without an Android update."""
    size = str(requested_size or "").strip().lower()
    explicit_sizes = {
        "1024x1024": (1024, 1024),
        "1024x768": (1024, 768),
        "768x1024": (768, 1024),
        "1536x1024": (1024, 768),
        "1024x1536": (768, 1024),
    }
    if size in explicit_sizes:
        return explicit_sizes[size]

    lowered = prompt.lower()
    portrait_phrases = (
        "portrait orientation",
        "vertical image",
        "vertical poster",
        "phone wallpaper",
        "mobile wallpaper",
        "story format",
        "9:16",
    )
    landscape_phrases = (
        "landscape orientation",
        "horizontal image",
        "horizontal poster",
        "wide shot",
        "widescreen",
        "desktop wallpaper",
        "banner",
        "16:9",
    )
    if any(item in lowered for item in portrait_phrases):
        return 768, 1024
    if any(item in lowered for item in landscape_phrases):
        return 1024, 768
    if source_size:
        source_width, source_height = source_size
        if source_width > source_height * 1.15:
            return 1024, 768
        if source_height > source_width * 1.15:
            return 768, 1024
    return 1024, 1024


def _contains_image_phrase(text, phrase):
    return _image_phrase_match(text, phrase) is not None


def _image_phrase_match(text, phrase):
    return re.search(
        rf"(?<![a-z0-9]){re.escape(phrase)}(?![a-z0-9])",
        text,
    )


def _detect_image_category(prompt):
    lowered = prompt.lower()
    best_match = None
    for category, phrases in IMAGE_CATEGORY_PATTERNS:
        for phrase in phrases:
            match = _image_phrase_match(lowered, phrase)
            if match is not None and (
                best_match is None or match.start() < best_match[0]
            ):
                best_match = (match.start(), category)
    if best_match is not None:
        return best_match[1]

    # A short request containing only a likely personal name is normally a
    # portrait request, for example "Deepika Padukone".
    words = re.findall(r"[a-zA-Z][a-zA-Z.'-]*", prompt)
    if 2 <= len(words) <= 4:
        return "portrait"
    return "general"


def _detect_image_style(prompt, category):
    lowered = prompt.lower()
    # If the user mentions conflicting styles, the final stated style wins.
    last_match = None
    for style, phrases in IMAGE_STYLE_PATTERNS:
        for phrase in phrases:
            match = _image_phrase_match(lowered, phrase)
            if match is not None and (
                last_match is None or match.start() > last_match[0]
            ):
                last_match = (match.start(), style)
    if last_match is not None:
        return last_match[1]
    if category == "anime":
        return "anime"
    if category in {"portrait", "vehicle", "product", "architecture", "food"}:
        return "photorealistic"
    return "unspecified"


def _fallback_named_identity(prompt, category):
    lowered = prompt.lower()
    identity_hints = (
        "celebrity", "actor", "actress", "singer", "politician",
        "cricketer", "footballer", "famous person", "public figure",
        "naruto", "deepika padukone",
    )
    if any(_contains_image_phrase(lowered, phrase) for phrase in identity_hints):
        return True
    generic_person_terms = (
        "person", "man", "woman", "boy", "girl", "child", "portrait",
        "headshot", "selfie",
    )
    if any(
        _contains_image_phrase(lowered, phrase)
        for phrase in generic_person_terms
    ):
        return False
    words = re.findall(r"[a-zA-Z][a-zA-Z.'-]*", prompt)
    return category == "portrait" and 2 <= len(words) <= 6


def _extract_prompt_plan_json(content):
    cleaned = re.sub(
        r"<think>[\s\S]*?</think>",
        "",
        str(content or ""),
        flags=re.IGNORECASE,
    ).strip()
    cleaned = re.sub(r"^```(?:json)?\s*|\s*```$", "", cleaned).strip()
    start = cleaned.find("{")
    end = cleaned.rfind("}")
    if start < 0 or end <= start:
        raise ValueError("Prompt planner did not return a JSON object")
    parsed = json.loads(cleaned[start:end + 1])
    if not isinstance(parsed, dict):
        raise ValueError("Prompt planner returned an invalid JSON value")
    return parsed


def _compose_generation_prompt(request, category, style, named_identity):
    category_rule = CATEGORY_PROMPT_REQUIREMENTS.get(
        category,
        CATEGORY_PROMPT_REQUIREMENTS["general"],
    )
    style_rule = STYLE_PROMPT_REQUIREMENTS.get(
        style,
        STYLE_PROMPT_REQUIREMENTS["unspecified"],
    )
    identity_rule = ""
    if named_identity:
        identity_rule = (
            " Represent the requested named person or character faithfully where the "
            "model has reliable visual knowledge. Preserve their recognizable age "
            "range, facial structure, hair, skin tone, clothing, and signature traits; "
            "do not silently replace them with an unrelated generic subject."
        )

    return (
        "Create one polished image from this exact request:\n"
        f"{request}\n\n"
        f"Subject template: {category}. {category_rule}\n"
        f"Visual style: {style}. {style_rule}{identity_rule}\n"
        "Accuracy constraints: preserve every requested subject, count, relationship, "
        "color, position, action, location, and quoted word. Do not add unrelated "
        "objects. If instructions conflict, follow the user's last stated instruction. "
        "Keep anatomy, geometry, perspective, lighting, shadows, and background "
        "internally consistent."
    )[:6000]


def _fallback_generation_plan(prompt):
    normalized = re.sub(r"\s+", " ", prompt).strip()
    category = _detect_image_category(normalized)
    named_identity = _fallback_named_identity(normalized, category)
    if named_identity and category == "general":
        category = "portrait"
    style = _detect_image_style(normalized, category)
    notice = ""
    if named_identity:
        notice = (
            "AI-generated interpretation: the free model may not reproduce an exact "
            "facial or character identity."
        )
    return {
        "request": normalized,
        "category": category,
        "style": style,
        "named_identity": named_identity,
        "notice": notice,
        "enhanced_prompt": _compose_generation_prompt(
            normalized,
            category,
            style,
            named_identity,
        ),
    }


def create_image_generation_plan(prompt):
    """Create one validated plan for templates, style, conflicts, and notices."""
    fallback = _fallback_generation_plan(prompt)
    if os.environ.get("IMAGE_PROMPT_ENHANCEMENT", "true").lower() == "false":
        return fallback

    system_prompt = """
You plan image generation for Chopper. Return only one JSON object:
{
  "normalized_request": "complete rewritten visual request",
  "category": "portrait|vehicle|anime|landscape|product|typography|architecture|food|general",
  "style": "photorealistic|cinematic|anime|illustration|watercolor|oil-painting|3d-render|pixel-art|minimalist|unspecified",
  "named_identity": false
}

Rules:
1. Preserve every requested subject, name, quantity, relationship, action,
   color, position, location, style, and quoted word.
2. Resolve only clear contradictions. Keep the user's last stated instruction
   when two visual instructions conflict.
3. Add only neutral production details needed for composition, camera framing,
   lighting, materials, depth, and clean output.
4. Set named_identity=true when a specific real person, celebrity, or fictional
   character is named and their recognizable identity matters.
5. Never claim that a generated likeness will be exact.
6. Do not include Markdown, commentary, or additional JSON fields.
""".strip()

    try:
        client = get_groq_client()
        response = client.chat.completions.create(
            model=os.environ.get("GROQ_PROMPT_MODEL", "openai/gpt-oss-20b"),
            messages=[
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": fallback["request"]},
            ],
            temperature=0.05,
            max_completion_tokens=700,
        )
        parsed = _extract_prompt_plan_json(response.choices[0].message.content)
        normalized = re.sub(
            r"\s+",
            " ",
            str(parsed.get("normalized_request") or ""),
        ).strip()
        if not normalized or len(normalized) > 4000:
            normalized = fallback["request"]

        category = str(parsed.get("category") or "").strip().lower()
        if category not in SUPPORTED_IMAGE_CATEGORIES:
            category = fallback["category"]
        elif category == "general" and fallback["category"] != "general":
            category = fallback["category"]

        style = str(parsed.get("style") or "").strip().lower()
        if style not in SUPPORTED_IMAGE_STYLES:
            style = fallback["style"]
        elif style == "unspecified" and fallback["style"] != "unspecified":
            style = fallback["style"]

        named_identity = (
            parsed.get("named_identity") is True or fallback["named_identity"]
        )
        notice = ""
        if named_identity:
            notice = (
                "AI-generated interpretation: the free model may not reproduce an "
                "exact facial or character identity."
            )
        return {
            "request": normalized,
            "category": category,
            "style": style,
            "named_identity": named_identity,
            "notice": notice,
            "enhanced_prompt": _compose_generation_prompt(
                normalized,
                category,
                style,
                named_identity,
            ),
        }
    except Exception as error:
        print(f"Image prompt planning failed; using deterministic plan: {error}")
        return fallback


def enhance_image_prompt(prompt, editing=False):
    """Compatibility wrapper retained for existing Chopper integrations."""
    if editing:
        instruction = re.sub(r"\s+", " ", prompt).strip()
        return (
            f"Apply this visual edit: {instruction}. Preserve every visible detail "
            "that the instruction does not explicitly ask to change."
        )
    return create_image_generation_plan(prompt)["enhanced_prompt"]


class CloudflareImageError(RuntimeError):
    def __init__(self, message, status_code=None):
        super().__init__(message)
        self.status_code = status_code


def _cloudflare_endpoint(model):
    account_id, _api_token = get_cloudflare_credentials()
    return (
        "https://api.cloudflare.com/client/v4/accounts/"
        f"{account_id}/ai/run/{model}"
    )


def _cloudflare_error_message(response_bytes, default_message):
    try:
        details = json.loads(response_bytes.decode("utf-8", errors="replace"))
        errors = details.get("errors") or []
        if errors and isinstance(errors[0], dict):
            return str(errors[0].get("message") or default_message)
        return str(details.get("error") or details.get("message") or default_message)
    except Exception:
        return default_message


def _send_cloudflare_request(model, body, content_type, timeout=180):
    _account_id, api_token = get_cloudflare_credentials()
    cloudflare_request = urllib.request.Request(
        _cloudflare_endpoint(model),
        data=body,
        method="POST",
        headers={
            "Authorization": f"Bearer {api_token}",
            "Content-Type": content_type,
        },
    )

    for attempt in range(3):
        try:
            with urllib.request.urlopen(cloudflare_request, timeout=timeout) as response:
                return response.read(), response.headers.get_content_type()
        except urllib.error.HTTPError as error:
            details = error.read()
            message = _cloudflare_error_message(
                details,
                f"Cloudflare image request failed ({error.code}).",
            )
            if error.code in {500, 502, 503, 504} and attempt < 2:
                time.sleep((2, 5)[attempt])
                continue
            if error.code == 429:
                message = (
                    "Cloudflare's free daily AI limit was reached. "
                    "It resets at 00:00 UTC."
                )
            raise CloudflareImageError(message, error.code) from error
        except urllib.error.URLError as error:
            if attempt < 2:
                time.sleep((2, 5)[attempt])
                continue
            raise CloudflareImageError(
                "Could not connect to Cloudflare image generation."
            ) from error

    raise CloudflareImageError("Cloudflare image request failed.")


def _build_multipart_body(fields, source_image=None):
    boundary = f"----ChopperBoundary{secrets.token_hex(16)}"
    chunks = []

    for name, value in fields.items():
        chunks.extend([
            f"--{boundary}\r\n".encode("ascii"),
            (
                f'Content-Disposition: form-data; name="{name}"\r\n\r\n'
            ).encode("ascii"),
            str(value).encode("utf-8"),
            b"\r\n",
        ])

    if source_image is not None:
        chunks.extend([
            f"--{boundary}\r\n".encode("ascii"),
            (
                'Content-Disposition: form-data; name="input_image_0"; '
                'filename="chopper-reference.jpg"\r\n'
            ).encode("ascii"),
            b"Content-Type: image/jpeg\r\n\r\n",
            source_image,
            b"\r\n",
        ])

    chunks.append(f"--{boundary}--\r\n".encode("ascii"))
    return b"".join(chunks), f"multipart/form-data; boundary={boundary}"


def _detect_image_mime(image_bytes):
    if image_bytes.startswith(b"\x89PNG\r\n\x1a\n"):
        return "image/png"
    if image_bytes.startswith(b"\xff\xd8\xff"):
        return "image/jpeg"
    if image_bytes.startswith(b"RIFF") and image_bytes[8:12] == b"WEBP":
        return "image/webp"
    return "image/jpeg"


def _extract_cloudflare_image(response_bytes, content_type):
    if content_type.startswith("image/"):
        if not response_bytes:
            raise CloudflareImageError("Cloudflare returned an empty image.")
        return base64.b64encode(response_bytes).decode("ascii"), content_type

    try:
        response_json = json.loads(response_bytes.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise CloudflareImageError(
            "Cloudflare returned an unsupported image response."
        ) from error

    if response_json.get("success") is False:
        raise CloudflareImageError(
            _cloudflare_error_message(
                response_bytes,
                "Cloudflare did not generate an image.",
            )
        )

    result = response_json.get("result") or response_json
    if isinstance(result, dict):
        image_base64 = str(result.get("image", "")).strip()
    else:
        image_base64 = str(result).strip()

    if image_base64.startswith("data:image/") and "," in image_base64:
        image_base64 = image_base64.split(",", 1)[1]
    if not image_base64:
        raise CloudflareImageError("Cloudflare returned an empty image.")

    try:
        image_bytes = base64.b64decode(image_base64, validate=True)
    except (binascii.Error, ValueError) as error:
        raise CloudflareImageError("Cloudflare returned invalid image data.") from error
    return image_base64, _detect_image_mime(image_bytes)


def _run_flux2(prompt, width, height, source_image=None, guidance=3.5):
    fields = {
        "prompt": prompt,
        "width": width,
        "height": height,
        "guidance": guidance,
    }
    body, content_type = _build_multipart_body(fields, source_image=source_image)
    response_bytes, response_type = _send_cloudflare_request(
        CLOUDFLARE_IMAGE_MODEL,
        body,
        content_type,
    )
    return _extract_cloudflare_image(response_bytes, response_type)


def _run_legacy_cloudflare_model(model, payload):
    response_bytes, content_type = _send_cloudflare_request(
        model,
        json.dumps(payload).encode("utf-8"),
        "application/json",
    )
    return _extract_cloudflare_image(response_bytes, content_type)


def _should_use_legacy_fallback(error):
    return getattr(error, "status_code", None) not in {401, 403, 429}


def _is_flagged_image_error(error):
    message = str(error).lower()
    return "flagged" in message or "safety filter" in message


def _safe_edit_prompt(prompt):
    """Keep editing instructions direct so prompt expansion does not trip filters."""
    instruction = re.sub(r"\s+", " ", prompt).strip()
    return (
        f"Apply this visual edit: {instruction}. "
        "Preserve the foreground subject, composition, and art style unless the "
        "instruction explicitly asks to change them."
    )


def _guidance_for_quality(quality):
    return 4.5 if quality == "high" else 3.5


def _safe_generation_retry_prompt(plan):
    """Build a harmless alternative without repeating rejected prompt wording."""
    category = plan.get("category", "general")
    style = plan.get("style", "unspecified")
    request_text = plan.get("request", "").lower()

    safe_subjects = {
        "portrait": "one original adult person with natural features",
        "vehicle": "one modern sports car with physically coherent details",
        "anime": "one original adult animated traveler",
        "landscape": "a peaceful natural landscape",
        "product": "one fictional unbranded product",
        "typography": "a clean decorative poster with no written words",
        "architecture": "a fictional modern building",
        "food": "a freshly prepared fictional meal",
        "general": "a peaceful original scene with a clear focal subject",
    }
    safe_styles = {
        "photorealistic": "believable photographic style",
        "cinematic": "cinematic lighting and balanced framing",
        "anime": "polished animated illustration style",
        "illustration": "polished digital illustration style",
        "watercolor": "soft watercolor painting style",
        "oil-painting": "traditional oil-painting style",
        "3d-render": "clean three-dimensional render style",
        "pixel-art": "clean pixel-art style",
        "minimalist": "simple minimalist style",
        "unspecified": "polished visual style",
    }
    safe_scenes = (
        ("mountain", "on a scenic mountain overlook"),
        ("beach", "on a quiet beach"),
        ("forest", "in a peaceful forest"),
        ("city", "in a fictional modern city"),
        ("chennai", "in a fictional South Indian city"),
        ("road", "on a quiet road"),
        ("space", "in a fictional outer-space setting"),
        ("studio", "in a simple studio setting"),
    )
    scene = next(
        (description for keyword, description in safe_scenes if keyword in request_text),
        "in a simple scenic setting",
    )
    if "sunset" in request_text:
        scene += " at sunset"
    elif "sunrise" in request_text:
        scene += " at sunrise"
    elif "night" in request_text:
        scene += " at night"

    colors = (
        "red", "orange", "yellow", "green", "blue", "purple", "pink",
        "black", "white", "silver", "gold",
    )
    color = next((item for item in colors if item in request_text), "")
    color_detail = f" Use a {color} accent color." if color else ""

    return (
        f"Create one harmless, original image of {safe_subjects.get(category, safe_subjects['general'])} "
        f"{scene}. Use {safe_styles.get(style, safe_styles['unspecified'])}."
        f"{color_detail} Keep the scene calm and nonviolent. Do not include weapons, "
        "conflict, injuries, real people, existing fictional characters, brands, logos, "
        "watermarks, or written text. Use coherent anatomy, geometry, perspective, and lighting."
    )[:1800]


def _safety_adjusted_plan(plan):
    adjusted = dict(plan)
    safety_notice = (
        "Cloudflare rejected the original request, so Chopper generated a safe, "
        "original alternative."
    )
    current_notice = str(adjusted.get("notice") or "").strip()
    adjusted["notice"] = (
        f"{current_notice} {safety_notice}".strip()
        if current_notice
        else safety_notice
    )
    return adjusted


def generate_image_with_cloudflare(prompt, quality, dimensions):
    plan = create_image_generation_plan(prompt)
    enhanced_prompt = plan["enhanced_prompt"]
    width, height = dimensions
    try:
        image_base64, mime_type = _run_flux2(
            enhanced_prompt,
            width,
            height,
            guidance=_guidance_for_quality(quality),
        )
        return (
            image_base64,
            mime_type,
            enhanced_prompt,
            "flux-2-klein-4b",
            plan,
            1,
        )
    except Exception as primary_error:
        if not _should_use_legacy_fallback(primary_error):
            raise

        attempts = 1
        if _is_flagged_image_error(primary_error):
            retry_prompt = _safe_generation_retry_prompt(plan)
            print(
                "FLUX.2 generation was flagged; retrying once with a harmless "
                "original prompt on FLUX.1."
            )
            try:
                image_base64, mime_type = _run_legacy_cloudflare_model(
                    CLOUDFLARE_GENERATION_FALLBACK_MODEL,
                    {
                        "prompt": retry_prompt,
                        "steps": 8,
                    },
                )
                return (
                    image_base64,
                    mime_type,
                    retry_prompt,
                    "flux-1-schnell",
                    _safety_adjusted_plan(plan),
                    2,
                )
            except Exception as retry_error:
                if _is_flagged_image_error(retry_error):
                    raise CloudflareImageError(
                        "Cloudflare could not generate even the harmless alternative. "
                        "Please try again later."
                    ) from retry_error
                raise

        print(
            "FLUX.2 generation failed; using FLUX.1 fallback: "
            f"{primary_error}"
        )
        image_base64, mime_type = _run_legacy_cloudflare_model(
            CLOUDFLARE_GENERATION_FALLBACK_MODEL,
            {
                "prompt": enhanced_prompt,
                "steps": 8,
            },
        )
        return (
            image_base64,
            mime_type,
            enhanced_prompt,
            "flux-1-schnell",
            plan,
            attempts + 1,
        )


def _prepare_reference_image(source_image_bytes):
    try:
        with Image.open(io.BytesIO(source_image_bytes)) as source_image:
            source_image.load()
            original_size = source_image.size
            if not original_size[0] or not original_size[1]:
                raise ValueError("Invalid image dimensions")
            if original_size[0] > 12000 or original_size[1] > 12000:
                raise ValueError("Source image dimensions are too large")

            prepared = source_image.convert("RGB")
            # Cloudflare requires every FLUX.2 reference image to be strictly
            # smaller than 512x512, so neither edge may equal 512.
            prepared.thumbnail((511, 511), Image.Resampling.LANCZOS)
            output = io.BytesIO()
            prepared.save(output, format="JPEG", quality=95, optimize=True)
            return output.getvalue(), original_size
    except (OSError, ValueError) as error:
        raise ValueError("Unsupported or damaged source image") from error


def edit_image_with_cloudflare(
    prompt,
    source_image_bytes,
    quality,
    requested_size=None,
):
    reference_image, source_size = _prepare_reference_image(source_image_bytes)
    dimensions = _requested_image_dimensions(
        prompt,
        requested_size=requested_size,
        source_size=source_size,
    )
    editing_prompt = _safe_edit_prompt(prompt)
    width, height = dimensions

    try:
        image_base64, mime_type = _run_flux2(
            editing_prompt,
            width,
            height,
            source_image=reference_image,
            guidance=_guidance_for_quality(quality),
        )
        return (
            image_base64,
            mime_type,
            editing_prompt,
            "flux-2-klein-4b",
            dimensions,
        )
    except Exception as flux_error:
        if not _should_use_legacy_fallback(flux_error):
            raise
        print(f"FLUX.2 editing failed; using SDXL fallback: {flux_error}")

        fallback_prompt = _safe_edit_prompt(prompt)
        try:
            image_base64, mime_type = _run_legacy_cloudflare_model(
                CLOUDFLARE_EDIT_FALLBACK_MODEL,
                {
                    "prompt": fallback_prompt,
                    # The SDXL REST runtime requires the encoded image bytes in
                    # the `image` tensor. `image_b64` is documented but is not
                    # mapped to the model tensor by every deployed runtime.
                    "image": list(reference_image),
                    "strength": 0.55,
                },
            )
            return (
                image_base64,
                mime_type,
                fallback_prompt,
                "stable-diffusion-xl-base-1.0",
                dimensions,
            )
        except Exception as fallback_error:
            print(f"SDXL editing fallback failed: {fallback_error}")
            if _is_flagged_image_error(fallback_error):
                raise CloudflareImageError(
                    "Cloudflare rejected this source image during editing. "
                    "Try an image without a visible watermark or use a different source image."
                ) from fallback_error
            raise CloudflareImageError(
                "The primary editor rejected the request and the SDXL fallback failed: "
                f"{fallback_error}"
            ) from fallback_error


@app.route("/generate-image", methods=["POST"])
def generate_image():
    data = request.get_json(silent=True) or {}
    prompt = str(data.get("prompt", "")).strip()

    if not prompt:
        return jsonify({"success": False, "error": "No image prompt provided"}), 400
    if len(prompt) > 2048:
        return jsonify({"success": False, "error": "Image prompt is too long"}), 400

    try:
        quality = _requested_image_quality(prompt, data.get("quality"))
        dimensions = _requested_image_dimensions(prompt, data.get("size"))
        (
            image_base64,
            mime_type,
            _enhanced_prompt,
            model_name,
            generation_plan,
            attempts,
        ) = generate_image_with_cloudflare(prompt, quality, dimensions)
        width, height = dimensions
        return jsonify({
            "success": True,
            "prompt": prompt,
            "mime_type": mime_type,
            "image_base64": image_base64,
            "quality": quality,
            "size": f"{width}x{height}",
            "model": model_name,
            "category": generation_plan["category"],
            "style": generation_plan["style"],
            "identity_limited": generation_plan["named_identity"],
            "notice": generation_plan["notice"],
            "attempts": attempts,
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
        source_image_bytes = base64.b64decode(image_base64, validate=True)
        if not source_image_bytes or len(source_image_bytes) > 6_000_000:
            return jsonify({
                "success": False,
                "error": "Source image is empty or too large",
            }), 400
        quality = _requested_image_quality(prompt, data.get("quality"))
        (
            edited_base64,
            mime_type,
            _enhanced_prompt,
            model_name,
            dimensions,
        ) = edit_image_with_cloudflare(
            prompt,
            source_image_bytes,
            quality,
            requested_size=data.get("size"),
        )
        width, height = dimensions
        return jsonify({
            "success": True,
            "prompt": prompt,
            "mime_type": mime_type,
            "image_base64": edited_base64,
            "quality": quality,
            "size": f"{width}x{height}",
            "model": model_name,
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
  "topic": "the precise subject the user wants",
  "required_concept_groups": [
    ["words", "that", "must", "appear", "together"],
    ["alternative", "complete", "name"]
  ],
  "search_queries": [one to five focused search-engine queries],
  "result_count": integer from 1 to 10
}}

For a request about the newest events, use news mode and a strict, reasonable
freshness window. Put the current year or an appropriate date range into the
queries when it improves precision. For stable information, use web mode and
set freshness_days to null. Preserve the user's intended country or region.
Each required concept group must identify the complete requested subject.
Every word in one group must appear in a result for that result to qualify.
Never create a one-word group from a generic word such as India, Parliament,
news, government, latest, or election. For Indian Parliament, suitable groups
include ["Indian", "Parliament"], ["Parliament", "India"],
["Lok", "Sabha"], and ["Rajya", "Sabha"]. Do not include unrelated foreign
institutions.

User request: {query}
""".strip()

    search_plan_schema = {
        "name": "chopper_search_plan",
        "strict": True,
        "schema": {
            "type": "object",
            "properties": {
                "search_mode": {
                    "type": "string",
                    "enum": ["news", "web"],
                },
                "needs_freshness": {"type": "boolean"},
                "freshness_days": {
                    "anyOf": [
                        {"type": "integer"},
                        {"type": "null"},
                    ]
                },
                "topic": {"type": "string"},
                "required_concept_groups": {
                    "type": "array",
                    "items": {
                        "type": "array",
                        "items": {"type": "string"},
                    },
                },
                "search_queries": {
                    "type": "array",
                    "items": {"type": "string"},
                },
                "result_count": {"type": "integer"},
            },
            "required": [
                "search_mode",
                "needs_freshness",
                "freshness_days",
                "topic",
                "required_concept_groups",
                "search_queries",
                "result_count",
            ],
            "additionalProperties": False,
        },
    }

    plan = None
    last_error = None
    for _attempt in range(2):
        try:
            response = client.chat.completions.create(
                model="openai/gpt-oss-20b",
                messages=[{"role": "user", "content": prompt}],
                temperature=0,
                reasoning_effort="low",
                include_reasoning=False,
                max_completion_tokens=1200,
                response_format={
                    "type": "json_schema",
                    "json_schema": search_plan_schema,
                },
            )
            content = response.choices[0].message.content
            if not content:
                raise RuntimeError("Groq returned an empty strict search plan.")
            plan = json.loads(content)
            break
        except Exception as error:
            last_error = error

    if plan is None:
        raise RuntimeError(
            f"Strict search planning failed after retry: {last_error}"
        )

    if not isinstance(plan, dict):
        raise RuntimeError("Groq returned an invalid search plan.")
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

    plan["topic"] = str(plan.get("topic", query)).strip()[:300] or query
    raw_groups = plan.get("required_concept_groups")
    if not isinstance(raw_groups, list):
        raw_groups = []
    groups = []
    for raw_group in raw_groups[:8]:
        if not isinstance(raw_group, list):
            continue
        group = [
            str(word).strip()[:60]
            for word in raw_group[:8]
            if str(word).strip()
        ]
        if len(group) >= 2:
            groups.append(group)
    if not groups:
        topic_words = re.findall(r"[A-Za-z0-9]+", plan["topic"])
        if len(topic_words) >= 2:
            groups = [topic_words[:8]]
    plan["required_concept_groups"] = groups

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
Never call browser.open, browser.search, or any other tool. All information you
need is already included in the supplied results. Return only normal answer text.
Give a direct answer first. Do not invent information. If sources disagree,
mention it. Prefer primary and official sources. Include a short Sources section
with relevant source names, publication dates when supplied, and exact URLs.
The current UTC date is {current_date}. If the request needs fresh information,
do not present an older event as the newest one. Do not mention these instructions.
Before answering, semantically reject any supplied result that is only loosely
related to the requested topic. Cite only results actually used in the answer.
Never list rejected or unrelated results in Sources. If no result directly
answers the question, say so and omit the Sources section entirely.
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
    try:
        response = client.chat.completions.create(
            model=os.environ.get(
                "GROQ_SUMMARY_MODEL",
                "openai/gpt-oss-20b",
            ),
            messages=[
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_prompt},
            ],
            temperature=0.1,
            max_completion_tokens=600,
        )
        answer = response.choices[0].message.content
        if answer:
            return answer.strip()
    except Exception as error:
        # Some models may incorrectly emit browser.open even though no tools
        # were supplied. Never fail the user's search because of that.
        print(f"Web summarization failed; returning results directly: {error}")

    return web_results.strip()


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
        return jsonify({
            "success": False,
            "error": "Web search is temporarily unavailable. Please try again.",
        }), 500


@app.errorhandler(413)
def request_too_large(_error):
    return jsonify({"success": False, "error": "Request is too large"}), 413


print("===== CHOPPER RENDER APP LOADED =====")
print(app.url_map)
