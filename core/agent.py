def execute_plan(plan):
    """
    Execute a plan step by step.

    (For now, this only prints the plan.
    Later it will execute each tool automatically.)
    """

    print("\n🤖 Agent Execution\n")

    for index, step in enumerate(plan["steps"], start=1):
        print(f"Step {index}: {step}")

    print("\n✅ Plan complete.\n")