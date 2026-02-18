package forge.nexus.conformance

import forge.nexus.game.ZoneIds
import org.testng.Assert.*
import org.testng.annotations.Test
import java.io.File

/**
 * Tests for RecordingDecoder — validates it can read real Arena recordings
 * and produce correct structured summaries.
 *
 * These tests run against the actual .bin recording files in test resources.
 * They validate the decoder output against known ground truth from the
 * action traces (docs/plans/2026-02-17-recording-analysis-deep-dive.md).
 */
@Test(groups = ["conformance"])
class RecordingDecoderTest {

    private val onPlayDir = File("src/test/resources/recordings/20260217-234330-on-play")
    private val onDrawDir = File("src/test/resources/recordings/20260217-234330-on-draw-sparky-two-creatures-player-attacked")

    @Test(description = "Decoder produces expected message count for on-play recording")
    fun onPlayMessageCount() {
        val messages = RecordingDecoder.decodeDirectory(onPlayDir)
        assertEquals(messages.size, 31, "on-play recording should have 31 GRE messages")
    }

    @Test(description = "Decoder produces expected message count for on-draw recording")
    fun onDrawMessageCount() {
        val messages = RecordingDecoder.decodeDirectory(onDrawDir)
        assertEquals(messages.size, 155, "on-draw recording should have 155 GRE messages")
    }

    // --- on-play recording: structural assertions ---

    @Test(description = "on-play: first message is UIMessage")
    fun onPlayFirstMessageIsUiMessage() {
        val messages = RecordingDecoder.decodeDirectory(onPlayDir)
        assertEquals(messages[0].greType, "Uimessage", "First message should be UIMessage")
        assertEquals(messages[0].systemSeatIds, listOf(2), "UIMessage should target seat 2")
    }

    @Test(description = "on-play: mulligan request present")
    fun onPlayHasMulliganReq() {
        val messages = RecordingDecoder.decodeDirectory(onPlayDir)
        val mulligan = messages.firstOrNull { it.hasMulliganReq }
        assertNotNull(mulligan, "Should have a MulliganReq message")
        assertEquals(mulligan!!.gsId, 3, "MulliganReq should be at gsId=3")
    }

    @Test(description = "on-play: play land produces ObjectIdChanged + ZoneTransfer + UserActionTaken")
    fun onPlayLandAnnotations() {
        val messages = RecordingDecoder.decodeDirectory(onPlayDir)
        val playLand = messages.firstOrNull { msg ->
            msg.annotations.any { a -> "ZoneTransfer" in a.types && a.details["category"] == "PlayLand" }
        }
        assertNotNull(playLand, "Should have a PlayLand ZoneTransfer annotation")

        val anns = playLand!!.annotations
        assertTrue(anns.any { "ObjectIdChanged" in it.types }, "PlayLand should have ObjectIdChanged")
        assertTrue(anns.any { "ZoneTransfer" in it.types }, "PlayLand should have ZoneTransfer")
        assertTrue(anns.any { "UserActionTaken" in it.types }, "PlayLand should have UserActionTaken")

        // ObjectIdChanged: orig_id=161, new_id=279
        val oic = anns.first { "ObjectIdChanged" in it.types }
        assertEquals(oic.details["orig_id"], 161, "ObjectIdChanged orig_id should be 161")
        assertEquals(oic.details["new_id"], 279, "ObjectIdChanged new_id should be 279")
    }

    @Test(description = "on-play: dual delivery — gsId=7 sent to both seats")
    fun onPlayDualDelivery() {
        val messages = RecordingDecoder.decodeDirectory(onPlayDir)
        val gs7 = messages.filter { it.gsId == 7 && it.greType == "GameStateMessage" }
        assertEquals(gs7.size, 2, "gsId=7 should have 2 GSM deliveries (one per seat)")

        val seats = gs7.map { it.systemSeatIds.first() }.toSet()
        assertEquals(seats, setOf(1, 2), "gsId=7 should be delivered to seat 1 and seat 2")
    }

