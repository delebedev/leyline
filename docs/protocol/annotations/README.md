---
summary: "Annotation family overview: transient event rows and persistent state rows carried inside a GSM."
read_when:
  - "finding the page or role of a GRE annotation type"
  - "emitting or consuming annotations on either rail"
---
# Annotations

Annotations explain what changed in a GSM and what durable state applies to
its objects. `annotations` carries one-shot event rows; `persistentAnnotations`
carries state that is repeated until it is deleted through
`diffDeletedPersistentAnnotationIds`. A type can appear on either rail or both;
the rail belongs to each emission.

| Annotation | Role |
|---|---|
| [ReplacementEffect](ReplacementEffect.md) | Links a replacement ability or lifecycle to the object whose event it modifies. |
