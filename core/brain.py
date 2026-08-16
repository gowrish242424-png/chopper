import ast
import operator
import time
from core.adaptive_progress import (
    estimate_difficulty,
    record_generation_time,
)
from ollama import chat

from core.ai_router import choose_model
from core.config import (
    CONVERSATION_MESSAGES_SENT,
    KEEP_ALIVE,
    MAX_CONVERSATION,
    NUM_CONTEXT,
    NUM_PREDICT_CODER,
    NUM_PREDICT_DEEP,
    TEMPERATURE,
)
from core.evidence_extractor import (
    extract_evidence,
    format_evidence,
)
from core.performance import PerformanceTimer
from core.python_router import route
from core.style_router import choose_style
from core.tool_router import choose_tool
from memory_system.extractor import extract_memories
from memory_system.memory import save_memory, search_memories
from tools.command_handler import handle_command
from tools.equation_tool import solve_equation
from tools.tool_manager import run_tool
from core.planner import create_plan
from core.planner_view import show_plan
from core.agent import execute_plan
from core.system_prompt import build_system_prompt
from core.mode_manager import get_current_mode
from core.parallel_context import collect_parallel_context

# Current conversation history
conversation = []


# Safe calculator operators
OPERATORS = {
    ast.Add: operator.add,
    ast.Sub: operator.sub,
    ast.Mult: operator.mul,
    ast.Div: operator.truediv,
    ast.FloorDiv: operator.floordiv,
    ast.Mod: operator.mod,
    ast.Pow: operator.pow,
    ast.USub: operator.neg,
    ast.UAdd: operator.pos,
}


def calculate(expression):
    """Safely evaluate a basic mathematical expression."""

    def evaluate(node):
        if isinstance(node, ast.Constant):
            if isinstance(node.value, (int, float)):
                return node.value

            raise ValueError("Only numbers are allowed.")

        if isinstance(node, ast.BinOp):
            operation = OPERATORS.get(type(node.op))

            if operation is None:
                raise ValueError("Unsupported operator.")

            return operation(
                evaluate(node.left),
                evaluate(node.right),
            )

        if isinstance(node, ast.UnaryOp):
            operation = OPERATORS.get(type(node.op))

            if operation is None:
                raise ValueError("Unsupported operator.")

            return operation(evaluate(node.operand))

        raise ValueError("Invalid expression.")

    tree = ast.parse(expression, mode="eval")
    return evaluate(tree.body)


def build_memory_text(user_message):
    """Load memories related to the current message."""

    relevant_memories = search_memories(user_message)

    if not relevant_memories:
        return "No relevant memories."

    return "\n".join(
        f"{key}: {value}"
        for key, value in relevant_memories
    )


def save_extracted_memories(user_message):
    """Extract and save personal information."""

    extracted_memories = extract_memories(user_message)

    for memory in extracted_memories:
        key = memory.get("key")
        value = memory.get("value")

        if not key or not value:
            continue

        save_memory(key, value)
        print(f"\n🧠 Memory Saved: {key} = {value}")


def save_conversation(user_message, assistant_reply):
    """Store and limit the conversation history."""

    conversation.append(
        {
            "role": "user",
            "content": user_message,
        }
    )

    conversation.append(
        {
            "role": "assistant",
            "content": assistant_reply,
        }
    )

    if len(conversation) > MAX_CONVERSATION:
        conversation[:] = conversation[-MAX_CONVERSATION:]


def get_num_predict(selected_route, user_message):
    """
    Choose the maximum response length based on
    the current Chopper mode and request type.
    """

    current_mode = get_current_mode()
    message = user_message.lower()

    # Quick mode
    if current_mode == "quick":
        return 250

    # Balanced mode
    if current_mode == "balanced":
        return 700

    # Research mode
    if current_mode == "research":
        return 2200

    # Builder mode
    if current_mode == "builder":
        if (
            "complete" in message
            or "full" in message
            or "project" in message
            or "system" in message
        ):
            return 3000

        return 1800

    # Solve mode
    if current_mode == "solve":
        return 350

    # Teacher mode
    if current_mode == "teacher":
        return 1800

    # Auto mode
    if selected_route == "CODER":
        if (
            "complete" in message
            or "full" in message
            or "project" in message
            or "system" in message
        ):
            return NUM_PREDICT_CODER

        return 700

    if selected_route in {
        "SMART",
        "VERY_DEEP",
    }:
        return NUM_PREDICT_DEEP

    return 500

