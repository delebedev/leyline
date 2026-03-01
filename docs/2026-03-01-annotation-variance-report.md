# Annotation Variance Report
17 sessions, 3510 S-C payloads, 5304 GSMs, 8724 annotation instances, 50 distinct types
Status: 4 MISMATCH, 25 NOT IMPLEMENTED, 21 OK

## ModifiedLife (128 instances, 5 sessions)  -- MISMATCH
  Always:    {life}
  Our keys:  {delta}
  Missing:   {life}
  Extra:     {delta}
  Samples:   life=[-3, 3, -8]

  [1] session=09-33-05 gsId=121 msg=156 T6 Combat
      affectedIds=[2] details={life=-3}
      file: 000000561_MD_S-C_MATCH_DATA.bin
  [2] session=09-33-05 gsId=154 msg=203 T7 Main1
      affectedIds=[1] details={life=-3}
      file: 000000684_MD_S-C_MATCH_DATA.bin

## SyntheticEvent (77 instances, 5 sessions)  -- MISMATCH
  Always:    {type}
  Our keys:  (no detail keys)
  Missing:   {type}
  Samples:   type=[1]

  [1] session=09-33-05 gsId=121 msg=156 T6 Combat
      affectedIds=[2] details={type=1}
      file: 000000561_MD_S-C_MATCH_DATA.bin
  [2] session=09-33-05 gsId=154 msg=203 T7 Main1
      affectedIds=[1] details={type=1}
      file: 000000684_MD_S-C_MATCH_DATA.bin

## ModifiedToughness (14 instances, 3 sessions)  -- MISMATCH
  Always:    (no detail keys)
  Sometimes: effect_id (64%), count (35%), counter_type (35%), sourceAbilityGRPID (7%)
  Our keys:  {value}
  Extra:     {value}
  Samples:   count=[1], counter_type=[1], effect_id=[7004, 7007, 7003], sourceAbilityGRPID=[137]

  [1] session=09-33-05 gsId=107 msg=137 T6 Main1
      affectedIds=[289 -> grp:93848] details={effect_id=7004}
      file: 000000531_MD_S-C_MATCH_DATA.bin
  [2] session=09-33-05 gsId=109 msg=139 T6 Main1
      affectedIds=[308 -> grp:94156, 289 -> grp:94156] details={effect_id=7004}
      file: 000000531_MD_S-C_MATCH_DATA.bin

## ModifiedPower (14 instances, 3 sessions)  -- MISMATCH
  Always:    (no detail keys)
  Sometimes: effect_id (64%), count (35%), counter_type (35%), sourceAbilityGRPID (7%)
  Our keys:  {value}
  Extra:     {value}
  Samples:   count=[1], counter_type=[1], effect_id=[7004, 7007, 7003], sourceAbilityGRPID=[137]

  [1] session=09-33-05 gsId=107 msg=137 T6 Main1
      affectedIds=[289 -> grp:93848] details={effect_id=7004}
      file: 000000531_MD_S-C_MATCH_DATA.bin
  [2] session=09-33-05 gsId=109 msg=139 T6 Main1
      affectedIds=[308 -> grp:94156, 289 -> grp:94156] details={effect_id=7004}
      file: 000000531_MD_S-C_MATCH_DATA.bin

## ColorProduction (159 instances, 6 sessions)  -- NOT IMPLEMENTED
  Always:    {colors}
  Our keys:  --
  Samples:   colors=[4, 1, 2]

  [1] session=09-33-05 gsId=7 msg=21 ?
      affectedIds=[279 -> grp:96188] details={colors=4}
      file: 000000393_MD_S-C_MATCH_DATA.bin
  [2] session=09-33-05 gsId=24 msg=40 ?
      affectedIds=[281 -> grp:98591] details={colors=1}
      file: 000000398_MD_S-C_MATCH_DATA.bin

## TriggeringObject (48 instances, 6 sessions)  -- NOT IMPLEMENTED
  Always:    {source_zone}
  Sometimes: NEW_OBJECT_ID (4%)
  Our keys:  --
  Samples:   NEW_OBJECT_ID=[334], source_zone=[27, 28, 37]

  [1] session=09-33-05 gsId=82 msg=107 ?
      affectedIds=[294 -> grp:95039] details={source_zone=27}
      file: 000000474_MD_S-C_MATCH_DATA.bin
  [2] session=09-33-05 gsId=107 msg=137 T6 Main1
      affectedIds=[303 -> grp:93715] details={source_zone=28}
      file: 000000531_MD_S-C_MATCH_DATA.bin

