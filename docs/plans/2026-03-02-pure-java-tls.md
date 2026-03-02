# Pure-Java TLS: Eliminate openssl/keytool subprocesses

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Remove all shell-out TLS tooling (openssl, keytool) from server startup and Docker image.

**Architecture:** Add `PemKeyConverter` utility that does PKCS#1→PKCS#8 ASN.1 wrapping in pure Java. Wire it into `LeylineServer.buildSslContext()` for auto-detection. Replace `MockWasServer.buildSslContext()` subprocess calls with in-memory Java crypto. Clean up Docker.

**Tech Stack:** Java Security API (`KeyFactory`, `CertificateFactory`, `PKCS8EncodedKeySpec`), Netty `SelfSignedCertificate`, ASN.1 DER encoding.

**Closes:** #7, #9

---

### Task 1: PemKeyConverter — PKCS#1→PKCS#8 conversion utility

**Files:**
- Create: `src/main/kotlin/leyline/server/PemKeyConverter.kt`
- Create: `src/test/kotlin/leyline/server/PemKeyConverterTest.kt`

**Step 1: Write the failing test**

```kotlin
package leyline.server

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import leyline.UnitTag
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

class PemKeyConverterTest : FunSpec({

    tags(UnitTag)

    test("round-trip: PKCS#1 PEM converts to valid PKCS#8 key") {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val kp = kpg.generateKeyPair()

        // Encode private key as PKCS#1 PEM (strip PKCS#8 wrapper)
        val pkcs8Bytes = kp.private.encoded  // JCA always gives PKCS#8
        val pkcs1Bytes = extractPkcs1FromPkcs8(pkcs8Bytes)
        val pkcs1Pem = "-----BEGIN RSA PRIVATE KEY-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pkcs1Bytes) +
            "\n-----END RSA PRIVATE KEY-----\n"

        val converted = PemKeyConverter.convertIfPkcs1(pkcs1Pem)

        // Should now be PKCS#8 PEM
        converted.contains("BEGIN PRIVATE KEY") shouldBe true
        converted.contains("BEGIN RSA PRIVATE KEY") shouldBe false

        // Parse back and verify key equality
        val pemBody = converted
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\\s".toRegex(), "")
        val decoded = Base64.getDecoder().decode(pemBody)
        val recovered = KeyFactory.getInstance("RSA")
            .generatePrivate(PKCS8EncodedKeySpec(decoded))
        recovered.encoded shouldBe kp.private.encoded
    }

    test("PKCS#8 PEM passes through unchanged") {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val kp = kpg.generateKeyPair()
        val pem = "-----BEGIN PRIVATE KEY-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(kp.private.encoded) +
            "\n-----END PRIVATE KEY-----\n"

        PemKeyConverter.convertIfPkcs1(pem) shouldBe pem
    }
})

/** Extract raw RSA key (PKCS#1) from PKCS#8 encoded bytes by stripping the ASN.1 wrapper. */
private fun extractPkcs1FromPkcs8(pkcs8: ByteArray): ByteArray {
    // PKCS#8 = SEQUENCE { SEQUENCE { OID, NULL }, OCTET STRING { pkcs1 } }
    // The OCTET STRING containing PKCS#1 is the last TLV in the outer SEQUENCE.
    // Simple parser: find the OCTET STRING (tag 0x04) wrapping a SEQUENCE (tag 0x30).
    var i = 0
    fun readTag() = pkcs8[i++].toInt() and 0xFF
    fun readLength(): Int {
        val first = pkcs8[i++].toInt() and 0xFF
        if (first < 0x80) return first
        val numBytes = first and 0x7F
        var len = 0
        repeat(numBytes) { len = (len shl 8) or (pkcs8[i++].toInt() and 0xFF) }
        return len
    }
    // Outer SEQUENCE
    check(readTag() == 0x30)
    readLength()
    // AlgorithmIdentifier SEQUENCE — skip it
    check(readTag() == 0x02 || pkcs8[i - 1].toInt() and 0xFF == 0x30)
    // Back up — actually let's just find the OCTET STRING
    i = 0
    readTag(); readLength() // outer SEQ
    // version INTEGER (PKCS#8 v0)
    if ((pkcs8[i].toInt() and 0xFF) == 0x02) { readTag(); val vl = readLength(); i += vl }
    // AlgorithmIdentifier SEQUENCE
    readTag(); val algLen = readLength(); i += algLen
    // OCTET STRING containing PKCS#1
    check(readTag() == 0x04)
    val octetLen = readLength()
    return pkcs8.copyOfRange(i, i + octetLen)
}
```

