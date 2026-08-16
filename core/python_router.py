import re


def route(user_message):
    message = user_message.lower().strip()

    # -------------------------
    # Manual model selection
    # -------------------------
    if message == "/smart" or message.startswith("/smart "):
        return "SMART", 100

    if message == "/deep" or message.startswith("/deep "):
        return "VERY_DEEP", 100

    # -------------------------
    # Greetings
    # -------------------------
    greetings = {
        "hi",
        "hello",
        "hey",
        "good morning",
        "good afternoon",
        "good evening",
    }

    if message in greetings:
        return "GREETING", 100

    # -------------------------
    # Personal memory
    # -------------------------
    memory_keywords = [
        "my name",
        "favorite",
        "favourite",
        "remember",
        "who am i",
        "my friend",
    ]
    # -------------------------
    # File operations
    # -------------------------
    file_keywords = [
        "read",
        "open",
        "show",
        "display",
        "summarize",
    ]

    file_extensions = [
        ".txt",
        ".md",
        ".py",
        ".java",
        ".json",
        ".csv",
    ]

    if (
        any(word in message for word in file_keywords)
        and any(ext in message for ext in file_extensions)
    ):
        return "FILE", 100

    if any(keyword in message for keyword in memory_keywords):
        return "MEMORY", 100
    # -------------------------
    # Linear equations
    # -------------------------
    if "x" in message and "=" in message:
        return "EQUATION", 100
    # -------------------------
    # Calculator
    # -------------------------
    if re.fullmatch(r"[0-9+\-*/().% ]+", message):
        return "CALCULATOR", 100

    # -------------------------
    # Comparison
    # Must come before coding
    # -------------------------
    comparison_keywords = [
        "compare",
        "difference between",
        "differences between",
        " vs ",
        " versus ",
    ]

    if any(keyword in message for keyword in comparison_keywords):
        return "SMART", 100

    # -------------------------
    # Mathematics
    # -------------------------
    math_keywords = [
        "solve",
        "prove",
        "equation",
        "integral",
        "derivative",
        "differentiate",
        "factorize",
        "simplify",
    ]

    if any(keyword in message for keyword in math_keywords):
        return "SMART", 100

    # -------------------------
    # Coding
    # Use action words, not only language names
    # -------------------------
    coding_keywords = [
        "write a program",
        "write code",
        "create a program",
        "create code",
        "generate code",
        "python program",
        "java program",
        "c++ program",
        "c# program",
        "javascript program",
        "html code",
        "css code",
        "sql query",
        "script",
        "function",
        "class",
        "debug",
        "bug",
        "error",
        "algorithm",
        "compile",
    ]

    if any(keyword in message for keyword in coding_keywords):
        return "CODER", 95

    # -------------------------
    # Smart technical reasoning
    # -------------------------
    smart_keywords = [
        "architecture",
        "design",
        "research",
        "machine learning",
        "deep learning",
        "artificial intelligence",
        "project plan",
        "technical explanation",
        "workflow",
        "diagram",
    ]

    if any(keyword in message for keyword in smart_keywords):
        return "SMART", 90

    # -------------------------
    # Default fast model
    # -------------------------
    return "UNKNOWN", 0