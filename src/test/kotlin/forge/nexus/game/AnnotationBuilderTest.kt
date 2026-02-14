package forge.nexus.game

import org.testng.Assert.assertEquals
import org.testng.Assert.assertTrue
import org.testng.annotations.Test
import wotc.mtgo.gre.external.messaging.Messages.AnnotationType

@Test
class AnnotationBuilderTest {

    @Test
    fun zoneTransferAnnotation() {
        val ann = AnnotationBuilder.zoneTransfer(
            instanceId = 100,
            srcZoneId = 31, // Hand
            destZoneId = 28, // Battlefield
            category = "PlayLand",
        )
        assertTrue(ann.typeList.contains(AnnotationType.ZoneTransfer_af5a))
        val details = ann.detailsList.associate { it.key to it.valueStringList.first() }
        assertEquals(details["zone_src"], "31")
        assertEquals(details["zone_dest"], "28")
        assertEquals(details["category"], "PlayLand")
        assertTrue(ann.affectedIdsList.contains(100))
    }

    @Test
    fun castSpellAnnotation() {
        val ann = AnnotationBuilder.zoneTransfer(
            instanceId = 105,
            srcZoneId = 31, // Hand
            destZoneId = 27, // Stack
            category = "CastSpell",
        )
        val details = ann.detailsList.associate { it.key to it.valueStringList.first() }
        assertEquals(details["zone_src"], "31")
        assertEquals(details["zone_dest"], "27")
        assertEquals(details["category"], "CastSpell")
    }
}