    @Test(description = "on-play: seat 2 does not see Private Limbo objects from seat 1's perspective")
    fun onPlayPerSeatVisibility() {
        val messages = RecordingDecoder.decodeDirectory(onPlayDir)
        val gs7seat1 = messages.first { it.gsId == 7 && it.greType == "GameStateMessage" && 1 in it.systemSeatIds }
        val gs7seat2 = messages.first { it.gsId == 7 && it.greType == "GameStateMessage" && 2 in it.systemSeatIds }

        // Seat 1 (owner) should see the old Limbo object
        val seat1LimboObj = gs7seat1.objects.firstOrNull { it.instanceId == 161 && it.zoneId == ZoneIds.LIMBO }
        assertNotNull(seat1LimboObj, "Seat 1 should see instanceId 161 in Limbo")

        // Seat 2 should NOT see the old Limbo object (not the owner)
        val seat2LimboObj = gs7seat2.objects.firstOrNull { it.instanceId == 161 }
        assertNull(seat2LimboObj, "Seat 2 should NOT see instanceId 161 (Private to seat 1)")
    }

    @Test(description = "on-play: game ends with PendingLoss status")
    fun onPlayGameEnd() {
        val messages = RecordingDecoder.decodeDirectory(onPlayDir)
        val pending = messages.firstOrNull { msg ->
            msg.players.any { it.status == "PendingLoss" }
        }
        assertNotNull(pending, "Should have a PendingLoss status message")
        assertEquals(pending!!.players.first { it.status == "PendingLoss" }.seat, 1, "Seat 1 (Sparky) should have PendingLoss")

        val intermission = messages.firstOrNull { it.hasIntermissionReq }
        assertNotNull(intermission, "Should have IntermissionReq at game end")
    }

    // --- on-draw recording: structural assertions ---

    @Test(description = "on-draw: cast creature produces CastSpell + mana ability chain")
    fun onDrawCastCreature() {
        val messages = RecordingDecoder.decodeDirectory(onDrawDir)
        val castBird = messages.firstOrNull { msg ->
            msg.annotations.any { a -> "ZoneTransfer" in a.types && a.details["category"] == "CastSpell" } &&
                msg.objects.any { it.grpId == 75485 } // Bird
        }
        assertNotNull(castBird, "Should have CastSpell for Bird (grpId=75485)")

        val anns = castBird!!.annotations
        assertTrue(anns.any { "ObjectIdChanged" in it.types }, "CastSpell should have ObjectIdChanged")
        assertTrue(anns.any { "AbilityInstanceCreated" in it.types }, "CastSpell should have mana ability created")
        assertTrue(anns.any { "TappedUntappedPermanent" in it.types }, "CastSpell should have land tap")
        assertTrue(anns.any { "ManaPaid" in it.types }, "CastSpell should have ManaPaid")
        assertTrue(anns.any { "AbilityInstanceDeleted" in it.types }, "CastSpell should have mana ability deleted")
        assertTrue(anns.any { "UserActionTaken" in it.types }, "CastSpell should have UserActionTaken")
    }

    @Test(description = "on-draw: creature resolves from Stack to Battlefield with summoning sickness")
    fun onDrawCreatureResolve() {
        val messages = RecordingDecoder.decodeDirectory(onDrawDir)
        val resolve = messages.firstOrNull { msg ->
            msg.annotations.any { a -> "ZoneTransfer" in a.types && a.details["category"] == "Resolve" } &&
                msg.objects.any { it.grpId == 75485 && it.hasSummoningSickness }
        }
        assertNotNull(resolve, "Should have Resolve with summoning sickness on Bird")
        val bird = resolve!!.objects.first { it.grpId == 75485 }
        assertTrue(bird.hasSummoningSickness, "Bird should have summoning sickness on resolve")
        assertEquals(bird.power, 1, "Bird should have power 1")
        assertEquals(bird.toughness, 1, "Bird should have toughness 1")
    }

