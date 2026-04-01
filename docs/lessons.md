# Lessons

Patterns learned from debugging sessions and user corrections. Review before starting complex work.

- Worktree requests: when user says "off main", default to new branch created from `main`, not a second checkout that locks `main`.
- Read `just --list` and justfile recipes before debugging infra. `just dev-setup`/`dev-teardown` toggle Arena↔leyline.
- `castSpellByName()` puts spell on stack — call `passPriority()` after to resolve.
- Check key types when wiring maps: forge card ID vs instanceId vs grpId. `snapshotKeywords()` keys by instanceId, not card.id.
- Suppress detekt on feature branches; refactor separately.
- Forge events must fire AFTER state mutation. `fireEvent()` before `changedCardKeywords.put()` = subscribers see stale state.
