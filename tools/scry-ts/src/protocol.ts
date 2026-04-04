/**
 * Arena GRE protocol types — typed from Player.log JSON.
 *
 * These match the protobuf-over-JSON wire format. Fields are optional
 * because Diff GSMs omit unchanged fields (proto3 semantics: absent = default).
 *
 * Stable: Arena maintains backward compatibility. New fields get added,
 * existing fields don't change shape. New annotation types and detail keys
 * appear with new mechanics — those stay as strings, not typed enums.
 */

// --- Top-level Game State Message ---

export interface RawGameStateMessage {
  type?: string; // "GameStateType_Full" | "GameStateType_Diff"
  gameStateId?: number;
  prevGameStateId?: number;
  gameInfo?: RawGameInfo;
  turnInfo?: RawTurnInfo;
  players?: RawPlayer[];
  zones?: RawZone[];
  gameObjects?: RawGameObject[];
  annotations?: RawAnnotation[];
  persistentAnnotations?: RawAnnotation[];
  diffDeletedInstanceIds?: number[];
  diffDeletedPersistentAnnotationIds?: number[];
  actions?: RawAction[];
  timers?: any[];
  update?: string; // "GameStateUpdate_Send" | "SendHiFi" | "SendAndRecord"
  pendingMessageCount?: number;
}

// --- Turn Info ---

export interface RawTurnInfo {
  turnNumber?: number;
  activePlayer?: number;
  priorityPlayer?: number;
  decisionPlayer?: number;
  phase?: string; // "Phase_Beginning", "Phase_Main", "Phase_Combat", "Phase_Ending"
  step?: string; // "Step_Upkeep", "Step_Draw", "Step_DeclareAttack", etc.
  nextPhase?: string;
  nextStep?: string;
}

// --- Player ---

export interface RawPlayer {
  systemSeatNumber?: number;
  lifeTotal?: number;
  maxHandSize?: number;
  teamId?: number;
  controllerSeatId?: number;
  controllerType?: string;
  pendingMessageType?: string;
  startingLifeTotal?: number;
  timerIds?: number[];
  status?: string;
}

// --- Zone ---

export interface RawZone {
  zoneId: number;
  type?: string; // "ZoneType_Hand", "ZoneType_Battlefield", etc.
  visibility?: string;
  ownerSeatId?: number;
  objectInstanceIds?: number[];
}

// --- Game Object ---

export interface RawGameObject {
  instanceId: number;
  grpId: number;
  type?: string; // "GameObjectType_Card" | "GameObjectType_Ability" | "GameObjectType_RevealedCard" | "GameObjectType_TriggerHolder"
  zoneId?: number;
  visibility?: string;
  ownerSeatId?: number;
  controllerSeatId?: number;
  superTypes?: string[];
  cardTypes?: string[];
  subtypes?: string[];
  color?: string[];
  power?: { value: number };
  toughness?: { value: number };
  loyalty?: { value: number };
  name?: number; // Arena localization ID
  overlayGrpId?: number;
  isTapped?: boolean;
  isFacedown?: boolean;
  hasSummoningSickness?: boolean;
  damage?: number;
  attackState?: string;
  blockState?: string;
  viewers?: number[]; // seat IDs that can see this (revealed-to-opponent)
  uniqueAbilities?: RawAbilityRef[];
  objectSourceGrpId?: number; // for Ability objects: source card grpId
  parentId?: number; // for Ability objects: source permanent instanceId
}

export interface RawAbilityRef {
  id?: number;
  grpId?: number;
}

// --- Annotation ---

export interface RawAnnotation {
  id?: number;
  affectorId?: number;
  affectedIds?: number[];
  type?: string[]; // ["AnnotationType_DamageDealt", ...] — multiple types per annotation
  details?: RawDetail[];
}

export interface RawDetail {
  key: string;
  type?: string; // "KeyValuePairValueType_int32", etc.
  valueInt32?: number[];
  valueUint32?: number[];
  valueString?: string[];
}

// --- Action ---

export interface RawAction {
  seatId?: number;
  action?: RawActionInner;
  // Some GSMs flatten action fields at this level
  actionType?: string;
  instanceId?: number;
  grpId?: number;
  facetId?: number;
  manaCost?: RawManaCost[];
  shouldStop?: boolean;
}

export interface RawActionInner {
  actionType?: string;
  instanceId?: number;
  grpId?: number;
  facetId?: number;
  manaCost?: RawManaCost[];
  shouldStop?: boolean;
  autoTapSolution?: any;
}

export interface RawManaCost {
  color?: string[]; // ["ManaColor_Blue"], ["ManaColor_Generic"]
  count?: number;
  abilityGrpId?: number;
}

// --- Game Info ---

export interface RawGameInfo {
  matchID?: string;
  gameNumber?: number;
  stage?: string;
  type?: string; // "GameType_Duel"
  variant?: string; // "GameVariant_Normal" | "GameVariant_Brawl"
  matchState?: string;
  matchWinCondition?: string;
  superFormat?: string;
  mulliganType?: string;
  freeMulliganCount?: number;
  deckConstraintInfo?: {
    minDeckSize?: number;
    maxDeckSize?: number;
    maxSideboardSize?: number;
    minCommanderSize?: number;
    maxCommanderSize?: number;
  };
  results?: RawResult[];
  matchState_?: { resultList?: RawResult[] }; // alternate location
}

export interface RawResult {
  scope?: string;
  result?: string;
  resultType?: string;
  winningTeamId?: number;
}
