#!/usr/bin/env python3
"""Check the process-runtime and storage-admission boundaries without an Android SDK.

This is a source dependency guard, not a Kotlin parser or a substitute for review.
It checks source tokens, including Kotlin interpolation, after removing comments
and literal text. Compilation and review validate language constructs it cannot parse.
"""
from pathlib import Path
import re
import sys


ROOT = Path(__file__).resolve().parents[1]
SOURCE = Path("app/src/main/java/com/battlesbudz/jarvis/v2")
COMPOSITION = {
    "JarvisRuntime.kt",
    "MainActivity.kt",
    "JarvisAppComposition.kt",
    "voice/VoiceCallService.kt",
    # Android alarm/reboot entry point; ordinary actions still inject dependencies.
    "actions/WorkflowScheduleReceiver.kt",
}
ACTIVITY_ENTRY_POINTS = {
    # Camera foreground service owns the notification PendingIntent entry point.
    "voice/VideoCallService.kt",
    "MainActivity.kt",
    "voice/VoiceCallService.kt",
    "assistant/JarvisInteractionSessionService.kt",
}
RUNTIME = re.compile(r"\bJarvisRuntime\b")
ACTIVITY = re.compile(r"\bMainActivity\b")
CONVERSATION = re.compile(r"\bcom\.battlesbudz\.jarvis\.v2\.conversation\b|\bConversationWork\b")


def source_tokens(source, interpolation=True):
    """Retain code/interpolations and line offsets, including nested Kotlin comments."""
    output = list(source)

    def blank(start, end):
        for position in range(start, min(end, len(source))):
            if output[position] != "\n":
                output[position] = " "

    def quoted(start, delimiter):
        index = start + len(delimiter)
        chunk = start
        while index < len(source):
            if delimiter != '"""' and source[index] == "\\":
                index += 2
            elif source.startswith(delimiter, index):
                index += len(delimiter)
                blank(chunk, index)
                return index
            elif interpolation and delimiter != "'" and source.startswith("${", index):
                blank(chunk, index + 2)
                index = code(index + 2, braced=True)
                chunk = index
            elif interpolation and delimiter != "'" and source[index] == "$" and \
                    index + 1 < len(source) and (source[index + 1].isalpha() or source[index + 1] == "_"):
                blank(chunk, index + 1)
                index += 2
                while index < len(source) and (source[index].isalnum() or source[index] == "_"):
                    index += 1
                chunk = index
            else:
                index += 1
        blank(chunk, index)
        return index

    def code(index, braced=False):
        depth = 1 if braced else 0
        while index < len(source):
            start = index
            if source.startswith("//", index):
                end = source.find("\n", index)
                index = len(source) if end < 0 else end
                blank(start, index)
            elif source.startswith("/*", index):
                comment_depth = 1
                index += 2
                while index < len(source) and comment_depth:
                    if source.startswith("/*", index):
                        comment_depth += 1
                        index += 2
                    elif source.startswith("*/", index):
                        comment_depth -= 1
                        index += 2
                    else:
                        index += 1
                blank(start, index)
            elif source.startswith('"""', index):
                index = quoted(index, '"""')
            elif source[index] in ('"', "'"):
                index = quoted(index, source[index])
            elif braced and source[index] == "{":
                depth += 1
                index += 1
            elif braced and source[index] == "}":
                depth -= 1
                index += 1
                if depth == 0:
                    return index
            else:
                index += 1
        return index

    code(0)
    return "".join(output)


def find_violations(root):
    directory = root / SOURCE
    if not directory.is_dir():
        raise FileNotFoundError(f"Missing production source directory: {SOURCE}")
    violations = []
    for path in sorted(directory.rglob("*")):
        if not path.is_file() or path.suffix not in (".kt", ".java"):
            continue
        relative = path.relative_to(directory).as_posix()
        tokens = source_tokens(path.read_text(encoding="utf-8"), interpolation=path.suffix == ".kt")
        rules = []
        if relative not in COMPOSITION:
            rules.append((RUNTIME, "Inject a narrow dependency; process runtime access belongs to composition."))
        if relative not in ACTIVITY_ENTRY_POINTS:
            rules.append((ACTIVITY, "Activity access belongs to Android entry points."))
        if relative.startswith("ai/"):
            rules.append((CONVERSATION, "Model code must use shared admission, not conversation implementation state."))
        for pattern, message in rules:
            for match in pattern.finditer(tokens):
                line = tokens.count("\n", 0, match.start()) + 1
                violations.append((str(path.relative_to(root)), line, message))
    return violations


def run_check(root):
    try:
        violations = find_violations(root)
    except (OSError, UnicodeError) as error:
        print(f"Architecture check failed: {error}", file=sys.stderr)
        return 1
    for path, line, message in violations:
        print(f"{path}:{line}: {message}", file=sys.stderr)
    if violations:
        return 1
    print("Architecture dependency boundaries passed.")
    return 0


if __name__ == "__main__":
    sys.exit(run_check(ROOT))
