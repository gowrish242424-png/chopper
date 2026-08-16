def choose_tool(route, user_message):
    """
    Select which internal Chopper tool
    should handle the request.
    """

    message = (
        user_message
        .lower()
        .strip()
    )

    # =================================
    # 1. Mode Manager
    # =================================

    mode_commands = {
        "mode",
        "quick",
        "balanced",
        "research",
        "builder",
        "solve",
        "teacher",
        "auto",
        "cancel",
    }

    if message in mode_commands:
        return "MODE"

    # =================================
    # 2. Direct routed tools
    # =================================

    if route == "FILE":
        return "FILE"

    if route == "GREETING":
        return "GREETING"

    if route == "MEMORY":
        return "MEMORY"

    if route == "EQUATION":
        return "EQUATION"

    if route == "CALCULATOR":
        return "CALCULATOR"

    # =================================
    # 3. File commands
    # =================================

    if message.startswith("create "):
        return "CREATE_FILE"

    if message.startswith("delete "):
        return "DELETE_FILE"

    if message.startswith("read "):
        return "FILE"

    if (
        message.startswith("run ")
        and message.endswith(".py")
    ):
        return "RUN_PYTHON"

    # =================================
    # 4. Personal memory questions
    # =================================

    memory_phrases = [
        "what is my name",
        "who am i",
        "my favourite language",
        "my favorite language",
        "my favourite programming language",
        "my favorite programming language",
        "my favourite subject",
        "my favorite subject",
    ]

    if any(
        phrase in message
        for phrase in memory_phrases
    ):
        return "MEMORY"

    # =================================
    # 5. Live time
    # =================================
    #
    # Use WEB because web_tool.py now
    # contains verified timezone handling.
    # =================================

    time_phrases = [
        "what time is it",
        "what is the time",
        "what time",
        "time now",
        "current time",
        "time right now",
        "tell me the time",
    ]

    if any(
        phrase in message
        for phrase in time_phrases
    ):
        return "WEB"

    # =================================
    # 6. Live date
    # =================================

    date_phrases = [
        "what is today's date",
        "what is todays date",
        "today's date",
        "todays date",
        "current date",
        "date today",
        "what is the date",
        "what's the date",
    ]

    if any(
        phrase in message
        for phrase in date_phrases
    ):
        return "WEB"

    # =================================
    # 7. Weather
    # =================================

    weather_phrases = [
        "weather",
        "temperature",
        "forecast",
    ]

    if any(
        phrase in message
        for phrase in weather_phrases
    ):
        return "WEB"

    # =================================
    # 8. Current office holders
    # =================================

    current_role_phrases = [
        "current chief minister",
        "current prime minister",
        "current president",
        "current governor",
        "current minister",
        "current ceo",
        "current chairman",
        "current mayor",
        "current captain",
        "who is the chief minister",
        "who is the prime minister",
        "who is the president",
        "who is the governor",
        "who is the ceo",
    ]

    if any(
        phrase in message
        for phrase in current_role_phrases
    ):
        return "WEB"

    # =================================
    # 9. Fresh/current web information
    # =================================

    web_keywords = [
        "latest",
        "news",
        "breaking",
        "recent developments",
        "latest developments",
        "this week",
        "this month",
        "last 24 hours",
        "past 24 hours",
        "search the web",
        "search internet",
        "search online",
        "google",
    ]

    if any(
        keyword in message
        for keyword in web_keywords
    ):
        return "WEB"

    # =================================
    # 10. AI Models
    # =================================

    if route in {
        "CODER",
        "SMART",
        "VERY_DEEP",
        "UNKNOWN",
    }:
        return "AI"

    # =================================
    # 11. Default
    # =================================

    return "AI"