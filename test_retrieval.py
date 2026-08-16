from memory_system.memory import get_all_memories

memories = get_all_memories()

print("Chopper's Memories:\n")

for key, value in memories:
    print(f"{key} : {value}")