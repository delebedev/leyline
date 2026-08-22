package leyline.architecture.fixtures

import leyline.tooling.headless.HeadlessMatch

internal fun HeadlessMatch.internalFixture(marker: String): String = marker

@JvmSynthetic
public fun HeadlessMatch.publicJvmSyntheticFixture(marker: String): String = marker

public fun <T> HeadlessMatch.publicGenericFixture(value: T): T = value

public fun HeadlessMatch.publicMultilineFixture(
    firstMarker: String,
    secondMarker: String,
): String = firstMarker + secondMarker
