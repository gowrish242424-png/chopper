from core.brain import ask_chopper

print("=" * 50)
print("🤖 Welcome to Chopper AI")
print("=" * 50)
print("Type /multi for a multiline request.")

while True:
    user = input("\nYou: ").strip()

    if user.lower() in ["bye", "exit", "quit"]:
        print("Chopper: Goodbye! 👋")
        break

    if user.lower() == "/multi":
        print("\nEnter your full request.")
        print("Type END on a separate line when finished.\n")

        lines = []

        while True:
            line = input()

            if line.strip().upper() == "END":
                break

            lines.append(line)

        user = "\n".join(lines).strip()

        if not user:
            print("Chopper: No request was entered.")
            continue

    ask_chopper(user)