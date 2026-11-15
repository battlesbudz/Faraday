"""Model backend boundary and deterministic test implementations."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol

from .transcript import Transcript


@dataclass(frozen=True)
class Generation:
    text: str
    tool_calls: tuple[dict, ...] = ()


class ModelBackend(Protocol):
    def generate(self, transcript: Transcript, variant: str) -> Generation: ...


class FakeModelBackend:
    """Deterministic backend: a variant may provide fixture answers by transcript id."""

    def __init__(self, answers: dict[str, str] | None = None):
        self.answers = answers or {}

    def generate(self, transcript: Transcript, variant: str) -> Generation:
        configured = transcript.metadata.get("fake_outputs", {})
        text = self.answers.get(transcript.id)
        if isinstance(configured, dict):
            text = configured.get(variant, text)
        if text is None:
            text = transcript.last_assistant_text or "I don't know."
        return Generation(str(text))


class ScriptedBackend:
    """Returns fixed outputs keyed by (variant, transcript id), useful in tests."""

    def __init__(self, outputs: dict[tuple[str, str], Generation | str]):
        self.outputs = outputs

    def generate(self, transcript: Transcript, variant: str) -> Generation:
        output = self.outputs.get((variant, transcript.id), transcript.last_assistant_text)
        return output if isinstance(output, Generation) else Generation(str(output))
