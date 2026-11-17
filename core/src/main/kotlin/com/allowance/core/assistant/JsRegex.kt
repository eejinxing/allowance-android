package com.allowance.core.assistant

/**
 * The assistant's patterns were written for the web app, where `\b` is an ASCII word boundary: "咖啡" next to "coffee" is a boundary there.
 * On the JVM, and more so on Android (ICU regex), `\b` treats Chinese characters as word letters, so the same pattern would read sentences
 * differently. Every `\b` is spelled out as an ASCII boundary here, so the rules behave the same on the phone, in tests and on the web.
 */
private const val W = "[A-Za-z0-9_]"
private const val ASCII_BOUNDARY = "(?:(?<=$W)(?!$W)|(?<!$W)(?=$W))"

internal fun jsRegex(pattern: String, ignoreCase: Boolean = false): Regex =
    Regex(pattern.replace("\\b", ASCII_BOUNDARY), if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet())
