import threading
import tkinter as tk
import time

from tkinter import messagebox, scrolledtext, ttk
from core.brain import ask_chopper
from core.mode_manager import (
    MODES,
    get_current_mode,
    set_mode,
)
from core.adaptive_progress import (
    get_expected_time,
    calculate_progress,
    estimate_difficulty,
)


MODE_ORDER = [
    "auto",
    "quick",
    "balanced",
    "research",
    "builder",
    "solve",
    "teacher",
]


class ChopperGUI:
    def __init__(self, root):
        self.root = root

        # -------------------------
        # Window settings
        # -------------------------
        self.root.title("Chopper AI")
        self.root.geometry("950x700")
        self.root.minsize(750, 550)

        # -------------------------
        # Generation state
        # -------------------------
        self.is_generating = False

        # -------------------------
        # Progress state
        # -------------------------
        self.progress_value = 0
        self.progress_job = None
        self.progress_started_at = None
        self.receiving_ai_chunks = False

        # Estimated response time for each mode
        self.mode_expected_seconds = {
            "quick": 8,
            "balanced": 18,
            "research": 55,
            "builder": 70,
            "solve": 12,
            "teacher": 45,
            "auto": 25,
        }

        # -------------------------
        # Build interface
        # -------------------------
        self.create_header()
        self.create_mode_section()

        # Create bottom controls before the expandable chat section.
        # This prevents the chat area from hiding the progress bar.
        self.create_status_bar()
        self.create_input_section()
        self.create_chat_section()

        # -------------------------
        # Load saved mode
        # -------------------------
        current_mode = get_current_mode()

        self.mode_variable.set(current_mode)
        self.update_mode_display(current_mode)

        # -------------------------
        # Welcome message
        # -------------------------
        self.add_chopper_message(
            "Hello, Sir! I am Chopper.\n"
            "Choose a mode above or send a message."
        )

        # Put the typing cursor inside the message box
        self.message_entry.focus_set()

    def create_header(self):
        """Create the application header."""

        header = tk.Frame(
            self.root,
            padx=15,
            pady=12,
        )
        header.pack(
            fill="x",
        )

        title = tk.Label(
            header,
            text="🤖 Chopper AI",
            font=("Segoe UI", 20, "bold"),
        )
        title.pack(side="left")

        user_label = tk.Label(
            header,
            text="Sir",
            font=("Segoe UI", 11),
        )
        user_label.pack(side="right")

    def create_mode_section(self):
        """Create clickable mode-selection controls."""

        mode_frame = tk.LabelFrame(
            self.root,
            text="Intelligence Mode",
            padx=12,
            pady=10,
        )
        mode_frame.pack(
            fill="x",
            padx=15,
            pady=(0, 10),
        )

        self.mode_variable = tk.StringVar()

        mode_label = tk.Label(
            mode_frame,
            text="Select mode:",
            font=("Segoe UI", 10, "bold"),
        )
        mode_label.pack(
            side="left",
            padx=(0, 10),
        )

        self.mode_combobox = ttk.Combobox(
            mode_frame,
            state="readonly",
            width=23,
            font=("Segoe UI", 10),
        )

        self.mode_display_to_key = {
            MODES[key]: key
            for key in MODE_ORDER
        }

        self.mode_combobox["values"] = [
            MODES[key]
            for key in MODE_ORDER
        ]

        self.mode_combobox.pack(
            side="left",
            padx=(0, 12),
        )

        self.mode_combobox.bind(
            "<<ComboboxSelected>>",
            self.on_combobox_mode_selected,
        )

        self.current_mode_label = tk.Label(
            mode_frame,
            text="Current: 🤖 Auto",
            font=("Segoe UI", 10, "bold"),
        )
        self.current_mode_label.pack(
            side="left",
            padx=10,
        )

        mode_buttons_frame = tk.Frame(mode_frame)
        mode_buttons_frame.pack(
            side="right",
        )

        quick_button = tk.Button(
            mode_buttons_frame,
            text="⚡ Quick",
            command=lambda: self.change_mode("quick"),
        )
        quick_button.pack(
            side="left",
            padx=3,
        )

        balanced_button = tk.Button(
            mode_buttons_frame,
            text="🎯 Balanced",
            command=lambda: self.change_mode("balanced"),
        )
        balanced_button.pack(
            side="left",
            padx=3,
        )

        research_button = tk.Button(
            mode_buttons_frame,
            text="🔬 Research",
            command=lambda: self.change_mode("research"),
        )
        research_button.pack(
            side="left",
            padx=3,
        )

        teacher_button = tk.Button(
            mode_buttons_frame,
            text="👨‍🏫 Teacher",
            command=lambda: self.change_mode("teacher"),
        )
        teacher_button.pack(
            side="left",
            padx=3,
        )

    def create_chat_section(self):
        """Create the conversation display."""

        chat_frame = tk.Frame(
            self.root,
            padx=15,
        )
        chat_frame.pack(
            fill="both",
            expand=True,
        )

        self.chat_display = scrolledtext.ScrolledText(
            chat_frame,
            wrap=tk.WORD,
            font=("Segoe UI", 11),
            state="disabled",
            padx=12,
            pady=12,
        )
        self.chat_display.pack(
            fill="both",
            expand=True,
        )

        self.chat_display.tag_configure(
            "user",
            font=("Segoe UI", 11, "bold"),
            spacing1=8,
            spacing3=10,
        )

        self.chat_display.tag_configure(
            "chopper",
            font=("Segoe UI", 11),
            spacing1=8,
            spacing3=12,
        )

        self.chat_display.tag_configure(
            "system",
            font=("Segoe UI", 9, "italic"),
            justify="center",
            spacing1=5,
            spacing3=5,
        )

    def create_input_section(self):
        """Create message input and send controls."""

        input_frame = tk.Frame(
            self.root,
            padx=15,
            pady=12,
        )
        input_frame.pack(
            fill="x",
        )

        self.message_entry = tk.Text(
            input_frame,
            height=3,
            wrap=tk.WORD,
            font=("Segoe UI", 11),
        )
        self.message_entry.pack(
            side="left",
            fill="x",
            expand=True,
            padx=(0, 10),
        )

        self.message_entry.bind(
            "<Control-Return>",
            self.send_message_event,
        )

        self.send_button = tk.Button(
            input_frame,
            text="Send",
            width=12,
            height=2,
            command=self.send_message,
        )
        self.send_button.pack(
            side="right",
        )

        hint = tk.Label(
            self.root,
            text="Press Ctrl + Enter to send",
            font=("Segoe UI", 8),
        )
        hint.pack(
            anchor="e",
            padx=20,
            pady=(0, 5),
        )

    def create_status_bar(self):
        """Create progress display and status bar."""

        progress_frame = tk.Frame(
            self.root,
            padx=15,
            pady=5,
        )
        progress_frame.pack(
            fill="x",
            side="bottom",
        )

        self.progress_text = tk.StringVar(value="")

        self.progress_label = tk.Label(
            progress_frame,
            textvariable=self.progress_text,
            font=("Segoe UI", 9),
            anchor="w",
        )
        self.progress_label.pack(fill="x")

        self.progress_bar = ttk.Progressbar(
            progress_frame,
            orient="horizontal",
            mode="determinate",
            maximum=100,
            value=0,
        )
        self.progress_bar.pack(
            fill="x",
            pady=(3, 0),
        )

        self.status_variable = tk.StringVar(value="Ready")

        status_bar = tk.Label(
            self.root,
            textvariable=self.status_variable,
            anchor="w",
            padx=10,
            pady=5,
            relief=tk.SUNKEN,
        )
        status_bar.pack(
            fill="x",
            side="bottom",
        )

    def on_combobox_mode_selected(self, _event):
        """Change mode when selected from the dropdown."""

        display_name = self.mode_combobox.get()
        mode_key = self.mode_display_to_key.get(display_name)

        if mode_key:
            self.change_mode(mode_key)

    def change_mode(self, mode):
        """Save and display the selected Chopper mode."""

        if not set_mode(mode):
            messagebox.showerror(
                "Mode Error",
                f"Unknown mode: {mode}",
            )
            return

        self.mode_variable.set(mode)
        self.update_mode_display(mode)

        self.add_system_message(
            f"Mode changed to {MODES[mode]}"
        )

    def update_mode_display(self, mode):
        """Refresh visible mode information."""

        display_name = MODES.get(
            mode,
            MODES["auto"],
        )

        self.current_mode_label.config(
            text=f"Current: {display_name}"
        )

        self.mode_combobox.set(display_name)

        self.status_variable.set(
            f"Ready | Mode: {display_name}"
        )

    def add_user_message(self, message):
        """Add a user message to the chat display."""

        self.chat_display.config(
            state="normal"
        )

        # Always separate the new user message
        # from the previous Chopper response.
        self.chat_display.insert(
            tk.END,
            "\n\n"
        )

        self.chat_display.insert(
            tk.END,
            "You:\n",
            "user",
        )

        self.chat_display.insert(
            tk.END,
            f"{message}\n\n",
        )

        self.chat_display.config(
            state="disabled"
        )

        self.chat_display.see(
            tk.END
        )

    def add_chopper_message(self, message):
        """Add a Chopper response to the chat display."""

        self.chat_display.config(state="normal")

        self.chat_display.insert(
            tk.END,
            "Chopper:\n",
            "user",
        )

        self.chat_display.insert(
            tk.END,
            f"{message}\n\n",
            "chopper",
        )

        self.chat_display.config(state="disabled")
        self.chat_display.see(tk.END)

    def add_system_message(self, message):
        """Add an application status message."""

        self.chat_display.config(state="normal")

        self.chat_display.insert(
            tk.END,
            f"— {message} —\n\n",
            "system",
        )

        self.chat_display.config(state="disabled")
        self.chat_display.see(tk.END)

    def send_message_event(self, _event):
        """Keyboard shortcut for sending."""

        self.send_message()
        return "break"

    def send_message(self):
        """Read and submit the user's message."""

        if self.is_generating:
            return

        user_message = self.message_entry.get(
            "1.0",
            tk.END,
        ).strip()

        if not user_message:
            return

        self.message_entry.delete(
            "1.0",
            tk.END,
        )

        self.add_user_message(user_message)
        self.start_streaming_message()
        self.is_generating = True
        self.start_progress()

        self.send_button.config(
            state="disabled",
            text="Working...",
        )
        self.status_variable.set(
            "Chopper is generating a response..."
        )

        worker = threading.Thread(
            target=self.generate_response,
            args=(user_message,),
            daemon=True,
        )
        worker.start()

    def generate_response(self, user_message):
        """Run Chopper without freezing the GUI."""

        try:
            reply = ask_chopper(
                user_message,
                progress_callback=self.on_ai_chunk,
                response_callback=self.on_response_chunk,
                status_callback=self.on_retrieval_status,
                model_callback=self.on_model_selected,
            )

            if reply is None:
                reply = "I couldn't generate a response."

        except Exception as error:
            reply = (
                "An error occurred:\n"
                f"{error}"
            )

        self.root.after(
            0,
            self.finish_response,
            reply,
        )
    def finish_response(self, reply):
        """Finish streaming and restore the controls."""

        self.is_generating = False
        self.stop_progress()

        # Add spacing after the streamed response
        self.chat_display.config(state="normal")
        self.chat_display.insert(
            tk.END,
            "\n\n",
        )
        self.chat_display.config(state="disabled")
        self.chat_display.see(tk.END)

        self.send_button.config(
            state="normal",
            text="Send",
        )

        current_mode = get_current_mode()
        self.update_mode_display(current_mode)

        self.message_entry.focus_set()

    def start_progress(
        self,
        model="qwen2.5:3b",
        difficulty="medium",
    ):
        """Start adaptive progress tracking."""
        self.progress_value = 1
        self.progress_started_at = time.monotonic()
        self.receiving_ai_chunks = False

        self.progress_model = model
        self.progress_difficulty = difficulty

        self.progress_expected_time = get_expected_time(
            model,
            difficulty,
        )
        self.progress_bar["value"] = 1

        self.progress_text.set(
            "🧠 Understanding your question... 1%"
        )

        self.root.update_idletasks()

        if self.progress_job is not None:
            self.root.after_cancel(self.progress_job)

        self.progress_job = self.root.after(
            100,
            self.animate_progress,
        )

        self.animate_progress()


    def animate_progress(self):
        """
        Animate progress using the learned mean
        generation time for the selected model
        and question difficulty.

        Progress can reach 99% while generating.
        Only stop_progress() can set it to 100%.
        """

        if not self.is_generating:
            return

        elapsed = (
            time.monotonic()
            - self.progress_started_at
        )

        expected_seconds = getattr(
            self,
            "progress_expected_time",
            30.0,
        )

        # Calculate adaptive progress from
        # learned generation time.
        estimated = calculate_progress(
            elapsed,
            expected_seconds,
        )

        # Never allow progress to move backwards.
        self.progress_value = max(
            self.progress_value,
            estimated,
        )

        # --------------------------------
        # Select visible stage
        # --------------------------------

        if self.receiving_ai_chunks:

            if self.progress_value < 45:
                stage = "✍️ Starting the answer"

            elif self.progress_value < 80:
                stage = "✍️ Generating the answer"

            elif self.progress_value < 95:
                stage = "📝 Completing the explanation"

            else:
                stage = "✅ Finalizing the response"

        else:

            if self.progress_value < 8:
                stage = "🧠 Understanding your question"

            elif self.progress_value < 15:
                stage = "⚙️ Selecting the best approach"

            elif self.progress_value < 22:
                stage = "📚 Searching memory"

            else:
                stage = "🤖 Loading and preparing the AI model"

        self.progress_bar["value"] = (
            self.progress_value
        )

        self.progress_text.set(
            f"{stage}... "
            f"{int(self.progress_value)}%"
        )

        self.progress_job = self.root.after(
            150,
            self.animate_progress,
        )
    def stop_progress(self):
        """Finish the progress display."""

        if self.progress_job is not None:
            self.root.after_cancel(
                self.progress_job
            )
            self.progress_job = None

        self.progress_value = 100

        self.progress_bar["value"] = 100

        self.progress_text.set(
            "✅ Response completed — 100%"
        )

        self.root.update_idletasks()

        self.root.after(
            2000,
            self.clear_progress,
        )


    def clear_progress(self):
        """Clear progress after completion."""

        self.progress_bar["value"] = 0
        self.progress_text.set("")
    def on_ai_chunk(
        self,
        chunk_length,
        total_length,
    ):
        """
        Receive AI-generation updates from the worker thread.

        Tkinter must only be updated from the main thread,
        so root.after() schedules the real GUI update.
        """

        self.root.after(
            0,
            self.update_chunk_progress,
            chunk_length,
            total_length,
        )


    def update_chunk_progress(
        self,
        chunk_length,
        total_length,
    ):
        """
        Mark that AI output has started.

        The adaptive time-based system controls
        the percentage. This function only updates
        the visible generation stage.
        """

        if not self.is_generating:
            return

        self.receiving_ai_chunks = True

        # Do not modify self.progress_value here.
        # animate_progress() controls the percentage.

        if self.progress_value < 45:
            stage = "✍️ Starting the answer"

        elif self.progress_value < 80:
            stage = "✍️ Generating the answer"

        elif self.progress_value < 95:
            stage = "📝 Completing the explanation"

        else:
            stage = "✅ Finalizing the response"

        self.progress_bar["value"] = (
            self.progress_value
        )

        self.progress_text.set(
            f"{stage}... "
            f"{int(self.progress_value)}%"
        )

        self.root.update_idletasks()
    def start_streaming_message(self):
        """Create an empty Chopper response in the chat window."""

        self.chat_display.config(state="normal")

        self.chat_display.insert(
            tk.END,
            "Chopper:\n",
            "user",
        )

        self.streaming_start_index = self.chat_display.index(
            tk.END
        )

        self.chat_display.config(state="disabled")
        self.chat_display.see(tk.END)


    def on_response_chunk(self, content):
        """
        Receive a response chunk from the worker thread.

        Tkinter widgets must be updated from the main thread.
        """

        self.root.after(
            0,
            self.append_response_chunk,
            content,
        )


    def append_response_chunk(self, content):
        """Display one generated piece of the answer."""

        self.chat_display.config(state="normal")

        self.chat_display.insert(
            tk.END,
            content,
            "chopper",
        )

        self.chat_display.config(state="disabled")
        self.chat_display.see(tk.END)
    def on_retrieval_status(self, source):
        """Receive parallel retrieval updates safely."""

        self.root.after(
            0,
            self.update_retrieval_status,
            source,
        )
    def on_model_selected(
        self,
        model,
        difficulty,
    ):
        """
        Receive the actual model and difficulty
        selected by Chopper.
        """

        self.root.after(
            0,
            self.update_adaptive_progress,
            model,
            difficulty,
        )


    def update_adaptive_progress(
        self,
        model,
        difficulty,
    ):
        """
        Switch the running progress tracker to
        the learned timing for the actual model
        and question difficulty.
        """

        self.progress_model = model
        self.progress_difficulty = difficulty

        self.progress_expected_time = get_expected_time(
            model,
            difficulty,
        )


    def update_retrieval_status(self, source):
        """Update the visible progress stage."""

        if source == "memory":
            text = "✅ Memory search completed"

            if self.progress_value < 18:
                self.progress_value = 18

        elif source == "web":
            text = "✅ Web search completed"

            if self.progress_value < 28:
                self.progress_value = 28

        else:
            text = "✅ Information collected"

        self.progress_bar["value"] = (
            self.progress_value
        )

        self.progress_text.set(
            f"{text} — {int(self.progress_value)}%"
        )
    
def main():
    root = tk.Tk()
    ChopperGUI(root)
    root.mainloop()


if __name__ == "__main__":
    main()