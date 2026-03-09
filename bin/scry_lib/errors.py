"""Client error extraction from Player.log lines.

Parses exception lines: annotation parse failures, deserialization errors,
and bare exceptions. Suppresses noisy types (NullRef, ArgumentNull, etc.).
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass, field

_ANNOTATION_EXCEPTION = re.compile(
    r"\[UnityCrossThreadLogger\]Exception while parsing annotation\.\s*(\{.+)?$"
)

_DESERIALIZATION_FAILED = re.compile(
    r"\[TaskLogger\]Deserialization function failed for\s+(\S+).*Exception:\s*(.+)"
)

_DESERIALIZATION_SHORT = re.compile(
    r"\[TaskLogger\]Deserialization function failed for\s+(\S+)"
)

_BARE_EXCEPTION = re.compile(
    r"(\w+(?:Exception|Error)):\s+(.+)"
)

_UNITY_ASSERTION = re.compile(
    r"Assertion failed on expression: '(.+)'"
)

_SUPPRESSED = frozenset({
    "ArgumentNullException",
    "ArgumentOutOfRangeException",
    "NullReferenceException",
    "IndexOutOfRangeException",
})


@dataclass
class ClientError:
    exception_type: str
    message: str
    stack_trace: str = ""
    annotation_id: int | None = None
    annotation_type: list[int] | None = None
    raw: str = ""


def parse_client_error(line: str) -> ClientError | None:
    """Try to extract a structured client error from a log line.

    Returns None if the line isn't an error or is a suppressed type.
    """
    if (
        "Exception" not in line
        and "Deserialization" not in line
        and "Error:" not in line
        and "Assertion failed" not in line
    ):
        return None

    # 1. Annotation parse exception (richest)
    m = _ANNOTATION_EXCEPTION.search(line)
    if m:
        payload = m.group(1)
        if payload:
            try:
                parsed = json.loads(payload)
            except json.JSONDecodeError:
                parsed = {}
            ex_type = parsed.get("ExceptionType", "UnknownException")
            if ex_type in _SUPPRESSED:
                return None
            return ClientError(
                exception_type=ex_type,
                message=parsed.get("Message", "Unknown error"),
                stack_trace=parsed.get("StackTraceString", "").replace("\\n", "\n"),
                annotation_id=parsed.get("Id"),
                annotation_type=parsed.get("Type"),
                raw=line,
            )
        return ClientError(
            exception_type="AnnotationParseException",
            message="Exception while parsing annotation (no details)",
            raw=line,
        )

    # 2. Deserialization failure
    m = _DESERIALIZATION_FAILED.search(line)
    if m:
        return ClientError(
            exception_type="DeserializationException",
            message=f"Failed to deserialize {m.group(1)}: {m.group(2)}",
            raw=line,
        )
    m = _DESERIALIZATION_SHORT.search(line)
    if m:
        return ClientError(
            exception_type="DeserializationException",
            message=f"Failed to deserialize {m.group(1)}",
            raw=line,
        )

    # 3. Unity assertion failure
    m = _UNITY_ASSERTION.search(line)
    if m:
        return ClientError(
            exception_type="AssertionFailure",
            message=f"Assertion failed: {m.group(1)}",
            raw=line,
        )

    # 4. Bare exception or error
    m = _BARE_EXCEPTION.search(line)
    if m:
        ex_type = m.group(1)
        if ex_type in _SUPPRESSED:
            return None
        return ClientError(
            exception_type=ex_type,
            message=m.group(2),
            raw=line,
        )

    return None
