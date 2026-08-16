from memory_system.memory import load_memory, save_memory

MODES = {
    "auto": "🤖 Auto",
    "quick": "⚡ Quick",
    "balanced": "🎯 Balanced",
    "research": "🔬 Research",
    "builder": "🛠️ Builder",
    "solve": "📝 Solve",
    "teacher": "👨‍🏫 Teacher",
}


def get_current_mode():
    mode = load_memory("response_mode")

    if mode is None:
        mode = "auto"
        save_memory("response_mode", mode)

    return mode


def set_mode(mode):
    mode = mode.lower().strip()

    if mode not in MODES:
        return False

    save_memory("response_mode", mode)
    return True


def show_modes():

    current = get_current_mode()

    text = f"""
🤖 Chopper Modes

Current Mode:
{MODES[current]}

---------------------------------------

1. ⚡ Quick
   Fast responses.

2. 🎯 Balanced
   Best for everyday use.

3. 🔬 Research
   Maximum accuracy and web verification.

4. 🛠️ Builder
   Build projects, code and solutions.

5. 📝 Solve
   Direct answers with minimal explanation.

6. 👨‍🏫 Teacher
   Teach step-by-step.

7. 🤖 Auto
   Chopper decides automatically.

---------------------------------------

Type:

quick
balanced
research
builder
solve
teacher
auto

or

cancel
"""

    return text