**Step 2: Run test to verify it fails**

Run: `just test-one PemKeyConverterTest`
Expected: compilation failure — `PemKeyConverter` doesn't exist

**Step 3: Write minimal implementation**

```kotlin
package leyline.server

import java.util.Base64

/**
 * Converts PKCS#1 (BEGIN RSA PRIVATE KEY) PEM to PKCS#8 (BEGIN PRIVATE KEY) PEM.
 * Pure Java — no openssl dependency.
 */
object PemKeyConverter {

    private const val PKCS1_HEADER = "-----BEGIN RSA PRIVATE KEY-----"
    private const val PKCS8_HEADER = "-----BEGIN PRIVATE KEY-----"

    /** If the PEM is PKCS#1, convert to PKCS#8. Otherwise return as-is. */
    fun convertIfPkcs1(pem: String): String {
        if (!pem.contains(PKCS1_HEADER)) return pem

        val base64 = pem
            .replace("-----BEGIN RSA PRIVATE KEY-----", "")
            .replace("-----END RSA PRIVATE KEY-----", "")
            .replace("\\s".toRegex(), "")
        val pkcs1Bytes = Base64.getDecoder().decode(base64)
        val pkcs8Bytes = wrapPkcs1InPkcs8(pkcs1Bytes)

        return "-----BEGIN PRIVATE KEY-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pkcs8Bytes) +
            "\n-----END PRIVATE KEY-----\n"
    }

    /**
     * Wrap raw PKCS#1 RSA key bytes in a PKCS#8 envelope.
     *
     * PKCS#8 structure:
     *   SEQUENCE {
     *     INTEGER 0                          -- version
     *     SEQUENCE {                         -- AlgorithmIdentifier
     *       OID 1.2.840.113549.1.1.1        -- rsaEncryption
     *       NULL
     *     }
     *     OCTET STRING { <pkcs1 bytes> }
     *   }
     */
    private fun wrapPkcs1InPkcs8(pkcs1: ByteArray): ByteArray {
        // RSA OID 1.2.840.113549.1.1.1
        val rsaOid = byteArrayOf(
            0x06, 0x09,
            0x2A.toByte(), 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(),
            0x0D, 0x01, 0x01, 0x01,
        )
        val nullTag = byteArrayOf(0x05, 0x00)
        val algId = derSequence(rsaOid + nullTag)
        val version = byteArrayOf(0x02, 0x01, 0x00) // INTEGER 0
        val keyOctet = derOctetString(pkcs1)
        return derSequence(version + algId + keyOctet)
    }

    private fun derSequence(content: ByteArray): ByteArray =
        byteArrayOf(0x30) + derLength(content.size) + content

    private fun derOctetString(content: ByteArray): ByteArray =
        byteArrayOf(0x04) + derLength(content.size) + content

    private fun derLength(len: Int): ByteArray = when {
        len < 0x80 -> byteArrayOf(len.toByte())
        len < 0x100 -> byteArrayOf(0x81.toByte(), len.toByte())
        len < 0x10000 -> byteArrayOf(0x82.toByte(), (len shr 8).toByte(), (len and 0xFF).toByte())
        else -> byteArrayOf(
            0x83.toByte(),
            (len shr 16).toByte(),
            ((len shr 8) and 0xFF).toByte(),
            (len and 0xFF).toByte(),
        )
    }
}
```

**Step 4: Run test to verify it passes**

Run: `just test-one PemKeyConverterTest`
Expected: PASS

**Step 5: Commit**

```
git add src/main/kotlin/leyline/server/PemKeyConverter.kt src/test/kotlin/leyline/server/PemKeyConverterTest.kt
git commit -m "feat: add PemKeyConverter for PKCS#1→PKCS#8 in pure Java"
```

