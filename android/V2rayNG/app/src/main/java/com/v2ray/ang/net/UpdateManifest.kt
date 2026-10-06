package com.v2ray.ang.net

import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Release manifest check before an update is installed (the same rules as Windows Handler/UpdateManifest.cs).
 * Every release carries FlowVeil-manifest.json (tag, build, files with size and SHA-256); the owner signs it offline
 * (ECDSA P-256 + SHA-256, DER signature in FlowVeil-manifest.json.sig). Two public keys are built in: the current one and a spare.
 *
 * Transition: while [REQUIRE_SIGNED] is false an unsigned or missing manifest is not refused but the user is asked
 * ("Это обновление не подписано, установить всё равно?", default "No"); a bad signature or a wrong file is always refused.
 */
object UpdateManifest {
    const val MANIFEST_FILE = "FlowVeil-manifest.json"
    const val SIGNATURE_FILE = "FlowVeil-manifest.json.sig"

    /** Turned on two versions after the first signed release (OWNER-TODO). */
    const val REQUIRE_SIGNED = false

    data class FileEntry(val name: String, val size: Long, val sha256: String)

    data class Manifest(val schema: Int, val tag: String, val build: Int, val minBuild: Int, val files: List<FileEntry>)

    enum class Reason { MALFORMED, BAD_SIGNATURE, UNSIGNED, NOT_IN_MANIFEST, SIZE_MISMATCH, HASH_MISMATCH, BUILD_MISMATCH, ROLLBACK }

    sealed interface Result {
        /** The file is the one the manifest lists; [signed] = the manifest signature was checked with a built-in key. */
        data class Verified(val entry: FileEntry, val signed: Boolean) : Result
        data class Rejected(val reason: Reason) : Result
        /** The release has no manifest (older releases). */
        data object Missing : Result
    }

    private fun str(text: String, key: String): String? = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(text)?.groupValues?.get(1)
    private fun num(text: String, key: String): Long? = Regex("\"$key\"\\s*:\\s*(\\d{1,18})").find(text)?.groupValues?.get(1)?.toLongOrNull()

    /** Reads the fixed manifest format written by CI; null when something is missing or odd. */
    fun parse(text: String): Manifest? {
        if (text.length > 64 * 1024) return null
        val schema = num(text, "schema")?.toInt() ?: return null
        val tag = str(text, "tag") ?: return null
        val build = num(text, "build")?.toInt() ?: return null
        val minBuild = num(text, "minBuild")?.toInt() ?: 0
        val filesBlock = Regex("\"files\"\\s*:\\s*\\[(.*)]", RegexOption.DOT_MATCHES_ALL).find(text)?.groupValues?.get(1) ?: return null
        val files = mutableListOf<FileEntry>()
        for (m in Regex("\\{[^{}]*}").findAll(filesBlock)) {
            val o = m.value
            files += FileEntry(
                name = str(o, "name") ?: return null,
                size = num(o, "size") ?: return null,
                sha256 = str(o, "sha256")?.lowercase()?.takeIf { Regex("[0-9a-f]{64}").matches(it) } ?: return null,
            )
        }
        if (schema != 1 || files.isEmpty() || minBuild > build || files.map { it.name }.toSet().size != files.size) return null
        return Manifest(schema, tag, build, minBuild, files)
    }

    /** True when [signature] (DER) over the exact [manifest] bytes was made by one of [publicKeys] (X.509 SubjectPublicKeyInfo). */
    fun signatureOk(manifest: ByteArray, signature: ByteArray, publicKeys: List<ByteArray>): Boolean =
        publicKeys.any { key ->
            runCatching {
                val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(key))
                Signature.getInstance("SHA256withECDSA").run {
                    initVerify(pub)
                    update(manifest)
                    verify(signature)
                }
            }.getOrDefault(false)
        }

    /**
     * Everything the installer must know about a downloaded file.
     * [tagBuild] is the build number from the release tag; [currentBuild] the running app's build.
     */
    fun check(
        manifest: ByteArray?,
        signature: ByteArray?,
        publicKeys: List<ByteArray>,
        assetName: String,
        size: Long,
        sha256: String,
        tagBuild: Int?,
        currentBuild: Int,
        requireSigned: Boolean = REQUIRE_SIGNED,
    ): Result {
        if (manifest == null) return if (requireSigned) Result.Rejected(Reason.UNSIGNED) else Result.Missing
        val parsed = parse(manifest.toString(Charsets.UTF_8)) ?: return Result.Rejected(Reason.MALFORMED)
        val signed = when {
            signature != null && signature.isNotEmpty() && publicKeys.isNotEmpty() ->
                if (signatureOk(manifest, signature, publicKeys)) true else return Result.Rejected(Reason.BAD_SIGNATURE)
            else -> false
        }
        if (!signed && requireSigned) return Result.Rejected(Reason.UNSIGNED)
        if (tagBuild != null && parsed.build != tagBuild) return Result.Rejected(Reason.BUILD_MISMATCH)
        if (parsed.build <= currentBuild) return Result.Rejected(Reason.ROLLBACK)
        val entry = parsed.files.firstOrNull { it.name == assetName } ?: return Result.Rejected(Reason.NOT_IN_MANIFEST)
        if (entry.size != size) return Result.Rejected(Reason.SIZE_MISMATCH)
        if (!entry.sha256.equals(sha256, ignoreCase = true)) return Result.Rejected(Reason.HASH_MISMATCH)
        return Result.Verified(entry, signed)
    }
}
