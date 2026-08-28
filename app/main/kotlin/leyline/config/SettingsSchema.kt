package leyline.config

import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind

/**
 * Mechanical enumeration of the canonical scalar properties in the settings
 * graph, derived from the serialization schema. Environment override names are
 * derived from the same paths, so environment support is not declared
 * independently per field.
 */
internal object SettingsSchema {
    /** One canonical scalar property: the dotted path and its scalar kind. */
    data class Leaf(
        val path: List<String>,
        val kind: SerialKind,
    )

    /** All scalar leaves; nested class sections are descended into. */
    fun leaves(
        descriptor: SerialDescriptor,
        prefix: List<String> = emptyList(),
    ): List<Leaf> =
        (0 until descriptor.elementsCount).flatMap { index ->
            val name = descriptor.getElementName(index)
            val element = descriptor.getElementDescriptor(index)
            val path = prefix + name
            if (element.kind == StructureKind.CLASS) leaves(element, path) else listOf(Leaf(path, element.kind))
        }

    /** Environment name for a canonical path: `native.fd_port` → `LEYLINE_NATIVE_FD_PORT`. */
    fun envNameOf(path: List<String>): String = "LEYLINE_" + path.joinToString("_") { it.uppercase() }
}
