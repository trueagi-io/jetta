package net.singularity.jetta.runtime.space

import java.nio.file.Files
import java.nio.file.Path

/**
 * Where the files of one space artifact (`<name>.manifest.json`, `<name>.jtsf`, its index blobs)
 * are read from.
 *
 * A program's own artifacts sit in a directory beside its class. A module SHIPPED with the
 * compiler — the standard library — sits inside `jettac.jar`, which is already on the runtime
 * classpath and cannot be addressed as a directory. Both are just named byte blobs, so the loader
 * asks for them by name and does not care which it is talking to.
 */
sealed interface ArtifactSource {
    /** The bytes of [name], or null when this source does not have it. */
    fun readBytes(name: String): ByteArray?

    /** A description fit for an error message — where the loader looked. */
    val description: String

    class Directory(private val directory: Path) : ArtifactSource {
        override fun readBytes(name: String): ByteArray? {
            val path = directory.resolve(name)
            return if (Files.isRegularFile(path)) Files.readAllBytes(path) else null
        }

        override val description: String get() = directory.toAbsolutePath().toString()
    }

    /**
     * Files packed under [prefix] in the classpath — how the compiler ships the standard library.
     */
    class Resources(
        private val prefix: String,
        private val loader: ClassLoader = ArtifactSource::class.java.classLoader,
    ) : ArtifactSource {
        override fun readBytes(name: String): ByteArray? =
            loader.getResourceAsStream(prefix + name)?.use { it.readBytes() }

        override val description: String get() = "classpath:$prefix"
    }

    companion object {
        /** Where a module shipped inside the compiler's jar lives. */
        const val SHIPPED_MODULE_PREFIX = "jetta/lib/"

        /** The shipped modules, as a source. */
        fun shipped(): ArtifactSource = Resources(SHIPPED_MODULE_PREFIX)
    }
}
