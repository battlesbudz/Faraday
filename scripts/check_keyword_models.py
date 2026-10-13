"""Exercise the actual native frontend/interpreter; acoustic accuracy needs phone tests."""
import hashlib
import json
import subprocess
import sys
import tempfile
from pathlib import Path

models = Path("app/src/main/assets/microwakeword")
expected = {"hey_jarvis": "21a7976add39ee24ec96c63d96b7aaa18e24d1d9824b963e451da8feb4b78b77",
            "stop": "b5a18c4ad681a89950dfade31011e1631bdcb333e93c84519a1a63ff4f071146"}
with tempfile.TemporaryDirectory() as directory:
    audio = Path(directory) / "silence.pcm"
    audio.write_bytes(bytes(16000 * 2 * 5))
    for name, digest in expected.items():
        model = models / f"{name}.tflite"
        assert hashlib.sha256(model.read_bytes()).hexdigest() == digest
        cutoff = "0.5" if name == "stop" else "0.97"
        result = subprocess.check_output([sys.argv[1], str(model), str(audio), cutoff], text=True)
        data = json.loads(result.strip().splitlines()[-1])
        assert data["frames"] == 80000 and data["detections"] == 0, data
        print(f"{name}: native model initialized, 5 seconds processed, no silence trigger")