## LayeredEffectCreated (43 instances, 5 sessions)  -- NOT IMPLEMENTED
  Always:    (no detail keys)
  Our keys:  --

  [1] session=09-33-05 gsId=44 msg=63 T3 Main1
      affectedIds=[7002] details={}
      file: 000000409_MD_S-C_MATCH_DATA.bin
  [2] session=09-33-05 gsId=44 msg=63 T3 Main1
      affectedIds=[7003] details={}
      file: 000000409_MD_S-C_MATCH_DATA.bin

## PlayerSelectingTargets (34 instances, 5 sessions)  -- NOT IMPLEMENTED
  Always:    (no detail keys)
  Our keys:  --

  [1] session=11-50-40 gsId=111 msg=153 T6 Main1
      affectedIds=[303 -> grp:176387] details={}
      file: 000000293_MD_S-C_MATCH_DATA.bin
  [2] session=11-50-40 gsId=111 msg=153 T6 Main1
      affectedIds=[303 -> grp:176387] details={}
      file: 000000294_MD_S-C_MATCH_DATA.bin

## PlayerSubmittedTargets (34 instances, 5 sessions)  -- NOT IMPLEMENTED
  Always:    (no detail keys)
  Our keys:  --

  [1] session=11-50-40 gsId=113 msg=157 ?
      affectedIds=[303 -> grp:176387] details={}
      file: 000000313_MD_S-C_MATCH_DATA.bin
  [2] session=11-50-40 gsId=113 msg=157 ?
      affectedIds=[303 -> grp:176387] details={}
      file: 000000314_MD_S-C_MATCH_DATA.bin

## TargetSpec (34 instances, 5 sessions)  -- NOT IMPLEMENTED
  Always:    {abilityGrpId, index, promptId, promptParameters}
  Our keys:  --
  Samples:   abilityGrpId=[176387, 170373, 1027], index=[1, 2], promptId=[1330, 12424, 1010], promptParameters=[303, 326, 365]

  [1] session=11-50-40 gsId=113 msg=157 ?
      affectedIds=[293 -> grp:75479] details={abilityGrpId=176387, index=1, promptParameters=303, promptId=1330}
      file: 000000313_MD_S-C_MATCH_DATA.bin
  [2] session=11-50-40 gsId=113 msg=157 ?
      affectedIds=[293 -> grp:75479] details={abilityGrpId=176387, index=1, promptParameters=303, promptId=1330}
      file: 000000314_MD_S-C_MATCH_DATA.bin

## LayeredEffect (19 instances, 5 sessions)  -- NOT IMPLEMENTED
  Always:    {effect_id}
  Sometimes: originalAbilityObjectZcid (21%), UniqueAbilityId (21%), grpid (21%), 310 (21%), MaxHandSize (10%), sourceAbilityGRPID (5%)
  Our keys:  --
  Samples:   310=[174405], MaxHandSize=[2147483647], UniqueAbilityId=[217, 183], effect_id=[7004, 7007, 7002], grpid=[6, 3], originalAbilityObjectZcid=[372, 310], sourceAbilityGRPID=[137]

  [1] session=09-33-05 gsId=107 msg=137 T6 Main1
      affectedIds=[289 -> grp:93848] details={effect_id=7004}
      file: 000000531_MD_S-C_MATCH_DATA.bin
  [2] session=09-33-05 gsId=109 msg=139 T6 Main1
      affectedIds=[308 -> grp:94156, 289 -> grp:94156] details={effect_id=7004}
      file: 000000531_MD_S-C_MATCH_DATA.bin

## DamagedThisTurn (18 instances, 3 sessions)  -- NOT IMPLEMENTED
  Always:    (no detail keys)
  Our keys:  --

  [1] session=14-15-29 gsId=126 msg=172 T6 Combat
      affectedIds=[355 -> grp:75450] details={}
      file: 000000445_MD_S-C_MATCH_DATA.bin
  [2] session=14-15-29 gsId=126 msg=172 T6 Combat
      affectedIds=[355 -> grp:75450] details={}
      file: 000000446_MD_S-C_MATCH_DATA.bin

