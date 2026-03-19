package leyline.frontdoor

import com.google.protobuf.UnknownFieldSet
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe

class FdProtoBuilderTest :
    FunSpec({

        test("buildFormatsProto produces valid Any-wrapped proto with formats and groups") {
            val bytes = FdProtoBuilder.buildFormatsProto()
            val any = UnknownFieldSet.parseFrom(bytes)

            // field 1 = type_url
            val typeUrl = any.getField(1).lengthDelimitedList.first().toStringUtf8()
            typeUrl shouldBe "type.googleapis.com/Wizards.Arena.Models.Network.GetFormatsResponse"

            // field 2 = inner message bytes
            val inner = UnknownFieldSet.parseFrom(any.getField(2).lengthDelimitedList.first())
            // field 1 = format entries, field 2 = format groups
            val formatCount = inner.getField(1).lengthDelimitedList.size
            val groupCount = inner.getField(2).lengthDelimitedList.size

            formatCount shouldBeGreaterThan 10 // 21 formats in metadata
            groupCount shouldBe 3 // EvergreenFormats, ConstructedSortOrder, BannedFormats
        }

        test("buildSetsProto produces valid Any-wrapped proto with sets and groups") {
            val bytes = FdProtoBuilder.buildSetsProto()
            val any = UnknownFieldSet.parseFrom(bytes)

            val typeUrl = any.getField(1).lengthDelimitedList.first().toStringUtf8()
            typeUrl shouldBe "type.googleapis.com/Wizards.Arena.Models.Network.SetMetadataResponse"

            val inner = UnknownFieldSet.parseFrom(any.getField(2).lengthDelimitedList.first())
            // field 1 = set entries, field 2 = set groups
            val setCount = inner.getField(1).lengthDelimitedList.size
            val groupCount = inner.getField(2).lengthDelimitedList.size

            setCount shouldBeGreaterThan 50 // 109 sets in metadata
            groupCount shouldBe 1 // AllFilters
        }
    })