def build_style_rules(style):
    """Return prompt rules for the selected response style."""

    style_rules = {
        "CODE": """
Response style: CODE
- Output the requested code immediately.
- Do not write introductions such as "Certainly", "Here is", or "Below is".
- Give complete working code.
- After the code, give at most one short improvement.
- Do not explain every line unless the user asks.
""",
        "ARCHITECTURE": """
Response style: ARCHITECTURE
- Show a small ASCII diagram first.
- Use at most 5 main components unless more are necessary.
- Explain each component in one short line.
- Explain the data flow briefly.
- Give at most one useful improvement.
- Do not write implementation code unless requested.
- Finish every sentence and bullet completely.
""",
        "COMPARE": """
Response style: COMPARE
- Use a compact Markdown comparison table.
- Highlight only the most important differences.
- Give one short recommendation when useful.
- Do not add a long introduction or conclusion.
""",
        "MATH": """
Response style: MATH

Always use exactly this format:

Answer:
<final answer>

Steps:
1. ...
2. ...
3. ...

Never start with:
"To solve..."
"The equation is..."
"Let's solve..."

Always put the final answer before the steps.
""",
        "NORMAL": """
Response style: NORMAL
- Answer directly and naturally.
- Keep simple answers within 6 lines.
- Give one useful suggestion only when it adds value.
""",
    }

    return style_rules.get(
        style,
        style_rules["NORMAL"],
    )


