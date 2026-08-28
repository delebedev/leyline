package leyline.bridge.coord

import leyline.game.PendingPromptCut
import leyline.game.PlaybackTerminalFailure
import leyline.game.PromptMaterializationDiagnostic
import leyline.game.PromptTerminalEvidence

internal val PlaybackTerminalFailure.pendingPromptCut: PendingPromptCut<*>?
    get() = (promptEvidence as? PromptTerminalEvidence.Pending)?.cut

internal val PlaybackTerminalFailure.promptMaterializationDiagnostic: PromptMaterializationDiagnostic<*>?
    get() = (promptEvidence as? PromptTerminalEvidence.Materialization)?.diagnostic
