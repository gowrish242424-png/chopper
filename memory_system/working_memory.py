MAX_MESSAGES = 20

conversation = []


def add_message(role, content):
    conversation.append(
        {
            "role": role,
            "content": content
        }
    )

    if len(conversation) > MAX_MESSAGES:
        conversation.pop(0)


def get_messages():
    return conversation.copy()


def clear():
    conversation.clear()