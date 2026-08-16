import re


def format_number(value):
    """
    Format numbers cleanly.

    Examples:
    9.0     -> 9
    2.5     -> 2.5
    3.33333 -> 3.333333
    """

    value = float(value)

    if value.is_integer():
        return str(int(value))

    return f"{value:.6f}".rstrip("0").rstrip(".")


def parse_side(expression):
    """
    Convert one side of a simple linear equation into:

        coefficient_of_x, constant

    Examples:
        3x - 8   -> (3, -8)
        x + 5    -> (1, 5)
        -x + 4   -> (-1, 4)
        2x + 3x  -> (5, 0)
        19       -> (0, 19)
    """

    expression = expression.lower()
    expression = expression.replace(" ", "")
    expression = expression.replace("*", "")

    if not expression:
        raise ValueError("The equation side is empty.")

    # Only allow numbers, x, decimal points, plus and minus
    if not re.fullmatch(r"[0-9x+\-.]+", expression):
        raise ValueError("Unsupported characters in equation.")

    # Make every term begin with + or -
    if expression[0] not in "+-":
        expression = "+" + expression

    terms = re.findall(r"[+-][^+-]+", expression)

    if not terms:
        raise ValueError("Invalid equation.")

    coefficient = 0.0
    constant = 0.0

    for term in terms:
        sign = -1.0 if term[0] == "-" else 1.0
        body = term[1:]

        if not body:
            raise ValueError("Invalid term in equation.")

        if "x" in body:
            # Only simple terms such as x, 3x, -x or 2.5x
            if body.count("x") != 1 or not body.endswith("x"):
                raise ValueError(
                    "Only simple linear x terms are supported."
                )

            number_part = body[:-1]

            if number_part == "":
                term_coefficient = 1.0
            else:
                term_coefficient = float(number_part)

            coefficient += sign * term_coefficient

        else:
            constant += sign * float(body)

    return coefficient, constant


def clean_equation(user_message):
    """
    Remove optional words from the user's request.

    Examples:
        Solve 3x - 8 = 19
        Please solve: 3x - 8 = 19
        Find x: 3x - 8 = 19
    """

    equation = user_message.lower().strip()

    equation = equation.replace("×", "*")
    equation = equation.replace("−", "-")
    equation = equation.replace("–", "-")

    equation = re.sub(
        r"^(please\s+)?"
        r"(solve|solve\s+for\s+x|find\s+x|calculate)\s*"
        r"[:\-]?\s*",
        "",
        equation,
    )

    return equation.strip()


def solve_equation(user_message):
    """
    Solve a simple linear equation containing x.

    Supported examples:
        Solve 3x - 8 = 19
        5x + 7 = 42
        x + 4 = 10
        2x + 3 = x + 9
        3x + 4x = 21
        -x + 5 = 10

    Not supported:
        x² + 5x + 6 = 0
        1/x = 5
        sin(x) = 1
        equations with brackets
    """

    equation = clean_equation(user_message)

    if equation.count("=") != 1:
        return (
            "I can solve simple linear equations with one equals sign.\n"
            "Example: 3x - 8 = 19"
        )

    if "x" not in equation:
        return (
            "This equation does not contain x.\n"
            "Example: 3x - 8 = 19"
        )

    left_text, right_text = equation.split("=", 1)

    try:
        left_x, left_constant = parse_side(left_text)
        right_x, right_constant = parse_side(right_text)

        # Move all x terms to the left
        coefficient = left_x - right_x

        # Move all constants to the right
        constant = right_constant - left_constant

        # Example:
        # 2x + 3 = 2x + 3
        if abs(coefficient) < 1e-12 and abs(constant) < 1e-12:
            return (
                "Answer:\n"
                "Infinitely many solutions\n\n"
                "Steps:\n"
                "1. The x terms cancel.\n"
                "2. Both sides simplify to the same value."
            )

        # Example:
        # 2x + 3 = 2x + 7
        if abs(coefficient) < 1e-12:
            return (
                "Answer:\n"
                "No solution\n\n"
                "Steps:\n"
                "1. The x terms cancel.\n"
                "2. The remaining constants are not equal."
            )

        solution = constant / coefficient

        coefficient_text = format_number(coefficient)
        constant_text = format_number(constant)
        solution_text = format_number(solution)

        return (
            "Answer:\n"
            f"x = {solution_text}\n\n"
            "Steps:\n"
            "1. Move all x terms to one side and constants to the other.\n"
            f"2. Simplify: {coefficient_text}x = {constant_text}\n"
            f"3. Divide by {coefficient_text}: x = {solution_text}"
        )

    except (ValueError, OverflowError):
        return (
            "I couldn't solve that equation.\n"
            "Use a simple linear form such as:\n"
            "3x - 8 = 19"
        )