    @Test(description = "on-draw: Aura resolve creates Attachment + LayeredEffect, modifies target P/T")
    fun onDrawAuraResolve() {
        val messages = RecordingDecoder.decodeDirectory(onDrawDir)
        val auraResolve = messages.firstOrNull { msg ->
            msg.annotations.any { "AttachmentCreated" in it.types }
        }
        assertNotNull(auraResolve, "Should have AttachmentCreated annotation")

        val anns = auraResolve!!.annotations
        assertTrue(anns.any { "ResolutionStart" in it.types }, "Should have ResolutionStart")
        assertTrue(anns.any { "ResolutionComplete" in it.types }, "Should have ResolutionComplete")
        assertTrue(anns.any { "LayeredEffectCreated" in it.types }, "Should have LayeredEffectCreated")
        assertTrue(anns.any { "ZoneTransfer" in it.types }, "Should have ZoneTransfer (Resolve)")

        // Bird should now be 2/2 (was 1/1, Aura adds +1/+1)
        val bird = auraResolve.objects.firstOrNull { it.grpId == 75485 }
        assertNotNull(bird, "Bird should be in the resolve GSM")
        assertEquals(bird!!.power, 2, "Bird should have power 2 after Aura")
        assertEquals(bird.toughness, 2, "Bird should have toughness 2 after Aura")

        // Persistent annotations should include Attachment and LayeredEffect
        val perAnns = auraResolve.persistentAnnotations
        assertTrue(perAnns.any { "Attachment" in it.types }, "Should have Attachment persistent annotation")
        assertTrue(
            perAnns.any { "ModifiedPower" in it.types || "ModifiedToughness" in it.types },
            "Should have ModifiedPower/Toughness persistent annotation",
        )
    }

    @Test(description = "on-draw: combat damage produces DamageDealt + ModifiedLife chain")
    fun onDrawCombatDamage() {
        val messages = RecordingDecoder.decodeDirectory(onDrawDir)
        val damage = messages.firstOrNull { msg ->
            msg.annotations.any { "DamageDealt" in it.types }
        }
        assertNotNull(damage, "Should have DamageDealt annotation")

        val anns = damage!!.annotations
        val dmg = anns.first { "DamageDealt" in it.types }
        assertEquals(dmg.details["damage"], 2, "Bird (2/2) should deal 2 combat damage")
        assertEquals(dmg.affectorId, 280, "Attacker should be Bird (instanceId=280)")

        assertTrue(anns.any { "ModifiedLife" in it.types }, "Should have ModifiedLife")
        val life = anns.first { "ModifiedLife" in it.types }
        assertEquals(life.details["life"], -2, "Life should decrease by 2")

        // Player life should update
        assertTrue(damage.players.any { it.life == 18 }, "Seat 1 should be at 18 life after 2 damage")
    }

    @Test(description = "on-draw: SelectTargetsReq appears during Aura cast")
    fun onDrawSelectTargetsReq() {
        val messages = RecordingDecoder.decodeDirectory(onDrawDir)
        val selectTargets = messages.filter { it.hasSelectTargetsReq }
        assertTrue(selectTargets.isNotEmpty(), "Should have SelectTargetsReq messages")
        assertTrue(selectTargets.all { it.promptId != null && it.promptId != 0 }, "SelectTargetsReq should have promptId")
    }

    @Test(description = "on-draw: DeclareAttackersReq and DeclareBlockersReq present")
    fun onDrawCombatPrompts() {
        val messages = RecordingDecoder.decodeDirectory(onDrawDir)
        assertTrue(messages.any { it.hasDeclareAttackersReq }, "Should have DeclareAttackersReq")
        assertTrue(messages.any { it.hasDeclareBlockersReq }, "Should have DeclareBlockersReq")
    }

