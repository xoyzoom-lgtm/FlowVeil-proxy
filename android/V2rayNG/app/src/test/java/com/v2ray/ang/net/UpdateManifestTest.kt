package com.v2ray.ang.net

import com.v2ray.ang.net.UpdateManifest.Reason
import com.v2ray.ang.net.UpdateManifest.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.Base64

/** Shared vectors in shared/test-vectors/update (the Windows tests use the same files). */
class UpdateManifestTest {
    private fun dir(): File {
        var d: File? = File("").absoluteFile
        while (d != null) {
            val f = File(d, "shared/test-vectors/update")
            if (f.isDirectory) return f
            d = d.parentFile
        }
        error("vectors not found")
    }

    private val v = dir()
    private val manifest = File(v, "manifest.json").readBytes()
    private val sig = File(v, "manifest.json.sig").readBytes()
    private val otherSig = File(v, "manifest.other.sig").readBytes()
    private val key = Base64.getDecoder().decode(File(v, "test-key.pub.b64").readText().trim())
    private val otherKey = Base64.getDecoder().decode(File(v, "other-key.pub.b64").readText().trim())
    private val payload = File(v, "payload.bin").readBytes()
    private val sha = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }

    private fun check(
        m: ByteArray? = manifest, s: ByteArray? = sig, keys: List<ByteArray> = listOf(key),
        name: String = "FlowVeil-android.apk", size: Long = payload.size.toLong(), hash: String = sha,
        tag: Int? = 127, current: Int = 126, strict: Boolean = false,
    ) = UpdateManifest.check(m, s, keys, name, size, hash, tag, current, strict)

    @Test fun goodSignature() = assertEquals(Result.Verified(UpdateManifest.parse(String(manifest))!!.files[0], true), check())
    @Test fun spareKeyAlsoWorks() = assertEquals(true, (check(keys = listOf(otherKey, key)) as Result.Verified).signed)
    @Test fun otherKeyRejected() = assertEquals(Result.Rejected(Reason.BAD_SIGNATURE), check(keys = listOf(otherKey)))
    @Test fun signatureOfOtherKeyRejected() = assertEquals(Result.Rejected(Reason.BAD_SIGNATURE), check(s = otherSig))
    @Test fun truncatedSignatureRejected() = assertEquals(Result.Rejected(Reason.BAD_SIGNATURE), check(s = sig.copyOf(sig.size - 3)))
    @Test fun changedManifestRejected() {
        val changed = String(manifest).replace("\"build\": 127", "\"build\": 128").toByteArray()
        assertEquals(Result.Rejected(Reason.BAD_SIGNATURE), check(m = changed, tag = 128))
    }
    @Test fun emptySignatureIsUnsigned() {
        assertEquals(false, (check(s = ByteArray(0)) as Result.Verified).signed)
        assertEquals(Result.Rejected(Reason.UNSIGNED), check(s = ByteArray(0), strict = true))
    }
    @Test fun noManifest() {
        assertEquals(Result.Missing, check(m = null, s = null))
        assertEquals(Result.Rejected(Reason.UNSIGNED), check(m = null, s = null, strict = true))
    }
    @Test fun noKeysBuiltInMeansUnsigned() = assertEquals(false, (check(keys = emptyList()) as Result.Verified).signed)
    @Test fun wrongName() = assertEquals(Result.Rejected(Reason.NOT_IN_MANIFEST), check(name = "FlowVeil-android-arm64.apk"))
    @Test fun wrongSize() = assertEquals(Result.Rejected(Reason.SIZE_MISMATCH), check(size = 1))
    @Test fun wrongHash() = assertEquals(Result.Rejected(Reason.HASH_MISMATCH), check(hash = "0".repeat(64)))
    @Test fun tagMismatch() = assertEquals(Result.Rejected(Reason.BUILD_MISMATCH), check(tag = 126))
    @Test fun rollback() {
        assertEquals(Result.Rejected(Reason.ROLLBACK), check(current = 127))
        assertEquals(Result.Rejected(Reason.ROLLBACK), check(current = 200))
    }
    @Test fun malformed() {
        assertEquals(Result.Rejected(Reason.MALFORMED), check(m = "{}".toByteArray(), s = null))
        assertNull(UpdateManifest.parse("""{"schema":2,"tag":"v1","build":1,"files":[{"name":"a","size":1,"sha256":"${"a".repeat(64)}"}]}"""))
        assertNull(UpdateManifest.parse("""{"schema":1,"tag":"v1","build":1,"files":[{"name":"a","size":1,"sha256":"xyz"}]}"""))
        assertNull(UpdateManifest.parse("""{"schema":1,"tag":"v1","build":1,"minBuild":5,"files":[{"name":"a","size":1,"sha256":"${"a".repeat(64)}"}]}"""))
        assertNotNull(UpdateManifest.parse("""{"schema":1,"tag":"v1","build":1,"files":[{"name":"a","size":1,"sha256":"${"A".repeat(64)}"}]}"""))
    }
}
