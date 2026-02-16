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
        // zone_src/zone_dest use Int32 type (matches real recordings)
        val zoneSrc = ann.detailsList.first { it.key == "zone_src" }
        assertEquals(zoneSrc.getValueInt32(0), 31)
        val zoneDest = ann.detailsList.first { it.key == "zone_dest" }
        assertEquals(zoneDest.getValueInt32(0), 28)
        val category = ann.detailsList.first { it.key == "category" }
        assertEquals(category.getValueString(0), "PlayLand")
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
        val zoneSrc = ann.detailsList.first { it.key == "zone_src" }
        assertEquals(zoneSrc.getValueInt32(0), 31)
        val zoneDest = ann.detailsList.first { it.key == "zone_dest" }
        assertEquals(zoneDest.getValueInt32(0), 27)
        val category = ann.detailsList.first { it.key == "category" }
        assertEquals(category.getValueString(0), "CastSpell")
    }
}
