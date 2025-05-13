package net.singularity.jetta

import net.singularity.jetta.compiler.VersionInfo

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