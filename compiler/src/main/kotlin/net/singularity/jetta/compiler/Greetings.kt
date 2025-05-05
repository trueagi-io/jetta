package net.singularity.jetta.compiler

fun greetings(): String {
    return """
      ██╗ ███████╗████████╗████████╗ █████╗ 
      ██║ ██╔════╝╚══██╔══╝╚══██╔══╝██╔══██╗
      ██║ █████╗     ██║      ██║   ███████║
 ██   ██║ ██╔══╝     ██║      ██║   ██╔══██║
 ╚█████╔╝ ███████╗   ██║      ██║   ██║  ██║
  ╚════╝  ╚══════╝   ╚═╝      ╚═╝   ╚═╝  ╚═╝
 Version: ${VersionInfo.VERSION}
        """.trimIndent()
}