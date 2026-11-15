"""Deterministic transcript-output checks. Each finding is a stable signal."""

from __future__ import annotations

from dataclasses import dataclass
import re
from typing import Any

from .backend import Generation
from .transcript import Transcript


@dataclass(frozen=True)
class CheckResult:
    signals: dict[str, bool]
    details: dict[str, str]

    @property
    def passed(self) -> bool:
        return all(self.signals.values())

    @property
    def violations(self) -> int:
        return sum(not passed for passed in self.signals.values())


class CheckerSuite:
    """Checks refusal, unsupported perception, repeated replies, and tool contracts."""

    REFUSAL = re.compile(r"\b(?:i (?:can't|cannot|won't) (?:help|answer|provide)|i(?:'m| am) unable to help|i must refuse)\b", re.I)
    PERCEPTION = re.compile(r"\b(?:i can see|i see|i can hear|i hear|looking at (?:the|your)|in the image (?:i|there))\b", re.I)

    def __init__(self, tool_schemas: dict[str, dict[str, Any]] | None = None):
        self.tool_schemas = tool_schemas or {}

    def check(self, transcript: Transcript, output: Generation) -> CheckResult:
        answerable = transcript.metadata.get("answerable") is True
        refusal = not (answerable and bool(self.REFUSAL.search(output.text)))

        modalities = transcript.metadata.get("input_modalities", [])
        modalities = modalities if isinstance(modalities, list) else []
        has_visual = "image" in modalities
        has_audio = "audio" in modalities
        perception_claim = bool(self.PERCEPTION.search(output.text))
        perception = not (perception_claim and not (has_visual or has_audio))

        assistant_turns = transcript.assistant_turns
        previous = assistant_turns[-1].text.strip() if assistant_turns else ""
        repeated = not (previous and self._normalize(previous) == self._normalize(output.text))

        tool_valid = all(self._valid_call(call) for call in output.tool_calls)
        return CheckResult(
            {"answerable_refusal": refusal, "unsupported_perception": perception,
             "repeated_previous_answer": repeated, "tool_call_validity": tool_valid},
            {"answerable_refusal": "refusal on answerable transcript" if not refusal else "",
             "unsupported_perception": "perception claim without image/audio input" if not perception else "",
             "repeated_previous_answer": "output repeats the previous assistant answer" if not repeated else "",
             "tool_call_validity": "tool call violates configured schema" if not tool_valid else ""},
        )

    @staticmethod
    def _normalize(text: str) -> str:
        return " ".join(re.findall(r"\w+", text.casefold()))

    def _valid_call(self, call: dict[str, Any]) -> bool:
        name, arguments = call.get("name"), call.get("arguments")
        schema = self.tool_schemas.get(name) if isinstance(name, str) else None
        if schema is None or not isinstance(arguments, dict):
            return False
        properties = schema.get("properties", {})
        required = schema.get("required", [])
        if not isinstance(properties, dict) or not isinstance(required, list):
            return False
        if any(key not in arguments for key in required) or any(key not in properties for key in arguments):
            return False
        types = {"string": str, "integer": int, "number": (int, float), "boolean": bool, "object": dict, "array": list}
        for key, value in arguments.items():
            expected = types.get(properties.get(key, {}).get("type"))
            if expected is None or not isinstance(value, expected) or (expected is int and isinstance(value, bool)):
                return False
        return True
