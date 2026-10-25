package com.bailensn.sort.ui

/** 把一组整数排成一行展示，例如 `1 · 2 · 3`。 */
internal fun Iterable<Int>.toDisplayString(): String = joinToString(separator = " · ")
