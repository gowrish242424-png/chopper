from memory_system.memory import save_memory
from memory_manager import should_remember


while True:
    user_message = input("You: ").strip()

    if user_message.lower() in {
        "exit",
        "quit",
        "end",
    }:
        print("Test ended.")
        break

    response = should_remember(
        user_message
    )

    print("\nMemory Manager:")
    print(response)

    try:
        # should_remember() already returns a dict
        data = response

        if data.get("remember", False):

            key = data.get("key")
            value = data.get("value")

            if key and value:
                save_memory(
                    key,
                    value,
                )

                print(
                    "\n✅ Saved to database!"
                )
                print(
                    f"Key   : {key}"
                )
                print(
                    f"Value : {value}"
                )

            else:
                print(
                    "\n⚠️ Memory data is incomplete."
                )

        else:
            print(
                "\nNothing to save."
            )

    except Exception as error:
        print(
            "\nMemory Manager Error:"
        )
        print(error)