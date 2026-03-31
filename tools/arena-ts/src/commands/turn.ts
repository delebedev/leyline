// src/commands/turn.ts
// Structured turn state for agent decision-making.

import { liveState } from "../gamestate";

export async function turnCommand(args: string[]): Promise<void> {
  if (args.includes("--help") || args.includes("-h")) {
    console.log("arena turn — show current turn state\n");
    console.log("Usage: arena turn [--json]");
    return;
  }

  const json = args.includes("--json");
  const state = await liveState();
  if (!state) {
    console.error("no active game");
    process.exitCode = 1;
    return;
  }

  if (json) {
    console.log(JSON.stringify(state, null, 2));
    return;
  }

  const ti = state.turnInfo;
  const phase = ti.step ? `${ti.phase}/${ti.step}` : ti.phase;
  const whose = ti.isOurTurn ? "Your turn" : "Opp turn";
  console.log(`T${ti.turn} ${phase} | ${whose} | Life ${state.ourLife}-${state.oppLife}`);

  const handNames = state.hand.map(c => c.name);
  console.log(`Hand (${state.handCount}): ${handNames.join(", ") || "(empty)"}`);

  if (state.lands.length > 0)
    console.log(`Can play land: ${state.lands.map(a => a.name).join(", ")}`);
  if (state.casts.length > 0)
    console.log(`Can cast: ${state.casts.map(a => a.name).join(", ")}`);
  if (state.otherActions.length > 0)
    console.log(`Other: ${state.otherActions.map(a => `${a.type}: ${a.name}`).join(", ")}`);
  if (state.lands.length === 0 && state.casts.length === 0 && state.otherActions.length === 0)
    console.log(ti.isOurTurn ? "No actions — pass priority" : "Opponent's turn — pass priority");
}
