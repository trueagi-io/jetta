package net.singularity.jetta.compiler
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.versionOption
import kotlin.system.exitProcess


class Compile : CliktCommand("jettac") {
    private val sources by argument(help = "Paths to source files").multiple()
    private val output by option("-d", "--directory", help = "Output directory").default(".")

    init {
        versionOption(VersionInfo.VERSION, names = setOf("--version"))
    }

    override fun run() {
        val compiler = Compiler(sources, output)
        val code = compiler.compile()
        if (code != 0) exitProcess(code)
    }
}

fun main(args: Array<String>) {
    Compile().main(args)
}