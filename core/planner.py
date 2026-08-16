def create_plan(route, tool, user_message):
    """
    Decide what Chopper intends to do.
    """

    plan = {
        "route": route,
        "tool": tool,
        "steps": []
    }

    if tool == "CALCULATOR":
        plan["steps"] = [
            "Parse expression",
            "Calculate result",
            "Return answer"
        ]

    elif tool == "EQUATION":
        plan["steps"] = [
            "Parse equation",
            "Solve equation",
            "Return steps"
        ]

    elif tool == "FILE":
        plan["steps"] = [
            "Open file",
            "Read contents",
            "Return contents"
        ]

    elif tool == "WEB":
        plan["steps"] = [
            "Search Internet",
            "Collect results",
            "Return results"
        ]

    elif tool == "AI":
        plan["steps"] = [
            "Build prompt",
            "Select model",
            "Generate response"
        ]

    else:
        plan["steps"] = [
            "Execute local tool"
        ]

    return plan