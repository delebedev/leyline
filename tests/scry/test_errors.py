from __future__ import annotations

from scry_lib.errors import parse_client_error, ClientError


class TestAnnotationException:
    def test_with_json_payload(self):
        line = (
            '[UnityCrossThreadLogger]Exception while parsing annotation. '
            '{"ExceptionType":"ArgumentException","Message":"bad annotation",'
            '"StackTraceString":"at Foo.Bar()","Id":42,"Type":[79,80]}'
        )
        err = parse_client_error(line)
        assert err is not None
        assert err.exception_type == "ArgumentException"
        assert err.message == "bad annotation"
        assert err.stack_trace == "at Foo.Bar()"
        assert err.annotation_id == 42
        assert err.annotation_type == [79, 80]

    def test_without_json(self):
        line = "[UnityCrossThreadLogger]Exception while parsing annotation."
        err = parse_client_error(line)
        assert err is not None
        assert err.exception_type == "AnnotationParseException"

    def test_suppressed_type_returns_none(self):
        line = (
            '[UnityCrossThreadLogger]Exception while parsing annotation. '
            '{"ExceptionType":"ArgumentNullException","Message":"x"}'
        )
        err = parse_client_error(line)
        assert err is None


class TestDeserializationFailure:
    def test_with_exception_detail(self):
        line = "[TaskLogger]Deserialization function failed for Wizards.Foo.Bar Exception: some error"
        err = parse_client_error(line)
        assert err is not None
        assert err.exception_type == "DeserializationException"
        assert "Wizards.Foo.Bar" in err.message
        assert "some error" in err.message

    def test_without_exception_detail(self):
        line = "[TaskLogger]Deserialization function failed for Wizards.Foo.Bar"
        err = parse_client_error(line)
        assert err is not None
        assert err.exception_type == "DeserializationException"
        assert "Wizards.Foo.Bar" in err.message


class TestBareException:
    def test_simple(self):
        line = "KeyNotFoundException: the key was not found"
        err = parse_client_error(line)
        assert err is not None
        assert err.exception_type == "KeyNotFoundException"
        assert err.message == "the key was not found"

    def test_with_logger_prefix(self):
        line = "[UnityCrossThreadLogger]InvalidOperationException: collection was modified"
        err = parse_client_error(line)
        assert err is not None
        assert err.exception_type == "InvalidOperationException"

    def test_suppressed_null_ref(self):
        line = "NullReferenceException: Object reference not set"
        assert parse_client_error(line) is None

    def test_suppressed_argument_null(self):
        line = "ArgumentNullException: Value cannot be null"
        assert parse_client_error(line) is None

    def test_suppressed_argument_out_of_range(self):
        line = "ArgumentOutOfRangeException: Index was out of range"
        assert parse_client_error(line) is None


class TestNoMatch:
    def test_normal_line(self):
        assert parse_client_error("Initialize engine version: 2022.3.62f2") is None

    def test_gre_line(self):
        assert parse_client_error('{ "greToClientEvent": {} }') is None

    def test_line_with_exception_in_text(self):
        # "Exception" appears but not as a type name
        assert parse_client_error("No exceptions found in this run") is None
