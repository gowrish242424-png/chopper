import time


class PerformanceTimer:
    def __init__(self):
        self.start = time.perf_counter()
        self.points = {}

    def mark(self, name):
        self.points[name] = time.perf_counter()

    def duration(self, start, end):
        if start not in self.points or end not in self.points:
            return 0.0
        return self.points[end] - self.points[start]

    def total(self):
        return time.perf_counter() - self.start

    def report(self, route, model):
        print("\n" + "=" * 40)
        print("⚡ Chopper Performance")
        print("=" * 40)

        print(f"Route          : {route}")
        print(f"Model          : {model}")

        if "memory_start" in self.points and "memory_end" in self.points:
            print(
                f"Memory Search  : "
                f"{self.duration('memory_start','memory_end'):.3f}s"
            )

        if "ai_start" in self.points and "ai_end" in self.points:
            print(
                f"AI Generation  : "
                f"{self.duration('ai_start','ai_end'):.3f}s"
            )

        print(f"Total Time     : {self.total():.3f}s")

        print("=" * 40)