## LayeredEffectDestroyed (15 instances, 4 sessions)  -- NOT IMPLEMENTED
  Always:    (no detail keys)
  Our keys:  --

  [1] session=09-33-05 gsId=180 msg=235 T8 Beginning
      affectedIds=[7007] details={}
      file: 000000714_MD_S-C_MATCH_DATA.bin
  [2] session=2026-03-01_00-11-05 gsId=213 msg=275 T10 Beginning
      affectedIds=[7003] details={}
      file: 000000439_MD_S-C_MATCH_DATA.bin

## InstanceRevealedToOpponent (10 instances, 2 sessions)  -- NOT IMPLEMENTED
  Always:    (no detail keys)
  Our keys:  --

  [1] session=2026-03-01_00-18-46 gsId=52 msg=77 ?
      affectedIds=[232 -> grp:75522] details={}
      file: 000000948_MD_S-C_MATCH_DATA.bin
  [2] session=2026-03-01_00-18-46 gsId=52 msg=77 ?
      affectedIds=[233 -> grp:75555] details={}
      file: 000000948_MD_S-C_MATCH_DATA.bin

## PowerToughnessModCreated (8 instances, 3 sessions)  -- NOT IMPLEMENTED
  Always:    {power, toughness}
  Our keys:  --
  Samples:   power=[1, 2], toughness=[1, 2]

  [1] session=09-33-05 gsId=163 msg=216 T7 Main1
      affectedIds=[335 -> grp:91865] details={power=1, toughness=1}
      file: 000000704_MD_S-C_MATCH_DATA.bin
  [2] session=09-33-05 gsId=198 msg=260 T8 Main1
      affectedIds=[289 -> grp:93848] details={power=1, toughness=1}
      file: 000000752_MD_S-C_MATCH_DATA.bin

## GainDesignation (8 instances, 2 sessions)  -- NOT IMPLEMENTED
  Always:    {DesignationType}
  Our keys:  --
  Samples:   DesignationType=[19, 20]

  [1] session=2026-03-01_00-18-46 gsId=100 msg=137 T5 Main1
      affectedIds=[310 -> grp:92196] details={DesignationType=19}
      file: 000001064_MD_S-C_MATCH_DATA.bin
  [2] session=2026-03-01_00-18-46 gsId=100 msg=137 T5 Main1
      affectedIds=[310 -> grp:92196] details={DesignationType=19}
      file: 000001065_MD_S-C_MATCH_DATA.bin

## Designation (8 instances, 2 sessions)  -- NOT IMPLEMENTED
  Always:    {DesignationType}
  Our keys:  --
  Samples:   DesignationType=[19, 20]

  [1] session=2026-03-01_00-18-46 gsId=100 msg=137 T5 Main1
      affectedIds=[310 -> grp:92196] details={DesignationType=19}
      file: 000001064_MD_S-C_MATCH_DATA.bin
  [2] session=2026-03-01_00-18-46 gsId=100 msg=137 T5 Main1
      affectedIds=[310 -> grp:92196] details={DesignationType=19}
      file: 000001065_MD_S-C_MATCH_DATA.bin

## AbilityExhausted (7 instances, 3 sessions)  -- NOT IMPLEMENTED
  Always:    {AbilityGrpId, UniqueAbilityId, UsesRemaining}
  Our keys:  --
  Samples:   AbilityGrpId=[137955, 137955,138314, 137955,138314,176655], UniqueAbilityId=[205, 215], UsesRemaining=[0]

  [1] session=09-33-05 gsId=139 msg=180 ?
      affectedIds=[294 -> grp:95039] details={UniqueAbilityId=205, UsesRemaining=0, AbilityGrpId=137955}
      file: 000000612_MD_S-C_MATCH_DATA.bin
  [2] session=09-33-05 gsId=145 msg=189 ?
      affectedIds=[294 -> grp:95039] details={UniqueAbilityId=205, UsesRemaining=0, AbilityGrpId=137955,138314}
      file: 000000639_MD_S-C_MATCH_DATA.bin