def ask_ai(
    user_message,
    selected_route,
    progress_callback=None,
    response_callback=None,
    status_callback=None,
    model_callback=None,
):
    """Choose a model and stream its response."""

    timer = PerformanceTimer()

    # --------------------------------
    # Step 1: Understand + collect context
    # --------------------------------
    timer.mark("memory_start")
    total_start = time.perf_counter()

    evidence_time = 0
    generation_time = 0
    first_token_time = None
    parallel_context = collect_parallel_context(
        user_message,
        status_callback=status_callback,
    )

    print("\n===== PARALLEL CONTEXT =====")
    print(parallel_context)
    print("============================")

    query_info = parallel_context["query_info"]
    memory_text = parallel_context["memory"]
    web_context = parallel_context["web"]
    evidence_text = ""
    final_web_context = ""

    current_mode = get_current_mode()

    if web_context:

        # Deep evidence extraction only in Research mode
        if current_mode == "research":
            stage_start = time.perf_counter()

            evidence = extract_evidence(
                user_message,
                web_context,
            )

            evidence_text = format_evidence(
                evidence
            )

            if evidence_text:
                final_web_context = evidence_text
            else:
                final_web_context = web_context

            evidence_time = (
                time.perf_counter()
                - stage_start
            )

        else:
            # Auto / Quick / Balanced:
            # use already-ranked web results directly
            final_web_context = web_context

    
    
    # --------------------------------
    # Step 2: Choose model
    # --------------------------------
    model, mode = choose_model(
        user_message,
        query_info,
    )
    difficulty = estimate_difficulty(
        user_message,
        mode,
    )
    if model_callback is not None:
        try:
            model_callback(
                model,
                difficulty,
            )
        except Exception:
            pass

    print(f"\n⚡ Mode : {mode}")
    print(f"🧠 Model : {model}")

    # --------------------------------
    # Step 3: Choose response style
    # --------------------------------
    style = choose_style(
        selected_route,
        user_message,
    )

    style_rules = build_style_rules(style)

    timer.mark("memory_end")

    # --------------------------------
    # Step 4: Build system prompt
    # --------------------------------
    mode_prompt = build_system_prompt()

    system_prompt = f"""
{mode_prompt}

You are Chopper, the USER's private personal AI assistant.

IMPORTANT IDENTITY RULES:
- Chopper is the ASSISTANT.
- The human sending the message is the USER.
- Personal memories below always belong to the USER.
- Never treat USER memories as Chopper's memories.
- If memory contains a name, that name belongs to the USER.
- When the detected subject is USER, talk about the USER.
- Address the USER using "you", "your", or their name.
- Never call the USER Chopper.
- Chopper refers to itself as "I" or "Chopper".
- If the detected subject is USER, answer only about the USER unless Chopper itself is specifically asked about.

General response rules:
- Give the answer directly.
- Return only the final answer.
- Never reveal internal reasoning, hidden thoughts, or private planning.
- Do not output <think> or </think> tags.
- Follow the currently selected mode.
- Use supplied personal memories when relevant.
- Use supplied web information when relevant.
- Do not invent personal information.
- Do not invent sources.
- Do not claim web verification when no web information was supplied.

Response style rules:
{style_rules}

Original user message:
{user_message}

Understood user request:
{query_info.get("normalized_query", user_message)}

Detected intent:
{query_info.get("intent", "general")}

Detected subject:
{query_info.get("subject", "general")}

References USER:
{query_info.get("references_user", False)}

References ASSISTANT:
{query_info.get("references_assistant", False)}

Personal memories ABOUT THE USER:
{memory_text or "No relevant personal memories found."}

WEB GROUNDING RULES:

The WEB RESEARCH EVIDENCE below is authoritative for this request.

If WEB RESEARCH EVIDENCE is available:

- Answer ONLY using facts contained in the supplied evidence.
- Do not use your pretrained knowledge to add factual claims.
- Do not invent titles, dates, names, numbers, events, or sources.
- Never create placeholder values such as "[Current Date]" or "[Current Source]".
- Never create a news story that does not appear in the evidence.
- Preserve publication dates exactly as provided.
- Preserve source names accurately.
- Preserve URLs exactly as provided.
- If the user requests 3 results but only 2 valid results exist, return only 2.
- If the evidence contains no valid answer, say:
  "I couldn't verify this from the current web results."
- For current or live questions, prefer the supplied live value over your internal knowledge.
- Your internal knowledge must NEVER override newer supplied web evidence.

WEB RESEARCH EVIDENCE:
{final_web_context or "NO WEB EVIDENCE AVAILABLE"}
IMPORTANT WEB RESPONSE RULES:

If web information is available:

- Treat the supplied web/live information as the source of truth.
- Answer the user's question directly from that information.
- Do NOT replace supplied live values with model memory.
- Do NOT guess when verified web/live information is available.
- For current time, date, weather, price, office-holder, or other live questions,
  use the exact current value supplied in the web context.
- If the supplied context says VERIFIED LIVE TIME, VERIFIED LIVE DATE,
  or VERIFIED LIVE WEATHER, copy those values accurately.
- If reliable web evidence conflicts with your internal knowledge,
  trust the supplied web evidence.
- Never respond by only recommending a website or link.
- Never say "you can find more information at".
- Never invent a source, date, person, number, or event.
- If the supplied evidence is insufficient, clearly say that reliable
  current information could not be verified.

For news requests:

- Respect the user's requested time period strictly.
- If the user asks for "today", do not include older stories.
- If the user asks for the last 24 hours, do not include older stories.
- Use only the supplied ranked news results.
- Ignore category pages that contain no specific news event.
- Ignore unrelated results even if they contain one matching keyword.
- Give only distinct, directly relevant developments.
- If 3 relevant developments are available, give 3.
- If fewer are available, give only the available verified items.
- Include publication date and source when available.
- Put the source name and URL after each development.
- Never invent missing news items just to reach a requested count.
"""
    # --------------------------------
    # Step 5: Build Chopper messages
    # --------------------------------
    messages = [
        {
            "role": "system",
            "content": system_prompt,
        }
    ]

    messages.extend(
        conversation[-CONVERSATION_MESSAGES_SENT:]
    )

    messages.append(
        {
            "role": "user",
            "content": user_message,
        }
    )

    # --------------------------------
    # Step 6: Response length
    # --------------------------------
    num_predict = get_num_predict(
        selected_route,
        user_message,
    )

    # --------------------------------
    # Step 7: Ollama arguments
    # --------------------------------
    chat_arguments = {
        "model": model,
        "messages": messages,

        # Web Research uses one non-streaming request.
        # Other modes continue streaming normally.
        "stream": (
            False
            if mode == "Web Research"
            else True
        ),

        "keep_alive": KEEP_ALIVE,

        "options": {
            "num_ctx": NUM_CONTEXT,
            "num_predict": num_predict,
            "temperature": TEMPERATURE,
        },
    }

    # Disable Qwen3 separate thinking output
    if model.startswith("qwen3:"):
        chat_arguments["think"] = False

    # --------------------------------
    # Step 8: Generate response
    # --------------------------------
    timer.mark("ai_start")

    assistant_reply = ""

    # Safe defaults for performance report
    generation_time = 0
    first_token_time = None

    try:
        generation_start = time.perf_counter()

        # ==========================================
        # WEB RESEARCH - NON STREAMING
        # ==========================================
        if mode == "Web Research":

            response = chat(
                **chat_arguments
            )

            assistant_reply = (
                response["message"]["content"]
                .strip()
            )

            generation_time = (
                time.perf_counter()
                - generation_start
            )
            

            # Non-streaming returns the complete
            # response at once.
            first_token_time = generation_time

            if assistant_reply:

                if progress_callback is not None:
                    try:
                        progress_callback(
                            len(assistant_reply),
                            len(assistant_reply),
                        )
                    except Exception:
                        pass

                if response_callback is not None:
                    try:
                        response_callback(
                            assistant_reply
                        )
                    except Exception:
                        pass

                print(
                    f"\nChopper: {assistant_reply}"
                )

        # ==========================================
        # OTHER MODES - STREAMING
        # ==========================================
        else:

            response_stream = chat(
                **chat_arguments
            )

            waiting_for_final_answer = (
                model.startswith("qwen3:")
            )

            pending_content = ""

            print(
                "\nChopper: ",
                end="",
                flush=True,
            )

            for chunk in response_stream:

                message = chunk["message"]

                content = message.get(
                    "content",
                    "",
                )

                if not content:
                    continue

                # Measure first visible token
                if first_token_time is None:
                    first_token_time = (
                        time.perf_counter()
                        - generation_start
                    )

                # --------------------------------
                # Qwen3 reasoning protection
                # --------------------------------
                if waiting_for_final_answer:

                    pending_content += content

                    closing_tag = "</think>"

                    if closing_tag in pending_content:

                        content = pending_content.split(
                            closing_tag,
                            1,
                        )[1]

                        pending_content = ""
                        waiting_for_final_answer = False

                        if not content:
                            continue

                    elif "<think>" not in pending_content:

                        content = pending_content
                        pending_content = ""
                        waiting_for_final_answer = False

                    else:
                        continue

                # --------------------------------
                # Store final answer
                # --------------------------------
                assistant_reply += content

                # GUI progress
                if progress_callback is not None:
                    try:
                        progress_callback(
                            len(content),
                            len(assistant_reply),
                        )
                    except Exception:
                        pass

                # GUI streaming
                if response_callback is not None:
                    try:
                        response_callback(
                            content
                        )
                    except Exception:
                        pass

                # Terminal streaming
                print(
                    content,
                    end="",
                    flush=True,
                )

            # --------------------------------
            # Qwen3 fallback
            # --------------------------------
            if (
                waiting_for_final_answer
                and pending_content
            ):

                cleaned_content = (
                    pending_content
                    .replace("<think>", "")
                    .replace("</think>", "")
                    .strip()
                )

                if cleaned_content:

                    assistant_reply += (
                        cleaned_content
                    )

                    if progress_callback is not None:
                        try:
                            progress_callback(
                                len(cleaned_content),
                                len(assistant_reply),
                            )
                        except Exception:
                            pass

                    if response_callback is not None:
                        try:
                            response_callback(
                                cleaned_content
                            )
                        except Exception:
                            pass

                    print(
                        cleaned_content,
                        end="",
                        flush=True,
                    )

            generation_time = (
                time.perf_counter()
                - generation_start
            )

        timer.mark("ai_end")
        print()

    except Exception as error:

        generation_time = (
            time.perf_counter()
            - generation_start
        )

        timer.mark("ai_end")

        print(
            "\nAI generation error:",
            error,
        )

        assistant_reply = ""

    # --------------------------------
    # Step 9: Empty-response retry
    # --------------------------------
    if not assistant_reply.strip():

        print(
            "\n⚠️ Empty response. "
            "Retrying once..."
        )

        retry_start = time.perf_counter()

        try:
            retry_arguments = (
                chat_arguments.copy()
            )

            # Retry once without streaming
            retry_arguments["stream"] = False

            retry_response = chat(
                **retry_arguments
            )

            assistant_reply = (
                retry_response["message"]["content"]
                .strip()
            )

            # Include retry in generation timing
            generation_time += (
                time.perf_counter()
                - retry_start
            )

            if assistant_reply:

                if response_callback is not None:
                    try:
                        response_callback(
                            assistant_reply
                        )
                    except Exception:
                        pass

                print(
                    f"\nChopper: "
                    f"{assistant_reply}"
                )

        except Exception as retry_error:

            generation_time += (
                time.perf_counter()
                - retry_start
            )

            print(
                f"\nRetry error: "
                f"{retry_error}"
            )

    # --------------------------------
    # Final fallback
    # --------------------------------
    if not assistant_reply.strip():

        assistant_reply = (
            "I couldn't generate a final answer. "
            "Please try again."
        )

        if response_callback is not None:
            try:
                response_callback(
                    assistant_reply
                )
            except Exception:
                pass

        print(
            f"\nChopper: {assistant_reply}"
        )

    # --------------------------------
    # Step 10: Save conversation
    # --------------------------------
    # IMPORTANT:
    # This must be OUTSIDE the fallback block.
    save_conversation(
        user_message,
        assistant_reply,
    )

    # --------------------------------
    # Step 11: Existing performance
    # --------------------------------
    timer.report(
        mode,
        model,
    )

    # --------------------------------
    # Step 12: Detailed performance
    # --------------------------------
    total_time = (
        time.perf_counter()
        - total_start
    )
    record_generation_time(
        model,
        difficulty,
        total_time,
    )
    print(
        "\n========================================"
    )
    print(
        "⏱ Chopper Detailed Performance"
    )
    print(
        "========================================"
    )

    print(
        f"Evidence Extraction : "
        f"{evidence_time:.3f}s"
    )

    if first_token_time is not None:
        print(
            f"First Token         : "
            f"{first_token_time:.3f}s"
        )
    else:
        print(
            "First Token         : N/A"
        )

    print(
        f"AI Generation       : "
        f"{generation_time:.3f}s"
    )

    print(
        f"Total               : "
        f"{total_time:.3f}s"
    )

    print(
        "========================================"
    )

    return assistant_reply


