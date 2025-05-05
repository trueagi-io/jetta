package net.singularity.jetta.repl
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.versionOption
import net.singularity.jetta.compiler.VersionInfo
import net.singularity.jetta.compiler.greetings
import net.singularity.jetta.compiler.logger.LogLevel
import kotlin.system.exitProcess


class ReplCommand : CliktCommand("jettac") {
    init {
        versionOption(VersionInfo.VERSION, names = setOf("--version"))
    }
    private val noGreetings by option("-n", "--no-greetings", help = "Do no use greetings").flag()

    override fun run() {
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
}

fun main(args: Array<String>) {
    ReplCommand().main(args)
}