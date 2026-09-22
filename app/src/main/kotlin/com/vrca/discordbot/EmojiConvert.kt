package com.vrca.discordbot

import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap

/**
 * Lets Cardinal use emojis the way a person does — type `:name:` and have it render.
 *
 *  - **Custom server emojis**: captured from each `GUILD_CREATE` (name → id + animated flag). A
 *    `:name:` matching one becomes Discord's real `<:name:id>` (`<a:name:id>` animated), which a
 *    bot message DOES render (the raw `:name:` a bot sends does NOT auto-resolve for custom).
 *  - **Standard shortcodes**: a curated `:smile:`→😄 table so common unicode shortcodes render too.
 *
 * [convert] rewrites message text; [reactionToken] maps a name to the `PUT reactions` token
 * (`name:id` for custom, the raw unicode char for standard). The custom map is process-global (a
 * bot is usually in one server here); [putGuildEmojis] is idempotent.
 */
object EmojiConvert {
    data class Ref(val id: String, val animated: Boolean)

    /** lowercased emoji name → custom emoji ref (per server, merged process-wide). */
    private val custom = ConcurrentHashMap<String, Ref>()

    /** Ingest a guild's `emojis` array (from GUILD_CREATE / GUILD_EMOJIS_UPDATE). */
    fun putGuildEmojis(emojis: JSONArray?) {
        if (emojis == null) return
        for (i in 0 until emojis.length()) {
            val e = emojis.optJSONObject(i) ?: continue
            val name = e.optString("name").trim(); val id = e.optString("id").trim()
            if (name.isBlank() || id.isBlank()) continue
            custom[name.lowercase()] = Ref(id, e.optBoolean("animated", false))
        }
    }

    fun customNames(): List<String> = custom.keys.sorted()
    fun clear() = custom.clear()

    private val SHORTCODE_RE = Regex(":([a-zA-Z0-9_+-]{1,40}):")

    /** Rewrite every `:name:` in [text] to a renderable form (custom id, else unicode, else leave). */
    fun convert(text: String): String {
        if (text.isBlank() || !text.contains(':')) return text
        return SHORTCODE_RE.replace(text) { m ->
            val name = m.groupValues[1].lowercase()
            custom[name]?.let { return@replace if (it.animated) "<a:${m.groupValues[1]}:${it.id}>" else "<:${m.groupValues[1]}:${it.id}>" }
            STANDARD[name]?.let { return@replace it }
            m.value   // unknown → leave the literal text alone
        }
    }

    /** The token to PUT as a reaction for a bare emoji NAME: `name:id` (custom) or the unicode char. */
    fun reactionToken(nameOrChar: String): String {
        val n = nameOrChar.trim().trim(':').lowercase()
        custom[n]?.let { return "${nameOrChar.trim().trim(':')}:${it.id}" }
        STANDARD[n]?.let { return it }
        return nameOrChar.trim()   // already a unicode emoji
    }

    // A pragmatic subset of the common unicode shortcodes chat regulars actually type.
    private val STANDARD: Map<String, String> = mapOf(
        "smile" to "😄", "smiley" to "😃", "grin" to "😁", "laughing" to "😆", "joy" to "😂",
        "rofl" to "🤣", "sob" to "😭", "cry" to "😢", "sweat_smile" to "😅", "wink" to "😉",
        "blush" to "😊", "heart_eyes" to "😍", "kissing_heart" to "😘", "yum" to "😋",
        "sunglasses" to "😎", "smirk" to "😏", "neutral_face" to "😐", "expressionless" to "😑",
        "unamused" to "😒", "roll_eyes" to "🙄", "flushed" to "😳", "pensive" to "😔",
        "confused" to "😕", "worried" to "😟", "grimacing" to "😬", "cold_sweat" to "😰",
        "weary" to "😩", "tired_face" to "😫", "triumph" to "😤", "rage" to "😡", "angry" to "😠",
        "sleeping" to "😴", "sleepy" to "😪", "dizzy_face" to "😵", "mask" to "😷",
        "thinking" to "🤔", "shrug" to "🤷", "facepalm" to "🤦", "skull" to "💀", "ghost" to "👻",
        "clown" to "🤡", "poop" to "💩", "fire" to "🔥", "eyes" to "👀", "100" to "💯",
        "tada" to "🎉", "sparkles" to "✨", "star" to "⭐", "zap" to "⚡", "boom" to "💥",
        "thumbsup" to "👍", "+1" to "👍", "thumbsdown" to "👎", "-1" to "👎", "ok_hand" to "👌",
        "clap" to "👏", "raised_hands" to "🙌", "pray" to "🙏", "muscle" to "💪", "wave" to "👋",
        "point_up" to "☝️", "v" to "✌️", "crossed_fingers" to "🤞", "handshake" to "🤝",
        "heart" to "❤️", "broken_heart" to "💔", "sparkling_heart" to "💖", "purple_heart" to "💜",
        "yellow_heart" to "💛", "green_heart" to "💚", "blue_heart" to "💙", "black_heart" to "🖤",
        "white_check_mark" to "✅", "heavy_check_mark" to "✔️", "x" to "❌", "warning" to "⚠️",
        "question" to "❓", "exclamation" to "❗", "rocket" to "🚀", "trophy" to "🏆",
        "gift" to "🎁", "crown" to "👑", "gem" to "💎", "moneybag" to "💰", "coffee" to "☕",
        "beer" to "🍺", "pizza" to "🍕", "cake" to "🍰", "salt" to "🧂", "cat" to "🐱",
        "dog" to "🐶", "frog" to "🐸", "snake" to "🐍", "robot" to "🤖", "alien" to "👽",
        "moyai" to "🗿", "sunny" to "☀️", "cloud" to "☁️", "snowflake" to "❄️", "zzz" to "💤",
        "no_mouth" to "😶", "melting_face" to "🫠", "salute" to "🫡", "pleading_face" to "🥺",
        "star_struck" to "🤩", "partying_face" to "🥳", "nauseated_face" to "🤢", "cursing_face" to "🤬",
        "cowboy" to "🤠", "nerd" to "🤓", "upside_down_face" to "🙃", "hugging_face" to "🤗",
    )
}
