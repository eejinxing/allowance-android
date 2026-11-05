package com.allowance.core.model

import java.math.BigDecimal
import java.math.RoundingMode

/** Amount in minor units of the budget currency (MYR: sen). */
@JvmInline
value class Money(val minor: Long) : Comparable<Money> {
    operator fun plus(other: Money) = Money(Math.addExact(minor, other.minor))
    operator fun minus(other: Money) = Money(Math.subtractExact(minor, other.minor))
    override fun compareTo(other: Money) = minor.compareTo(other.minor)
    fun decimal(): String = BigDecimal.valueOf(minor, 2).toPlainString()

    companion object {
        val ZERO = Money(0)

        /** Rejects excess decimal places instead of silently changing user input. */
        fun parse(text: String): Money = Money(
            text.trim().toBigDecimal().setScale(2, RoundingMode.UNNECESSARY)
                .movePointRight(2).longValueExact(),
        )
    }
}