## AbilityWordActive (6 instances, 1 sessions)  -- NOT IMPLEMENTED
  Always:    {AbilityGrpId, AbilityWordName, threshold, value}
  Our keys:  --
  Samples:   AbilityGrpId=[192590], AbilityWordName=[NumberOfLessonCardsInYourGraveyard], threshold=[3], value=[1, 2, 3]

  [1] session=09-33-05 gsId=85 msg=112 ?
      affectedIds=[299 -> grp:97318] details={threshold=3, value=1, AbilityGrpId=192590, AbilityWordName=NumberOfLessonCardsInYourGraveyard}
      file: 000000502_MD_S-C_MATCH_DATA.bin
  [2] session=09-33-05 gsId=144 msg=187 ?
      affectedIds=[299 -> grp:97318] details={threshold=3, value=2, AbilityGrpId=192590, AbilityWordName=NumberOfLessonCardsInYourGraveyard}
      file: 000000633_MD_S-C_MATCH_DATA.bin

## Qualification (6 instances, 2 sessions)  -- NOT IMPLEMENTED
  Always:    {QualificationSubtype, QualificationType, SourceParent, grpid}
  Our keys:  --
  Samples:   QualificationSubtype=[0], QualificationType=[1, 32], SourceParent=[293, 331, 360], grpid=[20230, 62969]

  [1] session=11-50-40 gsId=90 msg=125 T5 Main1
      affectedIds=[2] details={SourceParent=293, grpid=20230, QualificationSubtype=0, QualificationType=1}
      file: 000000252_MD_S-C_MATCH_DATA.bin
  [2] session=11-50-40 gsId=90 msg=125 T5 Main1
      affectedIds=[2] details={SourceParent=293, grpid=20230, QualificationSubtype=0, QualificationType=1}
      file: 000000253_MD_S-C_MATCH_DATA.bin

## DisplayCardUnderCard (6 instances, 3 sessions)  -- NOT IMPLEMENTED
  Always:    {Disable, TemporaryZoneTransfer}
  Our keys:  --
  Samples:   Disable=[0], TemporaryZoneTransfer=[1]

  [1] session=11-50-40 gsId=115 msg=159 T6 Main1
      affectedIds=[304 -> grp:75479] details={TemporaryZoneTransfer=1, Disable=0}
      file: 000000313_MD_S-C_MATCH_DATA.bin
  [2] session=11-50-40 gsId=115 msg=159 T6 Main1
      affectedIds=[304 -> grp:75479] details={TemporaryZoneTransfer=1, Disable=0}
      file: 000000314_MD_S-C_MATCH_DATA.bin

## MiscContinuousEffect (6 instances, 3 sessions)  -- NOT IMPLEMENTED
  Always:    (no detail keys)
  Sometimes: extra_phases (66%), grpid (66%), MaxHandSize (33%), effect_id (33%)
  Our keys:  --
  Samples:   MaxHandSize=[2147483647], effect_id=[7002], extra_phases=[3], grpid=[100287]

  [1] session=14-15-29 gsId=46 msg=68 T3 Main1
      affectedIds=[1] details={MaxHandSize=2147483647, effect_id=7002}
      file: 000000240_MD_S-C_MATCH_DATA.bin
  [2] session=14-15-29 gsId=46 msg=68 T3 Main1
      affectedIds=[1] details={MaxHandSize=2147483647, effect_id=7002}
      file: 000000241_MD_S-C_MATCH_DATA.bin

## Counter (5 instances, 3 sessions)  -- NOT IMPLEMENTED
  Always:    {count, counter_type}
  Our keys:  --
  Samples:   count=[1], counter_type=[1]

  [1] session=09-33-05 gsId=198 msg=260 T8 Main1
      affectedIds=[289 -> grp:93848] details={count=1, counter_type=1}
      file: 000000752_MD_S-C_MATCH_DATA.bin
  [2] session=14-15-29 gsId=150 msg=204 T7 Main1
      affectedIds=[370 -> grp:92081] details={count=1, counter_type=1}
      file: 000000515_MD_S-C_MATCH_DATA.bin