def ask_chopper(
    user_message,
    progress_callback=None,
    response_callback=None,
    status_callback=None,
    model_callback=None,
):
    user_message = user_message.strip()

    # Remove copied prefixes:
    # "You: Read main.py"
    # "You: You: Read main.py"
    while user_message.lower().startswith(
        "you:"
    ):
        user_message = (
            user_message[4:].strip()
        )


    if not user_message:
        reply = "Please enter a message."

        print(
            f"\nChopper: {reply}"
        )

        return reply

    # --------------------------------
    # Step 1: Select route
    # --------------------------------
    selected_route, confidence = route(
        user_message
    )

    # --------------------------------
    # Step 2: Select tool
    # --------------------------------
    selected_tool = choose_tool(
        selected_route,
        user_message,
    )

    # --------------------------------
    # Step 3: Create execution plan
    # --------------------------------
    plan = create_plan(
        selected_route,
        selected_tool,
        user_message,
    )

    # --------------------------------
    # Step 4: Display/execute plan
    # --------------------------------
    show_plan(plan)
    execute_plan(plan)

    # --------------------------------
    # Step 5: Local tools
    # --------------------------------
    # WEB is handled by
    # collect_parallel_context().
    if selected_tool != "WEB":

        reply = run_tool(
            selected_tool,
            user_message,
        )

        if reply is not None:

            print(
                f"\nChopper: {reply}"
            )

            # Send local-tool answer to GUI
            if response_callback is not None:
                try:
                    response_callback(
                        reply
                    )
                except Exception as error:
                    print(
                        f"GUI callback error: {error}"
                    )

            return reply

    # --------------------------------
    # Step 6: Save personal facts
    # --------------------------------
    save_extracted_memories(
        user_message
    )

    # --------------------------------
    # Step 7: Web support
    # --------------------------------
    if selected_tool == "WEB":
        selected_route = "UNKNOWN"

    # --------------------------------
    # Step 8: Ask AI
    # --------------------------------
    return ask_ai(
        user_message,
        selected_route,
        progress_callback=progress_callback,
        response_callback=response_callback,
        status_callback=status_callback,
        model_callback=model_callback,
    )