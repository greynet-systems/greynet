package systems.greynet.blockcheckw

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

// TODO: переписать на регулярочки?
// !!! TODO: в одной стратегии может быть несколько --lua-desync !!!
fun parseStrategy(str: String): Strategy {
    val payloadFlagIndex = str.indexOf("--payload=")
    val dropPayload = str.drop(payloadFlagIndex + 10)
    val payloadValueEndIndex = dropPayload.indexOf(" ")
    val payloadValue = dropPayload.take(payloadValueEndIndex)

    val luaDesyncIndex = str.indexOf("--lua-desync=")
    val luaDesyncValueHead = str.drop(luaDesyncIndex + 13)
    val luaDesyncValueHeadEnd = luaDesyncValueHead.indexOf(" ")
    val luaDesyncValues = luaDesyncValueHead.substring(0, luaDesyncValueHeadEnd).split(":")

    return Strategy(
        payload = Payload(payloadValue),
        action = Action(luaDesyncValues[0]),
        position = Position(luaDesyncValues[1]),
        evasion = Evasion(luaDesyncValues[2]),
        modifiers = Modifiers(luaDesyncValues[3]),
        Stage2Payload = Stage2Payload("pktmod:ip_ttl=1"),
    )
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
    val modifiers: Modifiers,
    val Stage2Payload: Stage2Payload,
)