## AddAbility (4 instances, 2 sessions)  -- NOT IMPLEMENTED
  Always:    {UniqueAbilityId, effect_id, grpid, originalAbilityObjectZcid}
  Our keys:  --
  Samples:   UniqueAbilityId=[217, 183], effect_id=[7005, 7002], grpid=[6, 3], originalAbilityObjectZcid=[372, 310]

  [1] session=14-15-29 gsId=146 msg=198 T7 Main1
      affectedIds=[370 -> grp:92081] details={originalAbilityObjectZcid=372, UniqueAbilityId=217, grpid=6, effect_id=7005}
      file: 000000497_MD_S-C_MATCH_DATA.bin
  [2] session=14-15-29 gsId=146 msg=198 T7 Main1
      affectedIds=[370 -> grp:92081] details={originalAbilityObjectZcid=372, UniqueAbilityId=217, grpid=6, effect_id=7005}
      file: 000000498_MD_S-C_MATCH_DATA.bin

## PredictedDirectDamage (4 instances, 2 sessions)  -- NOT IMPLEMENTED
  Always:    {value}
  Our keys:  --
  Samples:   value=[2]

  [1] session=2026-03-01_00-18-46 gsId=191 msg=260 ?
      affectedIds=[336 -> grp:58445] details={value=2}
      file: 000000488_MD_S-C_MATCH_DATA.bin
  [2] session=2026-03-01_00-18-46 gsId=191 msg=260 ?
      affectedIds=[336 -> grp:58445] details={value=2}
      file: 000000489_MD_S-C_MATCH_DATA.bin

## ModifiedCost (4 instances, 2 sessions)  -- NOT IMPLEMENTED
  Always:    {310, effect_id}
  Our keys:  --
  Samples:   310=[174405], effect_id=[7003]

  [1] session=2026-03-01_00-18-46 gsId=100 msg=137 T5 Main1
      affectedIds=[310 -> grp:92196] details={310=174405, effect_id=7003}
      file: 000001064_MD_S-C_MATCH_DATA.bin
  [2] session=2026-03-01_00-18-46 gsId=100 msg=137 T5 Main1
      affectedIds=[310 -> grp:92196] details={310=174405, effect_id=7003}
      file: 000001065_MD_S-C_MATCH_DATA.bin

## TextChange (4 instances, 2 sessions)  -- NOT IMPLEMENTED
  Always:    {310, effect_id}
  Our keys:  --
  Samples:   310=[174405], effect_id=[7003]

  [1] session=2026-03-01_00-18-46 gsId=100 msg=137 T5 Main1
      affectedIds=[310 -> grp:92196] details={310=174405, effect_id=7003}
      file: 000001064_MD_S-C_MATCH_DATA.bin
  [2] session=2026-03-01_00-18-46 gsId=100 msg=137 T5 Main1
      affectedIds=[310 -> grp:92196] details={310=174405, effect_id=7003}
      file: 000001065_MD_S-C_MATCH_DATA.bin

## ModifiedName (4 instances, 2 sessions)  -- NOT IMPLEMENTED
  Always:    {310, effect_id}
  Our keys:  --
  Samples:   310=[174405], effect_id=[7003]

  [1] session=2026-03-01_00-18-46 gsId=100 msg=137 T5 Main1
      affectedIds=[310 -> grp:92196] details={310=174405, effect_id=7003}
      file: 000001064_MD_S-C_MATCH_DATA.bin
  [2] session=2026-03-01_00-18-46 gsId=100 msg=137 T5 Main1
      affectedIds=[310 -> grp:92196] details={310=174405, effect_id=7003}
      file: 000001065_MD_S-C_MATCH_DATA.bin

## RemoveAbility (4 instances, 2 sessions)  -- NOT IMPLEMENTED
  Always:    {310, effect_id}
  Our keys:  --
  Samples:   310=[174405], effect_id=[7003]

  [1] session=2026-03-01_00-18-46 gsId=100 msg=137 T5 Main1
      affectedIds=[310 -> grp:92196] details={310=174405, effect_id=7003}
      file: 000001064_MD_S-C_MATCH_DATA.bin
  [2] session=2026-03-01_00-18-46 gsId=100 msg=137 T5 Main1
      affectedIds=[310 -> grp:92196] details={310=174405, effect_id=7003}
      file: 000001065_MD_S-C_MATCH_DATA.bin

