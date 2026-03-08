from __future__ import annotations

from scry_lib.errors import ClientError
from scry_lib.parser import GREBlock, parse_log


class TestParseLog:
    def test_yields_gre_blocks(self):
        lines = [
            "[UnityCrossThreadLogger]08/03/2026 10:21:24: Match to abc-123: GreToClientEvent",
            '{ "greToClientEvent": { "greToClientMessages": [{"type": "GREMessageType_GameStateMessage"}] } }',
        ]
        events = list(parse_log(lines))
        assert len(events) == 1
        assert isinstance(events[0], GREBlock)

    def test_yields_client_errors(self):
        lines = [
            "KeyNotFoundException: the key was not found",
        ]
        events = list(parse_log(lines))
        assert len(events) == 1
        assert isinstance(events[0], ClientError)

    def test_mixed_stream(self):
        lines = [
            "some noise",
            "KeyNotFoundException: bad key",
            "[UnityCrossThreadLogger]08/03/2026 10:21:24: Match to abc-123: GreToClientEvent",
            '{ "greToClientEvent": { "greToClientMessages": [{"type": "GREMessageType_ConnectResp"}] } }',
            "more noise",
            "InvalidOperationException: collection modified",
        ]
        events = list(parse_log(lines))
        types = [type(e).__name__ for e in events]
        assert types == ["ClientError", "GREBlock", "ClientError"]

    def test_suppressed_errors_not_yielded(self):
        lines = [
            "NullReferenceException: Object reference not set",
            "ArgumentNullException: Value cannot be null",
        ]
        events = list(parse_log(lines))
        assert events == []

    def test_annotation_error_in_stream(self):
        lines = [
            '[UnityCrossThreadLogger]Exception while parsing annotation. {"ExceptionType":"ArgumentException","Message":"bad"}',
        ]
        events = list(parse_log(lines))
        assert len(events) == 1
        assert isinstance(events[0], ClientError)
        assert events[0].exception_type == "ArgumentException"
