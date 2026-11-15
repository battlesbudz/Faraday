"""Validated JSONL conversation transcript format."""

from __future__ import annotations

from dataclasses import dataclass, field
import json
from pathlib import Path
from typing import Any, Iterable


@dataclass(frozen=True)
class Turn:
    role: str
    text: str = ""
    tool_calls: tuple[dict[str, Any], ...] = ()
    tool_results: tuple[dict[str, Any], ...] = ()
    diagnostics: dict[str, Any] = field(default_factory=dict)

    @classmethod
    def from_dict(cls, value: dict[str, Any]) -> "Turn":
        role = value.get("role")
        if role not in {"system", "user", "assistant", "tool"}:
            raise ValueError(f"invalid turn role: {role!r}")
        text = value.get("text", "")
        calls, results = value.get("tool_calls", []), value.get("tool_results", [])
        diagnostics = value.get("diagnostics", {})
        if not isinstance(text, str):
            raise ValueError("turn text must be a string")
        if not isinstance(calls, list) or not all(isinstance(x, dict) for x in calls):
            raise ValueError("tool_calls must be an array of objects")
        if not isinstance(results, list) or not all(isinstance(x, dict) for x in results):
            raise ValueError("tool_results must be an array of objects")
        if not isinstance(diagnostics, dict):
            raise ValueError("diagnostics must be an object")
        return cls(role, text, tuple(calls), tuple(results), diagnostics)

    def to_dict(self) -> dict[str, Any]:
        result: dict[str, Any] = {"role": self.role, "text": self.text}
        if self.tool_calls:
            result["tool_calls"] = list(self.tool_calls)
        if self.tool_results:
            result["tool_results"] = list(self.tool_results)
        if self.diagnostics:
            result["diagnostics"] = self.diagnostics
        return result


@dataclass(frozen=True)
class Transcript:
    id: str
    turns: tuple[Turn, ...]
    metadata: dict[str, Any] = field(default_factory=dict)

    @classmethod
    def from_dict(cls, value: dict[str, Any]) -> "Transcript":
        transcript_id = value.get("id")
        turns = value.get("turns")
        if not isinstance(transcript_id, str) or not transcript_id.strip():
            raise ValueError("transcript id must be a non-empty string")
        if not isinstance(turns, list) or not turns:
            raise ValueError(f"transcript {transcript_id!r} must have non-empty turns")
        metadata = value.get("metadata", {})
        if not isinstance(metadata, dict):
            raise ValueError("transcript metadata must be an object")
        return cls(transcript_id, tuple(Turn.from_dict(turn) for turn in turns), metadata)

    def to_dict(self) -> dict[str, Any]:
        return {"id": self.id, "turns": [turn.to_dict() for turn in self.turns], "metadata": self.metadata}

    @property
    def user_text(self) -> str:
        return "\n".join(turn.text for turn in self.turns if turn.role == "user")

    @property
    def assistant_turns(self) -> tuple[Turn, ...]:
        return tuple(turn for turn in self.turns if turn.role == "assistant")

    @property
    def last_assistant_text(self) -> str:
        turns = self.assistant_turns
        return turns[-1].text if turns else ""


def load_jsonl(path: str | Path) -> list[Transcript]:
    transcripts: list[Transcript] = []
    seen: set[str] = set()
    with Path(path).open(encoding="utf-8") as source:
        for line_number, raw in enumerate(source, 1):
            if not raw.strip() or raw.lstrip().startswith("#"):
                continue
            try:
                value = json.loads(raw)
                if not isinstance(value, dict):
                    raise ValueError("record must be an object")
                transcript = Transcript.from_dict(value)
                if transcript.id in seen:
                    raise ValueError(f"duplicate transcript id {transcript.id!r}")
                seen.add(transcript.id)
                transcripts.append(transcript)
            except (json.JSONDecodeError, ValueError) as error:
                raise ValueError(f"{path}:{line_number}: {error}") from error
    return transcripts


def dump_jsonl(transcripts: Iterable[Transcript], path: str | Path) -> None:
    with Path(path).open("w", encoding="utf-8") as target:
        for transcript in transcripts:
            target.write(json.dumps(transcript.to_dict(), ensure_ascii=False, sort_keys=True) + "\n")
