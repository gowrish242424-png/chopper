from concurrent.futures import (
    ThreadPoolExecutor,
    as_completed,
)

from memory_system.memory import (
    search_memories,
    get_all_memories,
)

from tools.web_tool import search_web

from core.query_understander import (
    understand_query,
)


def collect_memory_context(
    query,
    query_info=None,
):
    """
    Retrieve personal memories.

    General questions about the user can
    retrieve all stored personal memories.

    Otherwise retrieve only relevant memory.
    """

    try:

        if query_info is not None:

            subject = str(
                query_info.get(
                    "subject",
                    "",
                )
            ).lower()

            references_user = bool(
                query_info.get(
                    "references_user",
                    False,
                )
            )

            intent = str(
                query_info.get(
                    "intent",
                    "",
                )
            ).lower()

            general_user_request = (
                references_user
                and (
                    subject == "user"
                    or "about the user"
                    in intent
                    or "about themselves"
                    in intent
                    or "general information about the user"
                    in intent
                    or "user wants to know"
                    in intent
                )
            )

            if general_user_request:

                results = (
                    get_all_memories()
                )

            else:

                results = (
                    search_memories(
                        query
                    )
                )

        else:

            results = search_memories(
                query
            )

        if not results:
            return ""

        formatted = []

        for item in results:

            if isinstance(
                item,
                dict,
            ):

                key = item.get(
                    "key",
                    "",
                )

                value = item.get(
                    "value",
                    "",
                )

            else:

                key, value = item

            # Internal configuration is not
            # personal memory.
            if key == "response_mode":
                continue

            formatted.append(
                f"{key}: {value}"
            )

        return "\n".join(
            formatted
        )

    except Exception as error:

        print(
            f"Memory search error: "
            f"{error}"
        )

        return ""


def collect_web_context(
    user_message,
):
    """
    Retrieve live/current information.

    IMPORTANT:
    Use the ORIGINAL user message here.

    This preserves:
    - locations
    - words such as today/current/right now
    - exact freshness requirements
    - time/date/weather phrasing
    """

    try:

        return search_web(
            user_message,
            max_results=5,
        )

    except Exception as error:

        print(
            f"Web search error: "
            f"{error}"
        )

        return ""


def collect_parallel_context(
    user_message,
    status_callback=None,
):
    """
    Understand the request and retrieve
    memory/web information when needed.
    """

    # =================================
    # 1. Understand request
    # =================================

    query_info = understand_query(
        user_message
    )

    normalized_query = (
        query_info.get(
            "normalized_query",
            user_message,
        )
        or user_message
    )

    needs_memory = bool(
        query_info.get(
            "needs_memory",
            False,
        )
    )

    needs_web = bool(
        query_info.get(
            "needs_web",
            False,
        )
    )

    if status_callback is not None:

        try:

            status_callback(
                "understanding"
            )

        except Exception:
            pass

    context = {
        "query_info": query_info,
        "memory": "",
        "web": "",
    }

    tasks = {}

    # =================================
    # 2. Start retrieval workers
    # =================================

    with ThreadPoolExecutor(
        max_workers=2
    ) as executor:

        # --------------------------------
        # Personal memory
        # --------------------------------

        if needs_memory:

            memory_future = (
                executor.submit(
                    collect_memory_context,
                    normalized_query,
                    query_info,
                )
            )

            tasks[
                memory_future
            ] = "memory"

        # --------------------------------
        # Web/live information
        # --------------------------------

        if needs_web:

            # IMPORTANT:
            # Send ORIGINAL message to
            # web_tool.py, not normalized_query.
            #
            # Example:
            #
            # "What is the current time in
            # New Delhi, India?"
            #
            # must remain exactly meaningful
            # enough for live routing/location.
            print(
                f"\n🌐 DEBUG: Starting web search for: "
                f"{user_message}"
            )
            web_future = (
                executor.submit(
                    collect_web_context,
                    user_message,
                )
            )

            tasks[
                web_future
            ] = "web"

        # =================================
        # 3. Collect completed results
        # =================================

        for future in as_completed(
            tasks
        ):

            source = tasks[
                future
            ]

            try:

                context[
                    source
                ] = future.result()

            except Exception as error:

                print(
                    f"{source.title()} "
                    f"worker error: "
                    f"{error}"
                )

                context[
                    source
                ] = ""

            if status_callback is not None:

                try:

                    status_callback(
                        source
                    )

                except Exception:
                    pass

    # =================================
    # 4. Debug display
    # =================================

    print(
        "\n===== PARALLEL CONTEXT ====="
    )

    print(
        context
    )

    print(
        "============================"
    )

    return context