## PhaseOrStepModified (1917 instances, 6 sessions)  -- OK
  Always:    {phase, step}
  Our keys:  {phase, step}
  Samples:   phase=[1, 2, 3], step=[1, 2, 0]

  [1] session=09-33-05 gsId=4 msg=15 T1 Beginning
      affectedIds=[2] details={phase=1, step=1}
      file: 000000377_MD_S-C_MATCH_DATA.bin
  [2] session=09-33-05 gsId=4 msg=15 T1 Beginning
      affectedIds=[2] details={phase=1, step=2}
      file: 000000377_MD_S-C_MATCH_DATA.bin

## TappedUntappedPermanent (1052 instances, 6 sessions)  -- OK
  Always:    {tapped}
  Our keys:  {tapped}
  Samples:   tapped=[1, 0]

  [1] session=09-33-05 gsId=42 msg=61 ?
      affectedIds=[283 -> grp:79736] details={tapped=1}
      file: 000000409_MD_S-C_MATCH_DATA.bin
  [2] session=09-33-05 gsId=42 msg=61 ?
      affectedIds=[279 -> grp:96188] details={tapped=1}
      file: 000000409_MD_S-C_MATCH_DATA.bin

## EnteredZoneThisTurn (715 instances, 6 sessions)  -- OK
  Always:    (no detail keys)
  Our keys:  (no detail keys)

  [1] session=09-33-05 gsId=7 msg=21 ?
      affectedIds=[279 -> grp:96188] details={}
      file: 000000393_MD_S-C_MATCH_DATA.bin
  [2] session=09-33-05 gsId=21 msg=35 T2 Beginning
      affectedIds=[280 -> grp:98591] details={}
      file: 000000393_MD_S-C_MATCH_DATA.bin

## UserActionTaken (713 instances, 6 sessions)  -- OK
  Always:    {abilityGrpId, actionType}
  Sometimes: alternativeGrpId (0%)
  Our keys:  {abilityGrpId, actionType}
  Samples:   abilityGrpId=[0, 1002, 1004], actionType=[3, 4, 1], alternativeGrpId=[350]

  [1] session=09-33-05 gsId=7 msg=21 ?
      affectedIds=[279 -> grp:96188] details={actionType=3, abilityGrpId=0}
      file: 000000393_MD_S-C_MATCH_DATA.bin
  [2] session=09-33-05 gsId=24 msg=40 ?
      affectedIds=[281 -> grp:98591] details={actionType=3, abilityGrpId=0}
      file: 000000398_MD_S-C_MATCH_DATA.bin

## ZoneTransfer (711 instances, 6 sessions)  -- OK
  Always:    {category, zone_dest, zone_src}
  Our keys:  {category, zone_dest, zone_src}
  Samples:   category=[PlayLand, Draw, CastSpell], zone_dest=[28, 31, 35], zone_src=[35, 32, 31]

  [1] session=09-33-05 gsId=7 msg=21 ?
      affectedIds=[279 -> grp:96188] details={zone_src=35, zone_dest=28, category=PlayLand}
      file: 000000393_MD_S-C_MATCH_DATA.bin
  [2] session=09-33-05 gsId=21 msg=35 T2 Beginning
      affectedIds=[280 -> grp:98591] details={zone_src=32, zone_dest=31, category=Draw}
      file: 000000393_MD_S-C_MATCH_DATA.bin

## ObjectIdChanged (600 instances, 6 sessions)  -- OK
  Always:    {new_id, orig_id}
  Our keys:  {new_id, orig_id}
  Samples:   new_id=[279, 280, 281], orig_id=[220, 166, 159]

  [1] session=09-33-05 gsId=7 msg=21 ?
      affectedIds=[220] details={orig_id=220, new_id=279}
      file: 000000393_MD_S-C_MATCH_DATA.bin
  [2] session=09-33-05 gsId=21 msg=35 T2 Beginning
      affectedIds=[166] details={orig_id=166, new_id=280}
      file: 000000393_MD_S-C_MATCH_DATA.bin