---

### Task 2: Wire PemKeyConverter into LeylineServer.buildSslContext

**Files:**
- Modify: `src/main/kotlin/leyline/server/LeylineServer.kt:92-99`

**Step 1: Write the failing test**

Add to `PemKeyConverterTest`:

```kotlin
test("LeylineServer buildSslContext accepts PKCS#1 key file") {
    val kpg = KeyPairGenerator.getInstance("RSA")
    kpg.initialize(2048)
    val kp = kpg.generateKeyPair()

    // Generate self-signed cert
    val ssc = io.netty.handler.ssl.util.SelfSignedCertificate()

    // Write PKCS#1 key to temp file
    val pkcs1Bytes = extractPkcs1FromPkcs8(kp.private.encoded)
    val keyFile = java.io.File.createTempFile("test-pkcs1-", ".pem")
    keyFile.deleteOnExit()
    keyFile.writeText(
        "-----BEGIN RSA PRIVATE KEY-----\n" +
        Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pkcs1Bytes) +
        "\n-----END RSA PRIVATE KEY-----\n"
    )

    // Should not throw — LeylineServer should auto-convert PKCS#1
    val sslCtx = io.netty.handler.ssl.SslContextBuilder
        .forServer(ssc.certificate(), keyFile)
        .build()
    sslCtx.newEngine(io.netty.buffer.UnpooledByteBufAllocator.DEFAULT)
}
```

Actually — this test should verify LeylineServer's `buildSslContext` handles it. But that method is private. Instead, the test proves Netty accepts a PKCS#1-converted file. The real integration is: modify `buildSslContext` to read the key file, convert if needed, write to temp file, pass to Netty.

**Step 2: Modify LeylineServer.buildSslContext**

```kotlin
private fun buildSslContext(cert: File?, key: File?, name: String): SslContext = if (cert != null && key != null) {
    log.info("{}: loading TLS cert={} key={}", name, cert, key)
    val effectiveKey = convertKeyIfPkcs1(key)
    SslContextBuilder.forServer(cert, effectiveKey).build()
} else {
    log.info("{}: using self-signed TLS certificate", name)
    val ssc = SelfSignedCertificate()
    SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build()
}

/** If the key file is PKCS#1 (BEGIN RSA PRIVATE KEY), convert to PKCS#8 temp file. */
private fun convertKeyIfPkcs1(keyFile: File): File {
    val pem = keyFile.readText()
    if (!pem.contains("BEGIN RSA PRIVATE KEY")) return keyFile
    log.info("Converting PKCS#1 key to PKCS#8 (Netty requires PKCS#8)")
    val converted = PemKeyConverter.convertIfPkcs1(pem)
    val tmp = File.createTempFile("leyline-pkcs8-", ".pem")
    tmp.deleteOnExit()
    tmp.writeText(converted)
    return tmp
}
```

**Step 3: Run tests**

Run: `just test-one PemKeyConverterTest`
Expected: PASS

**Step 4: Commit**

```
git add src/main/kotlin/leyline/server/LeylineServer.kt
git commit -m "feat: auto-convert PKCS#1 keys in LeylineServer.buildSslContext"
```

---

### Task 3: Replace MockWasServer subprocess calls with pure Java

**Files:**
- Modify: `src/main/kotlin/leyline/server/MockWasServer.kt:1-225`

**Step 1: Write the failing test**

Add to `PemKeyConverterTest`:

```kotlin
test("MockWasServer self-signed SSLContext creates SSLEngine") {
    // This tests the self-signed fallback path (null cert/key).
    // After refactoring, this should work without keytool.
    val ctx = MockWasServer.buildSslContext(null, null)
    val engine = ctx.createSSLEngine()
    engine.useClientMode = false
    engine.supportedProtocols.isNotEmpty() shouldBe true
}
```

Note: `buildSslContext` is currently `private` in the companion. Need to make it `internal` for testing.

**Step 2: Rewrite MockWasServer.buildSslContext**

Replace lines 173-222 with:

```kotlin
internal fun buildSslContext(certFile: File?, keyFile: File?): SSLContext {
    if (certFile != null && keyFile != null && certFile.exists() && keyFile.exists()) {
        // Load PEM cert + key into in-memory KeyStore (no openssl subprocess)
        val certFactory = java.security.cert.CertificateFactory.getInstance("X.509")
        val cert = certFile.inputStream().use { certFactory.generateCertificate(it) }

        val keyPem = PemKeyConverter.convertIfPkcs1(keyFile.readText())
        val keyBase64 = keyPem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\\s".toRegex(), "")
        val keyBytes = java.util.Base64.getDecoder().decode(keyBase64)
        val keySpec = java.security.spec.PKCS8EncodedKeySpec(keyBytes)
        val privateKey = java.security.KeyFactory.getInstance("RSA").generatePrivate(keySpec)

        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry("mock-was", privateKey, charArrayOf(), arrayOf(cert))
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, charArrayOf())
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(kmf.keyManagers, null, null)
        return ctx
    }

    // Self-signed fallback via Netty's SelfSignedCertificate (no keytool)
    val ssc = io.netty.handler.ssl.util.SelfSignedCertificate()
    val certFactory = java.security.cert.CertificateFactory.getInstance("X.509")
    val cert = ssc.certificate().inputStream().use { certFactory.generateCertificate(it) }

    val keyPem = ssc.privateKey().readText()
    val keyBase64 = keyPem
        .replace("-----BEGIN PRIVATE KEY-----", "")
        .replace("-----END PRIVATE KEY-----", "")
        .replace("\\s".toRegex(), "")
    val keyBytes = java.util.Base64.getDecoder().decode(keyBase64)
    val keySpec = java.security.spec.PKCS8EncodedKeySpec(keyBytes)
    val privateKey = java.security.KeyFactory.getInstance("RSA").generatePrivate(keySpec)

    val ks = KeyStore.getInstance("PKCS12")
    ks.load(null, null)
    ks.setKeyEntry("mock-was", privateKey, charArrayOf(), arrayOf(cert))
    val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
    kmf.init(ks, charArrayOf())
    val ctx = SSLContext.getInstance("TLS")
    ctx.init(kmf.keyManagers, null, null)
    return ctx
}
```

Refactor: extract common `buildSslContextFromPem` helper to avoid duplication between the two branches.

**Step 3: Run test**

Run: `just test-one PemKeyConverterTest`
Expected: PASS

**Step 4: Commit**

```
git add src/main/kotlin/leyline/server/MockWasServer.kt
git commit -m "refactor: replace openssl/keytool subprocesses in MockWasServer with pure Java"
```

---

### Task 4: Docker cleanup — remove openssl, delete entrypoint.sh

**Files:**
- Modify: `deploy/Dockerfile:69-97`
- Delete: `deploy/entrypoint.sh`

**Step 1: Edit Dockerfile**

Remove line 72 (`apt-get ... openssl`), remove lines 86-88 (`COPY entrypoint.sh`, `chmod`), change ENTRYPOINT/CMD:

```dockerfile
# --- Stage 3: Runtime ---
FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

# Application distribution (launch script + all JARs)
COPY --from=build /build/build/install/leyline/ .

# Game resources (card scripts — large media dirs excluded via .dockerignore)
COPY forge/forge-gui/res/ forge-gui/res/

# Playtest config defaults (decks are embedded in forge-gui/res)
COPY playtest.toml .
COPY decks/ decks/

EXPOSE 30010 30003 9443 8090

# Memory limit (Netty access flags baked into launch script via applicationDefaultJvmArgs)
ENV JAVA_OPTS="-Xmx384m"

CMD ["bin/leyline"]
```

**Step 2: Delete entrypoint.sh**

```
trash deploy/entrypoint.sh
```

**Step 3: Commit**

```
git add deploy/Dockerfile
git add -u deploy/entrypoint.sh
git commit -m "build: remove openssl from Docker, delete entrypoint.sh

PKCS#1→PKCS#8 conversion now handled in Kotlin by PemKeyConverter."
```

---

### Task 5: Run test gate + format

**Step 1:** `just fmt`
**Step 2:** `just test-gate`
Expected: all pass

**Step 3: Fix any issues, commit if formatting changed**

```
git add -A && git commit -m "style: formatting"
```
