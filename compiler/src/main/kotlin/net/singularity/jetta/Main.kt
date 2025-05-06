package net.singularity.jetta
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.versionOption
import net.singularity.jetta.compiler.Compiler
import net.singularity.jetta.compiler.VersionInfo
import net.singularity.jetta.compiler.logger.LogLevel
import net.singularity.jetta.repl.InvalidInputException
import net.singularity.jetta.repl.Repl
import net.singularity.jetta.repl.ReplImpl
import kotlin.system.exitProcess


class Compile : CliktCommand("jettac") {
    private val sources by argument(help = "Paths to source files").multiple()
    private val output by option("-d", "--directory", help = "Output directory").default(".")
    private val noGreetings by option("-n", "--no-greetings", help = "Do not show greetings").flag()
    private val interactive by option("-i", "--interactive", help = "Interactive mode").flag()
    private val debug  by option("-D", "--debug", help = "Debug mode").flag()

    init {
        versionOption(VersionInfo.VERSION, names = setOf("--version"))
    }

    private fun runRepl() {
        if (!noGreetings) println(greetings())
        val repl = ReplImpl(logLevel = LogLevel.ERROR)
        runRepl(repl)
        exitProcess(0)
    }

    fun validateInput(input: String): Boolean {
        var count = 0
        input.forEach {
            when (it) {
                '(' -> count++
                ')' -> count--
            }
            if (count < 0) throw InvalidInputException("Extra ')' is found")
        }
        return count == 0
    }

    private fun runRepl(repl: Repl) {
        fun eval(input: String) {
            val result = repl.eval(input)
            if (result.isSuccess) {
                println(result.result)
            } else {
                result.messages.forEach {
                    println(it)
                }
            }
        }
        var readyToEvaluate = false
        val code = StringBuilder()
        while (true) {
            if (code.isNotEmpty()) print("...")
            print("> ")
            val input = readLine() ?: break
            if (input.isNotEmpty()) {
                if (input.lowercase() == ":exit") break
                code.append(input)
                try {
                    readyToEvaluate = validateInput(code.toString())
                } catch (e: InvalidInputException) {
                    printError(e.message!!)
                }
                if (readyToEvaluate) {
                    if (input == code.toString()) {
                        eval(code.toString())
                        code.clear()
                    } else {
                        println("Press ENTER to evaluate")
                        continue
                    }
                }
            } else {
                if (code.isEmpty()) continue
                eval(code.toString())
                code.clear()
            }
        }
    }

    private fun printError(message: String) {
        println(message)
    }

    private fun runCompiler() {
        if (!noGreetings) println(greetings())
        val logLevel = if (debug) LogLevel.DEBUG else LogLevel.INFO
        val compiler = Compiler(sources, output, logLevel = logLevel)
        val code = compiler.compile()
        if (code != 0) exitProcess(code)
    }

    override fun run() {
        if (interactive) runRepl() else runCompiler()
    }
}

fun main(args: Array<String>) {
    Compile().main(args)
}