## AbilityInstanceCreated (476 instances, 6 sessions)  -- OK
  Always:    {source_zone}
  Our keys:  {source_zone}
  Samples:   source_zone=[28]

  [1] session=09-33-05 gsId=42 msg=61 ?
      affectedIds=[285] details={source_zone=28}
      file: 000000409_MD_S-C_MATCH_DATA.bin
  [2] session=09-33-05 gsId=42 msg=61 ?
      affectedIds=[286] details={source_zone=28}
      file: 000000409_MD_S-C_MATCH_DATA.bin

## AbilityInstanceDeleted (471 instances, 6 sessions)  -- OK
  Always:    (no detail keys)
  Our keys:  (no detail keys)

  [1] session=09-33-05 gsId=42 msg=61 ?
      affectedIds=[285] details={}
      file: 000000409_MD_S-C_MATCH_DATA.bin
  [2] session=09-33-05 gsId=42 msg=61 ?
      affectedIds=[286] details={}
      file: 000000409_MD_S-C_MATCH_DATA.bin

## ManaPaid (403 instances, 6 sessions)  -- OK
  Always:    {color, id}
  Our keys:  {color, id}
  Samples:   color=[2, 4, 1], id=[19, 20, 42]

  [1] session=09-33-05 gsId=42 msg=61 ?
      affectedIds=[284 -> grp:91660] details={id=19, color=2}
      file: 000000409_MD_S-C_MATCH_DATA.bin
  [2] session=09-33-05 gsId=42 msg=61 ?
      affectedIds=[284 -> grp:91660] details={id=20, color=4}
      file: 000000409_MD_S-C_MATCH_DATA.bin

## DamageDealt (218 instances, 6 sessions)  -- OK
  Always:    {damage, markDamage, type}
  Our keys:  {damage, markDamage, type}
  Samples:   damage=[3, 2, 4], markDamage=[1], type=[1, 2]

  [1] session=09-33-05 gsId=121 msg=156 T6 Combat
      affectedIds=[2] details={damage=3, type=1, markDamage=1}
      file: 000000561_MD_S-C_MATCH_DATA.bin
  [2] session=09-33-05 gsId=211 msg=280 T8 Combat
      affectedIds=[2] details={damage=2, type=1, markDamage=1}
      file: 000000782_MD_S-C_MATCH_DATA.bin

## ResolutionComplete (214 instances, 6 sessions)  -- OK
  Always:    {grpid}
  Our keys:  {grpid}
  Samples:   grpid=[91660, 93848, 173994]

  [1] session=09-33-05 gsId=44 msg=63 T3 Main1
      affectedIds=[284 -> grp:91660] details={grpid=91660}
      file: 000000409_MD_S-C_MATCH_DATA.bin
  [2] session=09-33-05 gsId=64 msg=86 T4 Main1
      affectedIds=[289 -> grp:93848] details={grpid=93848}
      file: 000000441_MD_S-C_MATCH_DATA.bin

## ResolutionStart (213 instances, 6 sessions)  -- OK
  Always:    {grpid}
  Our keys:  {grpid}
  Samples:   grpid=[91660, 93848, 173994]

  [1] session=09-33-05 gsId=44 msg=63 T3 Main1
      affectedIds=[284 -> grp:91660] details={grpid=91660}
      file: 000000409_MD_S-C_MATCH_DATA.bin
  [2] session=09-33-05 gsId=64 msg=86 T4 Main1
      affectedIds=[289 -> grp:93848] details={grpid=93848}
      file: 000000441_MD_S-C_MATCH_DATA.bin

## NewTurnStarted (170 instances, 6 sessions)  -- OK
  Always:    (no detail keys)
  Our keys:  (no detail keys)

  [1] session=09-33-05 gsId=2 msg=8 T0 None
      affectedIds=[1] details={}
      file: 000000254_MD_S-C_MATCH_DATA.bin
  [2] session=09-33-05 gsId=2 msg=8 T0 None
      affectedIds=[2] details={}
      file: 000000358_MD_S-C_MATCH_DATA.bin

## TokenCreated (48 instances, 3 sessions)  -- OK
  Always:    (no detail keys)
  Our keys:  (no detail keys)

  [1] session=09-33-05 gsId=109 msg=139 T6 Main1
      affectedIds=[308 -> grp:94156] details={}
      file: 000000531_MD_S-C_MATCH_DATA.bin
  [2] session=09-33-05 gsId=147 msg=191 T7 Main1
      affectedIds=[326 -> grp:95104] details={}
      file: 000000639_MD_S-C_MATCH_DATA.bin

