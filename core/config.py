# ==========================
# Chopper Configuration
# ==========================


# --------------------------
# AI Models
# --------------------------

# Fastest model for short and simple requests
MODEL_FAST = "qwen3:1.7b"

# Specialized coding model
MODEL_CODER = "qwen2.5-coder:3b"

# Balanced everyday model
MODEL_SMART = "qwen2.5:3b"

# Strong model for research, teaching, and complex reasoning
MODEL_DEEP = "qwen3:8b"

# Router fallback model
ROUTER_MODEL = MODEL_FAST


# --------------------------
# Legacy Response Mode
# --------------------------
# Your new mode system uses core/mode_manager.py.
# Keep this only for compatibility with older code.
#
# Possible values:
# FAST
# SMART
# ENGINEER
# TEACHER

RESPONSE_MODE = "FAST"


# --------------------------
# Conversation
# --------------------------

# Maximum messages stored in the active conversation
MAX_CONVERSATION = 20

# Number of recent messages sent to Ollama
CONVERSATION_MESSAGES_SENT = 4


# --------------------------
# Memory
# --------------------------

# Maximum number of matching memories returned
MAX_MEMORY_RESULTS = 10


# --------------------------
# Ollama Context
# --------------------------

# Context window size
NUM_CONTEXT = 4096


# --------------------------
# Output Token Limits
# --------------------------

# Quick responses
NUM_PREDICT_FAST = 300

# Coding responses
NUM_PREDICT_CODER = 1600

# Balanced and Solve responses
NUM_PREDICT_SMART = 900

# Research, Builder, and Teacher responses
NUM_PREDICT_DEEP = 2200


# --------------------------
# Generation Settings
# --------------------------

# Lower values are more focused and consistent
TEMPERATURE = 0.3


# --------------------------
# Ollama Model Memory
# --------------------------

# Keep the selected model loaded for a short time
KEEP_ALIVE = "5m"


# --------------------------
# Chopper Information
# --------------------------

AI_NAME = "Chopper"
VERSION = "2.1"