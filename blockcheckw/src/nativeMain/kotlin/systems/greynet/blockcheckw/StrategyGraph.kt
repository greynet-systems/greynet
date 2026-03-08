package systems.greynet.blockcheckw

import kotlin.test.assertEquals

fun checkStrategies(
    strategiesToCheck: List<Strategy>,
    workingStrategies: List<Strategy>
): List<Strategy> {
    if (strategiesToCheck.isEmpty()) return workingStrategies

    val head = strategiesToCheck.first()
    val tail = strategiesToCheck.drop(1)

    return if (validStrategyWithNfqws2(head)) {
        val newWorkingStrategies = workingStrategies + head
        newWorkingStrategies + checkStrategies(tail, newWorkingStrategies)
        // TODO: кэширование TTL?
    } else {
        /** Если action X с evasion Y не работает, то все комбинации X+Y+* можно пропустить */
        val pruneBranch = tail.filter { it.action != head.action && it.evasion != head.evasion }
        workingStrategies + checkStrategies(pruneBranch, workingStrategies)
    }
}

fun validStrategyWithNfqws2(strategyToCheck: Strategy): Boolean {
    return true
}

fun parseStrategy(str: String): Strategy {
    val payload = str.lastIndexOf("--payload=")
    val s = str.drop(payload)
    return Strategy(
        payload = Payload(s),
        action = Action(""),
        position = Position(""),
        evasion = Evasion(""),
        Modifiers = Modifiers(""),
        Stage2Payload = Stage2Payload(s),
    )
}

fun main() {
}

sealed interface TreeLevel
data class Payload(val value: String) : TreeLevel
data class Action(val value: String) : TreeLevel
data class Position(val value: String) : TreeLevel
data class Evasion(val value: String) : TreeLevel
data class Modifiers(val value: String) : TreeLevel
data class Stage2Payload(val value: String) : TreeLevel

data class Branch(
    val action: Action,
    val evasion: Evasion,
)

data class Strategy(
    val payload: Payload,
    val action: Action,
    val position: Position,
    val evasion: Evasion,
    val Modifiers: Modifiers,
    val Stage2Payload: Stage2Payload,
)