import subprocess


def run_python(filename):
    try:
        result = subprocess.run(
            ["python", filename],
            capture_output=True,
            text=True,
        )

        return result.stdout or result.stderr

    except Exception as e:
        return str(e)