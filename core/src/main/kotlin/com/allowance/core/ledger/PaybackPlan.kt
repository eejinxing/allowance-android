package com.allowance.core.ledger

/**
 * A swap as it applies right now. A swap that lines up with one of your fixed bills (a Breakfast bill and the breakfast swap) is priced against
 * that bill: [swap] then carries the bill's name and daily price as "usual", so its saving is what the bill costs minus the cheaper option.
 * [fits] is false when the saving is bigger than the day's own money (only ever for a swap that isn't tied to a bill).
 */
data class SwapChoice(val swap: Swap, val fromBill: Boolean = false, val billId: String? = null, val fits: Boolean = true) {
    val id: String get() = swap.id
    val saving: Long get() = swap.usualMinor - swap.cheaperMinor
}

/** One day of a payback: what it normally gets ([was]), the swaps used, and what is left to spend ([now]). */
data class PaybackDayPlan(
    val date: String,
    val was: Long,
    val swaps: List<SwapChoice>,
    /** Taken evenly from the day's own money to cover what the swaps don't. */
    val trim: Long,
    /** Everything taken from the day's own money: swaps that are not tied to a bill, plus the trim. */
    val cut: Long,
    /** Saved from a fixed bill by a swap tied to it. The day keeps its own money for this part. */
    val billSaved: Long,
    val now: Long,
)

/** One of the next days, with the swaps that could go on it, so the chart can offer them. */
data class PaybackOption(
    val date: String,
    val was: Long,
    val cut: Long,
    val now: Long,
    val billSaved: Long,
    val swaps: List<SwapChoice>,
    val cands: List<SwapChoice>,
)

data class PaybackPlan(
    /** How much more than today allows. */
    val over: Long,
    val usual: Long,
    /** Everything spent today, including the purchase. */
    val total: Long,
    val days: List<PaybackDayPlan>,
    val options: List<PaybackOption>,
    val covered: Long,
    /** What can't be paid back from the days that are left. */
    val short: Long,
)
