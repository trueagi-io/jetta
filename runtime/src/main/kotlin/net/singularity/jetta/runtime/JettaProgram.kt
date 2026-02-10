package net.singularity.jetta.runtime

import net.singularity.jetta.compiler.frontend.ir.Atom
import net.singularity.jetta.compiler.frontend.ir.Expression
import net.singularity.jetta.runtime.space.Space
import net.singularity.jetta.runtime.space.SpaceDirectorySerializer
import net.singularity.jetta.runtime.space.SpaceImpl
import java.nio.file.Path
import kotlin.io.path.exists

open class JettaProgram {
    companion object {
        private lateinit var space: Space
        private var dataDir: Path = Path.of(".")

        @JvmStatic
        fun setDataDir(path: Path) {
            dataDir = path
        }

        @JvmStatic
        fun init(programName: String) {
            // Clear the space, it's important for test
            space = SpaceImpl()
            val manifestFile = dataDir.resolve("$programName.manifest.json")
            if (manifestFile.exists()) {
                space = SpaceDirectorySerializer.load(dataDir, programName)
            }
        }

        @JvmStatic
        fun getSpace(): Space = space

        @JvmStatic
        fun match(src: Expression, dst: Atom): List<Atom> =
            space.match(src, dst)
    }
}