    @Test(description = "on-draw: ETB trigger creates Ability on Stack with GroupReq for Scry")
    fun onDrawEtbTrigger() {
        val messages = RecordingDecoder.decodeDirectory(onDrawDir)
        // Wall ETB creates an Ability (grpId=176406) on the Stack
        val etb = messages.firstOrNull { msg ->
            msg.objects.any { it.grpId == 176406 && it.type == "Ability" }
        }
        assertNotNull(etb, "Should have Ability object for Wall ETB trigger (grpId=176406)")

        // GroupReq for Scry choice
        val groupReq = messages.firstOrNull { it.greType == "GroupReq" }
        assertNotNull(groupReq, "Should have GroupReq for Scry choice")
    }

    // --- Accumulator tests ---

    @Test(description = "AccumulatorSimulator: on-play final state has objects and zones")
    fun accumulatorOnPlay() {
        val messages = RecordingDecoder.decodeDirectory(onPlayDir)
        val acc = AccumulatorSimulator()
        for (msg in messages) acc.process(msg)

        assertTrue(acc.zones.isNotEmpty(), "Should have accumulated zones")
        assertTrue(acc.turnInfo != null, "Should have turnInfo after processing")
        assertTrue(acc.players.values.isNotEmpty(), "Should have player info")
    }

    @Test(description = "AccumulatorSimulator: on-draw tracks life changes through combat")
    fun accumulatorOnDrawLifeChanges() {
        val messages = RecordingDecoder.decodeDirectory(onDrawDir)
        val acc = AccumulatorSimulator()
        for (msg in messages) acc.process(msg)

        // After full game, seat 1 should have taken damage
        val seat1 = acc.players[1]
        assertNotNull(seat1, "Should have seat 1 player")
        assertEquals(seat1!!.life, 18, "Seat 1 should be at 18 life (took 2 combat damage)")

        // Seat 2 should have taken damage too (Snake/Ninja attacked)
        val seat2 = acc.players[2]
        assertNotNull(seat2, "Should have seat 2 player")
        assertEquals(seat2!!.life, 19, "Seat 2 should be at 19 life (took 1 combat damage)")
    }

    @Test(description = "AccumulatorSimulator: on-draw battlefield has correct creature set")
    fun accumulatorOnDrawBattlefield() {
        val messages = RecordingDecoder.decodeDirectory(onDrawDir)
        val acc = AccumulatorSimulator()

        // Process up to the point where the Aura resolves (gsId=52)
        for (msg in messages) {
            acc.process(msg)
            if (msg.gsId == 52 && msg.gsmType != null) break
        }

        // Battlefield should have: Bird(280), Aura(288), Wall not yet, 2 Islands, 1 Forest, 1 creature
        val bfZone = acc.zones.values.firstOrNull { it.type == "Battlefield" }
        assertNotNull(bfZone, "Should have Battlefield zone")
        assertTrue(bfZone!!.objectIds.contains(280), "Battlefield should contain Bird (280)")
        assertTrue(bfZone.objectIds.contains(288), "Battlefield should contain Aura (288)")
        assertTrue(bfZone.objectIds.contains(279), "Battlefield should contain Island (279)")

        // Bird should be 2/2 (Aura applied)
        val bird = acc.objects[280]
        assertNotNull(bird, "Should have Bird in objects")
        assertEquals(bird!!.power, 2, "Bird should have power 2 (Aura applied)")
        assertEquals(bird.toughness, 2, "Bird should have toughness 2 (Aura applied)")
    }

    @Test(description = "JSONL serialization produces valid JSON for each message")
    fun jsonlSerialization() {
        val messages = RecordingDecoder.decodeDirectory(onPlayDir)
        for (msg in messages) {
            val json = RecordingDecoder.toJsonLine(msg)
            assertTrue(json.startsWith("{"), "JSON line should start with {")
            assertTrue(json.endsWith("}"), "JSON line should end with }")
            assertTrue(json.contains("\"index\""), "JSON should have index field")
            assertTrue(json.contains("\"greType\""), "JSON should have greType field")
        }
    }
}
