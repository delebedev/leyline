package leyline.conformance

import forge.game.ability.AbilityKey
import forge.game.zone.ZoneType
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldNotBe
import leyline.ConformanceTag
import leyline.bridge.ForgeCardId

/**
 * InstanceId reallocation on zone transfer and Limbo zone accumulation.
 *
 * Real server allocates a new instanceId every time a card changes zones
 * (except Stack→Battlefield on resolve). The old instanceId is retired to Limbo
 * via objectInstanceIds in the Limbo ZoneInfo — no GameObjectInfo is emitted for it.
 * Limbo is monotonically growing — retired IDs are never removed.
 */
class InstanceIdReallocTest :
    FunSpec({

        tags(ConformanceTag)

        val base = ConformanceTestBase()
        beforeSpec { base.initCardDatabase() }
        afterEach { base.tearDown() }

        // PlayLand realloc test moved to LandManaTest

        test("zone transfer reallocs instanceId (Destroy)") {
            val (b, game, counter) = base.startWithBoard { _, human, _ ->
                base.addCard("Grizzly Bears", human, ZoneType.Battlefield)
            }

            val creature = game.humanPlayer.getZone(ZoneType.Battlefield).cards.first { it.isCreature }
            val origInstanceId = b.getOrAllocInstanceId(ForgeCardId(creature.id))
            val cardId = creature.id

            base.captureAfterAction(b, game, counter) {
                game.action.destroy(creature, null, false, AbilityKey.newMap())
            }
            val newInstanceId = b.getOrAllocInstanceId(ForgeCardId(cardId))

            origInstanceId shouldNotBe newInstanceId
            b.getLimboInstanceIds().shouldContain(origInstanceId)
        }

        // "Resolve keeps same instanceId" moved to StackCastResolveTest
        // "Limbo grows" moved to LandManaTest
    })
