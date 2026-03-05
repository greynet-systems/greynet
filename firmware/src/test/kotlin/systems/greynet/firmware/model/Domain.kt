package systems.greynet.firmware.model

data class Domain(
    val name: String,
    val type: BlockType,
)

enum class BlockType {
    BLOCKED,
    THROTTLED,
}
