from core.config import RESPONSE_MODE


def format_response(answer, suggestion=None, warning=None):

    if RESPONSE_MODE == "FAST":
        return answer

    elif RESPONSE_MODE == "SMART":

        result = answer

        if suggestion:
            result += f"\n\n💡 Suggestion:\n{suggestion}"

        return result

    elif RESPONSE_MODE == "ENGINEER":

        result = "Answer\n"
        result += "────────────────────\n"
        result += answer

        if suggestion:
            result += f"\n\n💡 Better Idea\n{suggestion}"

        if warning:
            result += f"\n\n⚠️ Risk\n{warning}"

        return result

    elif RESPONSE_MODE == "TEACHER":

        return answer

    return answer