## RevealedCardCreated (20 instances, 2 sessions)  -- OK
  Always:    (no detail keys)
  Our keys:  (no detail keys)

  [1] session=2026-03-01_00-18-46 gsId=52 msg=77 ?
      affectedIds=[299 -> grp:75522] details={}
      file: 000000948_MD_S-C_MATCH_DATA.bin
  [2] session=2026-03-01_00-18-46 gsId=52 msg=77 ?
      affectedIds=[300 -> grp:75555] details={}
      file: 000000948_MD_S-C_MATCH_DATA.bin

## RevealedCardDeleted (20 instances, 2 sessions)  -- OK
  Always:    (no detail keys)
  Our keys:  (no detail keys)

  [1] session=2026-03-01_00-18-46 gsId=69 msg=97 ?
      affectedIds=[302 -> grp:75555] details={}
      file: 000000964_MD_S-C_MATCH_DATA.bin
  [2] session=2026-03-01_00-18-46 gsId=69 msg=97 ?
      affectedIds=[302 -> grp:75555] details={}
      file: 000000965_MD_S-C_MATCH_DATA.bin

## LossOfGame (10 instances, 3 sessions)  -- OK
  Always:    {reason}
  Our keys:  {reason}
  Samples:   reason=[SBA_LifeTotal]

  [1] session=2026-03-01_00-11-05 gsId=263 msg=345 T11 Combat
      affectedIds=[2] details={reason=SBA_LifeTotal}
      file: 000000545_MD_S-C_MATCH_DATA.bin
  [2] session=2026-03-01_00-11-05 gsId=263 msg=345 T11 Combat
      affectedIds=[2] details={reason=SBA_LifeTotal}
      file: 000000546_MD_S-C_MATCH_DATA.bin

## TokenDeleted (9 instances, 3 sessions)  -- OK
  Always:    (no detail keys)
  Our keys:  (no detail keys)

  [1] session=09-33-05 gsId=160 msg=212 ?
      affectedIds=[339] details={}
      file: 000000701_MD_S-C_MATCH_DATA.bin
  [2] session=2026-03-01_00-18-46 gsId=169 msg=227 T8 Combat
      affectedIds=[329 -> grp:75510] details={}
      file: 000000383_MD_S-C_MATCH_DATA.bin

## CounterAdded (5 instances, 3 sessions)  -- OK
  Always:    {counter_type, transaction_amount}
  Our keys:  {counter_type, transaction_amount}
  Samples:   counter_type=[1], transaction_amount=[1]

  [1] session=09-33-05 gsId=198 msg=260 T8 Main1
      affectedIds=[289 -> grp:93848] details={counter_type=1, transaction_amount=1}
      file: 000000752_MD_S-C_MATCH_DATA.bin
  [2] session=14-15-29 gsId=150 msg=204 T7 Main1
      affectedIds=[370 -> grp:92081] details={counter_type=1, transaction_amount=1}
      file: 000000515_MD_S-C_MATCH_DATA.bin

## AttachmentCreated (4 instances, 1 sessions)  -- OK
  Always:    (no detail keys)
  Our keys:  (no detail keys)

  [1] session=14-15-29 gsId=110 msg=148 T6 Main1
      affectedIds=[355 -> grp:75450] details={}
      file: 000000311_MD_S-C_MATCH_DATA.bin
  [2] session=14-15-29 gsId=110 msg=148 T6 Main1
      affectedIds=[355 -> grp:75450] details={}
      file: 000000312_MD_S-C_MATCH_DATA.bin

## Attachment (4 instances, 1 sessions)  -- OK
  Always:    (no detail keys)
  Our keys:  (no detail keys)

  [1] session=14-15-29 gsId=110 msg=148 T6 Main1
      affectedIds=[355 -> grp:75450] details={}
      file: 000000311_MD_S-C_MATCH_DATA.bin
  [2] session=14-15-29 gsId=110 msg=148 T6 Main1
      affectedIds=[355 -> grp:75450] details={}
      file: 000000312_MD_S-C_MATCH_DATA.bin

