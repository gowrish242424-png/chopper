def choose_style(route, user_message):
    """
    Choose the response style based on the user's request.
    """

    message = user_message.lower().strip()

    # -------------------------
    # Coding
    # -------------------------
    if route == "CODER":
        return "CODE"

    # -------------------------
    # Architecture / Design
    # -------------------------
    architecture_keywords = [
        "architecture",
        "design",
        "workflow",
        "diagram",
        "system design",
        "block diagram",
    ]

    if any(keyword in message for keyword in architecture_keywords):
        return "ARCHITECTURE"

    # -------------------------
    # Comparison
    # -------------------------
    comparison_keywords = [
        "compare",
        "comparison",
        "difference",
        "differences",
        " vs ",
        " versus ",
    ]

    if any(keyword in message for keyword in comparison_keywords):
        return "COMPARE"

    # -------------------------
    # Mathematics
    # -------------------------
    math_keywords = [
        "solve",
        "calculate",
        "prove",
        "equation",
        "integral",
        "derivative",
        "differentiate",
        "factorize",
        "simplify",
        "evaluate",
    ]

    if any(keyword in message for keyword in math_keywords):
        return "MATH"

    # -------------------------
    # Default
    # -------------------------
    return "NORMAL"