import threading
import time

from core.adaptive_progress import (
    get_expected_time,
    calculate_progress,
)


class ProgressBar:

    def __init__(self):
        self.running = False
        self.progress = 0.0

        self.model = None
        self.difficulty = "medium"

        self.expected_time = 30.0
        self.start_time = None

    def start(
        self,
        model,
        difficulty="medium",
    ):
        """
        Start adaptive generation progress.

        Progress speed depends on:
        - selected model
        - question difficulty
        - learned historical generation time
        """

        self.running = True
        self.progress = 0.0

        self.model = model
        self.difficulty = difficulty

        self.expected_time = get_expected_time(
            model,
            difficulty,
        )

        self.start_time = time.perf_counter()

        threading.Thread(
            target=self.animate,
            daemon=True,
        ).start()

    def animate(self):
        """
        Update progress using elapsed time
        and learned expected generation time.
        """

        while self.running:

            elapsed = (
                time.perf_counter()
                - self.start_time
            )

            self.progress = calculate_progress(
                elapsed,
                self.expected_time,
            )

            print(
                f"\rGenerating... "
                f"{self.progress:5.1f}%",
                end="",
                flush=True,
            )

            time.sleep(0.2)

    def finish(self):
        """
        Generation actually completed.

        Only now can progress reach 100%.
        """

        self.running = False
        self.progress = 100.0

        print(
            "\rGenerating... 100.0%"
        )