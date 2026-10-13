package com.battlesbudz.jarvis.v2.actions

/** A current numeric Android reading, never model prose or remembered device state. */
data class BatteryCondition(val comparison: Comparison, val threshold: Int, val unless: Boolean = false) {
    enum class Comparison { BELOW, AT_MOST, ABOVE, AT_LEAST, EQUAL }
    init { require(threshold in 0..100) }
    fun matches(percent: Int): Boolean {
        require(percent in 0..100)
        val result = when (comparison) {
            Comparison.BELOW -> percent < threshold
            Comparison.AT_MOST -> percent <= threshold
            Comparison.ABOVE -> percent > threshold
            Comparison.AT_LEAST -> percent >= threshold
            Comparison.EQUAL -> percent == threshold
        }
        return if (unless) !result else result
    }
    data class Parsed(val condition: BatteryCondition, val actions: String)
    companion object {
        private val units = "one|two|three|four|five|six|seven|eight|nine"
        private val number = "(?:[0-9]+|one hundred|(?:twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety)(?:[ -](?:$units))?|zero|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|$units)"
        private val predicate = "(?:my |the |phone |device )?battery(?: (?:level|percentage|percent))?(?: is)?\\s+(less than or equal to|greater than or equal to|at most|at least|less than|lower than|more than|greater than|higher than|below|under|above|over|equal to|exactly|at)\\s+($number)(?:\\s*(?:percent|%))?"
        private val prefix = Regex("^(if|unless)\\s+$predicate(?:\\s*,\\s*|\\s+(?:then\\s+)?)(.+?)[.!?]*$", RegexOption.IGNORE_CASE)
        private val suffix = Regex("^(.+?)\\s+(if|unless)\\s+$predicate[.!?]*$", RegexOption.IGNORE_CASE)
        fun split(text: String): Parsed? {
            val first = prefix.matchEntire(text)
            val last = if (first == null) suffix.matchEntire(text) else null
            val match = first ?: last ?: return null
            val keyword = match.groupValues[if (first != null) 1 else 2]
            val operator = match.groupValues[if (first != null) 2 else 3].lowercase()
            val threshold = ActionTurnPlan.parseExactVolume(match.groupValues[if (first != null) 3 else 4]) ?: return null
            val actions = match.groupValues[if (first != null) 4 else 1].trim()
            val comparison = when (operator) {
                "less than or equal to", "at most" -> Comparison.AT_MOST
                "greater than or equal to", "at least" -> Comparison.AT_LEAST
                "less than", "lower than", "below", "under" -> Comparison.BELOW
                "more than", "greater than", "higher than", "above", "over" -> Comparison.ABOVE
                else -> Comparison.EQUAL
            }
            return Parsed(BatteryCondition(comparison, threshold, keyword.equals("unless", true)), actions)
        }
    }
}
