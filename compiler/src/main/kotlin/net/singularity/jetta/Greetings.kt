package net.singularity.jetta

import net.singularity.jetta.compiler.VersionInfo

fun greetings(): String {
    return "JeTTa ${VersionInfo.VERSION}"
}