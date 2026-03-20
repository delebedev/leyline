"""Structured annotation extraction from raw GSM annotation dicts.

Flattens the KVP details format into plain dicts and tracks
persistent annotation lifecycle (created/destroyed across GSMs).
"""

from __future__ import annotations

from dataclasses import dataclass, field

# Persistent annotation types — present in every GSM while active,
# removed via diffDeletedPersistentAnnotationIds.
_PERSISTENT_TYPES = frozenset({
    "AnnotationType_Counter",
    "AnnotationType_LayeredEffect",
    "AnnotationType_Qualification",
    "AnnotationType_RevealedCardCreated",
    "AnnotationType_Designation",
    "AnnotationType_LoyaltyActivationsRemaining",
    "AnnotationType_CastingTimeOption",
    "AnnotationType_AddAbility",
})

_PREFIX = "AnnotationType_"


def flatten_details(details: list[dict]) -> dict:
    """Convert KVP detail array to flat dict.

    Input:  [{"key": "zone_src", "type": "..._int32", "valueInt32": [35]}]
    Output: {"zone_src": 35}

    Multi-element arrays are preserved as lists; single-element unwrapped.
    """
    result: dict = {}
    for kvp in details:
        key = kvp.get("key", "")
        if not key:
            continue
        if "valueInt32" in kvp:
            vals = kvp["valueInt32"]
            result[key] = vals[0] if len(vals) == 1 else vals
        elif "valueString" in kvp:
            vals = kvp["valueString"]
            result[key] = vals[0] if len(vals) == 1 else vals
        elif "valueUint32" in kvp:
            vals = kvp["valueUint32"]
            result[key] = vals[0] if len(vals) == 1 else vals
    return result


@dataclass
class Annotation:
    id: int | None = None
    types: list[str] = field(default_factory=list)
    affected_ids: list[int] = field(default_factory=list)
    affector_id: int | None = None
    details: dict = field(default_factory=dict)

    @classmethod
    def from_raw(cls, raw: dict) -> Annotation:
        return cls(
            id=raw.get("id"),
            types=list(raw.get("type", [])),
            affected_ids=list(raw.get("affectedIds", [])),
            affector_id=raw.get("affectorId"),
            details=flatten_details(raw.get("details", [])),
        )

    @property
    def is_persistent(self) -> bool:
        return any(t in _PERSISTENT_TYPES for t in self.types)

    def to_dict(self) -> dict:
        d: dict = {}
        if self.id is not None:
            d["id"] = self.id
        d["types"] = [t.removeprefix(_PREFIX) for t in self.types]
        d["affected_ids"] = self.affected_ids
        if self.affector_id is not None:
            d["affector_id"] = self.affector_id
        if self.details:
            d["details"] = self.details
        return d
