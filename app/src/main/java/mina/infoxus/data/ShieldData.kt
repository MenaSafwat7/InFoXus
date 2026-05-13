package mina.infoxus.data

enum class MatchType { TEXT, VIEW_ID, CONTENT_DESC }
enum class ShieldAction { BLOCK, REQUIRE_PIN }

// ShieldRule: Data class for generic node blocking. 
// Annotations removed until Room dependencies are added to the project.
data class ShieldRule(
    val id: Int = 0,
    val packageName: String,
    val matchType: MatchType,
    val matchValue: String,
    val action: ShieldAction,
    val isActive: Boolean = true
)

sealed class ShieldEvent {
    data class UNINSTALL_BLOCKED(val packageName: String) : ShieldEvent()
    data class TIME_LIMIT_HIT(val packageName: String, val limitMs: Long) : ShieldEvent()
    data class BUTTON_BLOCKED(val packageName: String, val matchedRule: ShieldRule) : ShieldEvent()
    object SELF_DISABLE_BLOCKED : ShieldEvent()
}

data class ShieldConfig(
    val uninstallProtectionEnabled: Boolean = true,
    val timeLimitsEnabled: Boolean = true,
    val buttonBlockingEnabled: Boolean = true,
    val selfProtectionEnabled: Boolean = true,
    val overlayEnabled: Boolean = true
)

data class AttentionSpanVideoItem(val elapsedTime: Float, val time: String, val type: Int = 1)
const val VIDEO_TYPE_REEL = 1
