from tools.calculator_tool import run_calculator
from tools.command_handler import handle_command
from tools.equation_tool import solve_equation
from tools.file_tool import create_file, delete_file, read_file
from tools.python_tool import run_python
from tools.web_tool import search_web
from core.mode_manager import (
    show_modes,
    set_mode,
    get_current_mode,
)

def extract_filename(user_message, command_words):
    """
    Remove a command prefix and return the filename.

    Example:
        "Read main.py" -> "main.py"
    """

    message = user_message.strip()

    for command in command_words:
        if message.lower().startswith(command):
            return message[len(command):].strip()

    return message


def run_tool(tool_name, user_message):
    """
    Execute a local Chopper tool.

    Returns:
        str: when a tool handles the request.
        None: when the AI should answer.
    """

    # Commands and memory
    if tool_name in {
        "GREETING",
        "MEMORY",
        "TIME",
        "DATE",
        "COMMAND",
    }:
        return handle_command(user_message)
        # -------------------------
    # Mode Manager
    # -------------------------
    if tool_name == "MODE":

        command = user_message.lower().strip()

        if command == "mode":
            return show_modes()

        if command in {
            "quick",
            "balanced",
            "research",
            "builder",
            "solve",
            "teacher",
            "auto",
        }:

            set_mode(command)

            return (
                "✅ Chopper mode changed successfully.\n\n"
                f"Current Mode:\n"
                f"{get_current_mode().capitalize()}"
            )

        if command == "cancel":
            return "Mode change cancelled."

        return None
    # Calculator
    if tool_name == "CALCULATOR":
        return run_calculator(user_message)

    # Equation solver
    if tool_name == "EQUATION":
        return solve_equation(user_message)

    # Read file
    if tool_name in {"FILE", "READ_FILE"}:
        filename = extract_filename(
            user_message,
            [
                "read file ",
                "read ",
                "open file ",
                "open ",
                "show file ",
                "show ",
                "display file ",
                "display ",
            ],
        )

        if not filename:
            return "Please provide a filename."

        return read_file(filename)

    # Create file
    if tool_name == "CREATE_FILE":
        filename = extract_filename(
            user_message,
            [
                "create file ",
                "create ",
            ],
        )

        if not filename:
            return "Please provide a filename."

        return create_file(filename)

    # Delete file
    if tool_name == "DELETE_FILE":
        filename = extract_filename(
            user_message,
            [
                "delete file ",
                "delete ",
            ],
        )

        if not filename:
            return "Please provide a filename."

        return delete_file(filename)

    # Run Python file
    if tool_name == "RUN_PYTHON":
        filename = extract_filename(
            user_message,
            [
                "run python ",
                "run ",
            ],
        )

        if not filename:
            return "Please provide a Python filename."

        return run_python(filename)
    if tool_name == "WEB":
        query = user_message.strip()

        prefixes = [
            "search ",
            "google ",
            "find ",
        ]

        for prefix in prefixes:
            if query.lower().startswith(prefix):
                query = query[len(prefix):].strip()
                break

        if not query:
            return "Please provide something to search for."

        return search_web(query)
    # AI should answer
    return None