from __future__ import annotations

from scry_lib.annotations import Annotation, flatten_details


class TestFlattenDetails:
    def test_int32_value(self):
        details = [{"key": "zone_src", "type": "KeyValuePairValueType_int32", "valueInt32": [35]}]
        assert flatten_details(details) == {"zone_src": 35}

    def test_string_value(self):
        details = [{"key": "category", "type": "KeyValuePairValueType_string", "valueString": ["PlayLand"]}]
        assert flatten_details(details) == {"category": "PlayLand"}

    def test_multiple_keys(self):
        details = [
            {"key": "zone_src", "type": "KeyValuePairValueType_int32", "valueInt32": [35]},
            {"key": "zone_dest", "type": "KeyValuePairValueType_int32", "valueInt32": [28]},
            {"key": "category", "type": "KeyValuePairValueType_string", "valueString": ["PlayLand"]},
        ]
        result = flatten_details(details)
        assert result == {"zone_src": 35, "zone_dest": 28, "category": "PlayLand"}

    def test_empty(self):
        assert flatten_details([]) == {}

    def test_multi_value_int_keeps_list(self):
        details = [{"key": "ids", "type": "KeyValuePairValueType_int32", "valueInt32": [1, 2, 3]}]
        assert flatten_details(details) == {"ids": [1, 2, 3]}

    def test_unknown_type_skipped(self):
        details = [{"key": "x", "type": "KeyValuePairValueType_unknown"}]
        assert flatten_details(details) == {}


class TestAnnotation:
    def test_from_raw_zone_transfer(self):
        raw = {
            "id": 49,
            "affectorId": 1,
            "affectedIds": [217],
            "type": ["AnnotationType_ZoneTransfer"],
            "details": [
                {"key": "zone_src", "type": "KeyValuePairValueType_int32", "valueInt32": [35]},
                {"key": "zone_dest", "type": "KeyValuePairValueType_int32", "valueInt32": [28]},
                {"key": "category", "type": "KeyValuePairValueType_string", "valueString": ["PlayLand"]},
            ],
        }
        a = Annotation.from_raw(raw)
        assert a.id == 49
        assert a.types == ["AnnotationType_ZoneTransfer"]
        assert a.affected_ids == [217]
        assert a.affector_id == 1
        assert a.details == {"zone_src": 35, "zone_dest": 28, "category": "PlayLand"}

    def test_from_raw_multi_type(self):
        raw = {
            "id": 100,
            "affectedIds": [335],
            "type": ["AnnotationType_ModifiedType", "AnnotationType_LayeredEffect"],
            "details": [
                {"key": "effect_id", "type": "KeyValuePairValueType_int32", "valueInt32": [7007]},
            ],
        }
        a = Annotation.from_raw(raw)
        assert a.types == ["AnnotationType_ModifiedType", "AnnotationType_LayeredEffect"]
        assert a.details == {"effect_id": 7007}
        assert a.affector_id is None

    def test_from_raw_no_details(self):
        raw = {
            "id": 5,
            "affectedIds": [100, 200],
            "type": ["AnnotationType_NewTurnStarted"],
        }
        a = Annotation.from_raw(raw)
        assert a.details == {}
        assert a.affected_ids == [100, 200]

    def test_from_raw_no_id(self):
        raw = {
            "affectedIds": [1],
            "type": ["AnnotationType_PhaseOrStepModified"],
        }
        a = Annotation.from_raw(raw)
        assert a.id is None

    def test_to_dict(self):
        a = Annotation(
            id=49,
            types=["AnnotationType_ZoneTransfer"],
            affected_ids=[217],
            affector_id=1,
            details={"zone_src": 35, "zone_dest": 28, "category": "PlayLand"},
        )
        d = a.to_dict()
        assert d["id"] == 49
        assert d["types"] == ["ZoneTransfer"]
        assert d["affected_ids"] == [217]
        assert d["affector_id"] == 1
        assert d["details"]["category"] == "PlayLand"

    def test_to_dict_strips_prefix(self):
        a = Annotation(
            id=1,
            types=["AnnotationType_LayeredEffectCreated", "AnnotationType_LayeredEffect"],
            affected_ids=[7007],
        )
        d = a.to_dict()
        assert d["types"] == ["LayeredEffectCreated", "LayeredEffect"]

    def test_is_persistent(self):
        persistent_types = [
            "AnnotationType_LayeredEffect",
            "AnnotationType_Counter",
            "AnnotationType_Qualification",
        ]
        for t in persistent_types:
            a = Annotation(id=1, types=[t], affected_ids=[1])
            assert a.is_persistent, f"{t} should be persistent"

    def test_transient_types(self):
        a = Annotation(id=1, types=["AnnotationType_ZoneTransfer"], affected_ids=[1])
        assert not a.is_persistent
