def show_plan(plan):
    """
    Display Chopper's execution plan.
    """

    print("\n🧠 Chopper Plan")

    for i, step in enumerate(
        plan["steps"],
        start=1,
    ):
        print(f"{i}. {step}")

    print()