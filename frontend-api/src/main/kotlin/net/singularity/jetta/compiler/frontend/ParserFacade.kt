package net.singularity.jetta.compiler.frontend


interface ParserFacade {
    fun parse(source: Source, messageCollector: MessageCollector): ParsedSource
}