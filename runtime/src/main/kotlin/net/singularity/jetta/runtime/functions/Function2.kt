package net.singularity.jetta.runtime.functions

import java.util.function.BiFunction

interface Function2<T1, T2, R> : BiFunction<T1, T2, R>