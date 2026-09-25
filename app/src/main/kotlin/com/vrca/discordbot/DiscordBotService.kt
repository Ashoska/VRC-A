package com.vrca.discordbot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.vrca.BuildConfig
import com.vrca.R
import com.vrca.app.MainActivity
import com.vrca.app.startForegroundSafely
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Admin-only foreground service running **Cardinal** — a self-hosted Discord gateway bot with a
 * self-grown personality, per-user memory, shared server culture, a revivable topic archive, cheap
 * event-driven routing, and a real Cloudflare neuron budget.
 *
 * Per message: a FREE heuristic decides ignore / react / reply / consider-ambient. An addressed
 * message goes straight to the reply model [DiscordBotAi.reply], whose prompt carries only what this moment
 * needs (who it's answering, the channel, and — when relevant — people named or talking, server
 * memories, the earlier-conversation summary). An ambient moment pays one cheap 8B
 * [DiscordBotAi.director] (at most every [DiscordBotLimits.DIRECTOR_MIN_GAP_MS] per channel). Memory is
 * written OFF the reply path by the 8B learn pass ([DiscordBotAi.observe]), which reads EVERY message
 * since the previous pass in batches, with the learned facts checked against what was actually said.
 * A per-channel reply mutex keeps it fluid; exact per-call neuron billing drives a daily budget that
 * degrades gracefully. Custom + standard emojis render via [EmojiConvert].
 */
class DiscordBotService : Service() {

    companion object {
        private const val TAG = "DiscordBotService"
        const val ACTION_START = "com.vrca.DISCORD_BOT_START"
        const val ACTION_STOP = "com.vrca.DISCORD_BOT_STOP"

        private const val NOTIF_CHANNEL = "vrca_discord_bot"
        private const val NOTIF_ID = 1010

        // GUILDS(1) | GUILD_EMOJIS(1<<3) | GUILD_MESSAGES(1<<9) | GUILD_MESSAGE_REACTIONS(1<<10) |
        // DIRECT_MESSAGES(1<<12) | MESSAGE_CONTENT(1<<15) = 38409
        private const val INTENTS = 1 or 8 or 512 or 1024 or 4096 or 32768

        private const val MAX_BACKOFF_MS = 30_000L
        private val URL_RE = Regex("""https?://\S+""")
        // "Stop" aimed at Cardinal: the whole (punctuation-free) message is a stop request ("stop",
        // "ok shut up pls", "go away"), or it contains an unmistakable one ("stop replying", "leave me
        // alone") that isn't negated ("don't stop", "can't shut up"). "stop by the event" is neither.
        private val STOP_WHOLE_RE = Regex("^((ok|okay|pls|please|just|bro|dude|omg|god|cardinal|lol|seriously|nah|no|yo|hey|man|now) )*" +
            "(stop|stop it|stop that|stop talking|stop replying|stop responding|stop pinging|stop now|shut up|shutup|shush|hush|" +
            "be quiet|go away|leave me alone|not now|quit it|enough|stfu|pipe down)" +
            "( (please|pls|now|cardinal|bro|dude|lol|already|omg|man|thanks|thx))*$")
        private val STOP_PHRASE_RE = Regex("\\b(stop (replying|responding|talking|pinging|messaging|posting)|shut up|stfu|leave me alone|go away|be quiet|pipe down)\\b")
        private val NEGATIONS = setOf("don't", "dont", "never", "can't", "cant", "won't", "wont", "not", "didn't", "didnt",
            "couldn't", "couldnt", "shouldn't", "shouldnt", "wouldn't", "wouldnt", "doesn't", "doesnt")
        // Short or everyday-word nicknames ("ali", "boss", "mom") only count as naming someone when the
        // message clearly points at a person (@name, name's, a question) — otherwise they pulled a
        // card into unrelated messages.
        private val COMMON_NICK_WORDS = setOf(
            "boss", "mom", "mum", "dad", "bro", "bruh", "dude", "man", "king", "queen", "babe", "baby", "sis", "kid",
            "chief", "doc", "cap", "captain", "champ", "buddy", "pal", "mate", "sir", "lady", "love", "hun", "bestie",
            "homie", "fam", "friend", "guy", "girl", "boy", "gamer", "legend", "goat", "daddy", "mommy", "papa", "mama",
            "honey", "sweetie", "sugar", "angel", "bear", "bunny", "cat", "dog", "fox", "wolf", "cutie", "nerd", "noob",
            "god", "lord", "master", "admin", "mod", "owner", "boo", "bae", "chat", "team", "crew", "gang",
        )
        // Someone asking Cardinal to recall a person / event / memory (drives full-card injection).
        private val RECALL_RE = Regex("(?i)(who is|who'?s |what do you know|know about|tell me about|info(rmation)? (on|about)|(^|\\b(you|u|hey|yo|cardinal|do you|dyou)\\W+)remember (when|that|the|how|what|who)|do you (know|remember)|did (you|u) (see|hear|catch) (what|that|the|about)|(you|u) hear about|about (him|her|them|me)\\b|from your (profile|memory|notes)|in your (profile|memory))")
        // Questions about the asker themself ("what do you remember about me", "what do i do again").
        private val SELF_RECALL_RE = Regex("(?i)(about me\\b|know about me|my (profile|info|memory)|remember me|who am i|what am i like|" +
            "where (do i live|am i from)|what('?s| is) my (job|name|cat|dog|pet|game|hobby|work|thing|deal)\\b|" +
            "what do i do( again| for (a )?living| for work)?\\s*(lol|lmao|haha)?\\s*\\??\\s*$)")
        // "who runs the events here?" — a question about people with nobody named → search the cards.
        private val WHO_Q_RE = Regex("(?i)(^|\\s)(who|whose)\\b[^?]*\\?")
        private val CALL_ME_RE = Regex("(?i)\\b(just call me|you can call me|call me|i go by|everyone calls me|" +
            "(?:update|change|set|switch|make) my (?:nick ?name|name) (?:to|as)|my (?:new )?nick ?name is(?: now)?)\\s+([\\p{L}\\p{N}_]{2,32})")
        private const val NICK_NOTE_MS = 15 * 60_000L   // "you didnt do it" a few minutes later: he knows it's done
        private val SELF_STATED_RE = Regex("\\b(i'?m|im|i am) (an? )([\\p{L}]{3,20}(?: [\\p{L}]{3,20})?)(?=\\W|$)")
        private val SELF_STATED_TAIL = setOf("and", "but", "for", "too", "lol", "now", "who", "that", "the", "with", "at", "in",
            "lmao", "fr", "btw", "tho", "bit", "lot", "little", "big", "huge", "fan", "mess", "joke", "genius", "idiot")
        private val STANDS_BY_RE = Regex("\\b(yes|yeah|yea|yep|ya)\\b.{0,12}\\b(i am|i'?m|i do|i swear)\\b|\\bi (really|actually|literally) am\\b|\\bask anyone\\b|\\bi swear\\b")
        private val DETAIL_ASK_RE = Regex("(?i)\\b(explain|describe|tell (me|us) (about|more|everything|what)|walk (me|us) through|" +
            "help (me|us)|list|give (me|us) (a|some|the)|write|recommend|suggest|compare|summari[sz]e|story|details?|in depth|step by step)\\b")
        private val SLANG = mapOf(
            "peak" to "that's great / the best (they love it)", "goated" to "the best (praise)", "goat" to "the best ever (praise)",
            "bussin" to "really good", "based" to "respectably bold (approval)", "ate" to "nailed it (praise)",
            "slay" to "did great (praise)", "valid" to "fair / approved", "w" to "a win (approval)", "dub" to "a win (approval)",
            "mid" to "mediocre (a dig)", "l" to "a loss (a dig)", "cooked" to "done for / in trouble", "ratio" to "your take got outvoted (a dig)",
            "cap" to "a lie", "nocap" to "no lie, for real", "fr" to "for real (agreeing)",
            "sus" to "suspicious", "lowkey" to "kind of / secretly", "highkey" to "openly / very", "ong" to "on god, for real",
            "deadass" to "seriously", "bet" to "ok, deal", "rizz" to "charm / flirting skill", "delulu" to "delusional (teasing)",
            "iykyk" to "an inside joke", "sheesh" to "wow (impressed)", "yapping" to "talking too much", "yap" to "talk too much")
        private val LIKE_Q_RE = Regex("(?i)\\bdo (?:you|u|ya) (like|love|enjoy|rate|dig|hate) ([\\p{L}\\p{N}' .-]{2,30}?)\\s*\\?")
        private val TASTE_YES = Regex("^\\W*(yes|yeah|yea|yep|ya|obviously|of course|ofc|duh|absolutely|definitely|100|always|hell yeah|i love|love it|love that|big fan|who doesn'?t)\\b")
        private val TASTE_NO = Regex("^\\W*(no|nah|nope|never|hell no|absolutely not|ew|eww|gross|i hate|hate it|not really|can'?t stand)\\b")
        private val LEARNER_EXAMPLES = listOf("thinks every new movie is overrated", "roasts anyone who posts their music taste", "critic")
        private val REACT_CMD_RE = Regex("(?i)^\\W*(?:(?:can|could|would|will) (?:you|u) |please |pls |plz |now |go |ok |just )*react\\b|" +
            "\\breact (?:with|to my|on my|to this|to that)\\b|\\b(?:add|leave|give|put) (?:a|an|me a|my message a) react(?:ion)?\\b")
        private val REACT_NAMED_RE = Regex("(?i)\\bwith (?:an? |the |some )?([\\p{L}][\\p{L} _-]{1,30}?)(?:\\s+(?:emoji|emote|reaction|react)\\b|\\s+(?:to|on)\\b|\\W*$)")
        private const val TONE_MS = 2 * 60 * 60_000L
        private val YOU_Q_RE = Regex("(?i)\\b(you|your|u|ur|ya|you'?re|youre)\\b.*(\\?|$)|\\b(how|what|why|where|when|wyd|hbu|wbu)\\b.*\\b(you|your|u|ur)\\b")
        private val TONE_NICE_RE = Regex("(?i)\\b(be|talk|speak|act) (a (lil|little|bit) )?(nicer|nice|kinder|kind|sweeter|sweet|wholesome|friendlier|friendly|nicely|gentler|gentle)\\b|" +
            "\\blighten (it|the (chat|mood|vibe)|this|things|that chat) up\\b|\\bless (mean|rude|sassy|toxic|harsh)\\b|\\bstop (being|roasting) (so )?(mean|rude|harsh)?|" +
            "\\btalk nicely\\b|\\bchange the (vibe|mood|tone)\\b|\\bpositive vibes\\b|\\bno more roast")
        private val TONE_SERIOUS_RE = Regex("(?i)\\b(be|get) serious\\b|\\bno jokes?\\b|\\bfor real (now|tho)\\b|\\bserious (question|talk|mode)\\b")
        private val TONE_CALM_RE = Regex("(?i)\\b(calm|chill) (down|out)\\b|\\btone it down\\b")
        private val TONE_NORMAL_RE = Regex("(?i)\\bbe (mean|sassy|savage|yourself)( again)?\\b|\\b(go )?back to normal\\b|\\bnormal again\\b|" +
            "\\b(you can|go) roast\\b|\\broast (him|her|them|me|us)\\b|\\bstop being (so )?(nice|soft|polite)\\b|\\bbring back the sass\\b")
        private fun toneFor(text: String): String? = when {
            TONE_NORMAL_RE.containsMatchIn(text) -> "normal"
            TONE_NICE_RE.containsMatchIn(text) -> "nice"
            TONE_SERIOUS_RE.containsMatchIn(text) -> "serious"
            TONE_CALM_RE.containsMatchIn(text) -> "calm"
            else -> null
        }
        private val TONE_TEXT = mapOf(
            "nice" to "The chat asked you to change the vibe: keep it light, warm and friendly — no insults, no roasts; teasing only if it's clearly affectionate.",
            "serious" to "The chat wants it serious: drop the jokes and sass for now, answer plainly and kindly.",
            "calm" to "The chat asked you to calm down: low-key and easygoing, no heat.")
        private val ASKING_RE = Regex("(?i)\\?\\s*$|^\\W*(do|does|did|are|is|was|were|can|could|would|will|have|has|what|why|how|who|where|when|which)\\b")
        private val REACT_GIVE_RE = Regex("(?i)^\\W*(?:can you |could you |pls |please |just )?(?:give|send|drop|hit) (?:me|us|this|it) (?:an? |the |one )?([\\p{L} ]{2,25}?)(?: emoji| emote| react(?:ion)?)?(?: (?:pretty )?(?:please|pls|plz))?\\W*$")
        private val REACT_WITH_FRAGMENT_RE = Regex("(?i)^\\W*(?:with |use )(?:an? |the |one )?([\\p{L} ]{2,25}?)(?: emoji| one)?\\W*$")
        private val POLITICS_RE = Regex("(?i)\\b(israel\\w*|palestin\\w*|gaza|hamas|zionis\\w*|ukrain\\w*|russia|putin|trump|biden|kamala|maga|" +
            "democrats?|republicans?|liberals?|conservatives?|communis\\w*|nazis?|abortion|politic\\w*|election|epstein|elon|musk|obama|congress)\\b")
        private val NOT_EMOJI_WORDS = setOf("you", "u", "me", "it", "this", "that", "him", "her", "them", "us", "one", "some", "something",
            "anything", "a", "an", "the", "my", "your", "our", "back", "up", "more", "again", "too", "please", "pls")
        private val SIGNOFF_RE = Regex("(?i)\\b(say|tell (the chat|everyone|us|them|everybody))\\b|\\b(good ?night|gn|goodbye|bye|cya|see ya|farewell)\\b")
        private val CALL_ME_STOP = setOf("when", "later", "back", "out", "if", "tomorrow", "sometime", "maybe", "that", "a", "an", "the", "it", "him", "her", "anything", "crazy", "whatever")
        private val CALL_ME_NOT_RE = Regex("(?i)\\b(stop|quit|don'?t|do not|never|no more) call(?:ing)? me ([\\p{L}\\p{N}_]{2,32})")
        private const val PING_ONLY = "(they pinged you with no message)"
        private val QUESTION_RE = Regex("(?i)\\?|\\b(why|what|how|who|which|where|when|would you|do you|are you|can you|you think)\\b|" +
            "\\b(right|yeah|huh|eh|innit|no|agree)\\W*$")   // tag questions: "cheese is elite though right"
        // Talking to the whole room, not one person ("guys", "anyone", "y'all").
        private val ROOM_RE = Regex("\\b(guys|everyone|everybody|y'?all|yall|anyone|anybody|you all|chat|people|@here|@everyone)\\b")
        // Asking about Cardinal himself: his job, role, what he's known for.
        /** Asking for a rating or a pick — the model likes to dodge these ("hard pass"), which reads as a refusal. */
        /** Asked the time, date or his timezone: he only knows it if the prompt tells him (he made up "EST"). */
        private val TIME_ASK_RE = Regex("(?i)(time ?zone|\\btz\\b|what time|what'?s the time|\\bthe time (is it|there|for you)|what (day|date)|today'?s date|\\b(utc|gmt)\\b)")

        private fun todayLine(now: Long): String =
            java.time.format.DateTimeFormatter.ofPattern("EEE d MMM yyyy", java.util.Locale.ENGLISH)
                .format(java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneOffset.UTC))
        private fun clockLine(now: Long): String {
            val t = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneOffset.UTC)
            val f = java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM yyyy, HH:mm", java.util.Locale.ENGLISH)
            return "It's ${f.format(t)} UTC. Your timezone is UTC."
        }

        /** Snowflake → epoch millis. */
        private fun snowflakeMs(id: String): Long = (id.toLongOrNull() ?: 0L).let { if (it <= 0L) 0L else (it ushr 22) + 1420070400000L }

        /** The newest message id that belongs to an OLDER conversation (0 = none): walking back from the newest id,
         *  the first message followed by a quiet gap longer than CONVO_GAP_MS, and everything before it. */
        internal fun staleCutoff(ids: List<String>): Long {
            val sorted = ids.mapNotNull { it.toLongOrNull() }.filter { it > 0 }.distinct().sortedDescending()
            for (i in 1 until sorted.size) {
                val newer = snowflakeMs(sorted[i - 1].toString()); val older = snowflakeMs(sorted[i].toString())
                if (newer - older > DiscordBotLimits.CONVO_GAP_MS) return sorted[i]
            }
            return 0L
        }

        private val VERDICT_ASK_RE = Regex("(?i)(\\brate\\b|\\brank\\b|\\b(1|one) ?(-|–|to) ?10\\b|out of (10|ten)|on a scale|\\bpick (one|between)|\\bchoose (one|between)|would (you|u) rather|smash or pass|which (one )?(is|would you|do you)|\\bwho wins\\b|\\b(better|worse)[,:]? .{1,30}\\bor\\b|" +
            // "Cardinal Yuri or Yaoi?" — a bare this-or-that (he dodged with "zero of the above"), and the
            // "…answer" / "just pick" nudge after a dodge.
            "^\\W*(?:(?:hey |yo |ok |so )?cardinal[,:]?\\s+)?[\\p{L}\\p{N}' .-]{1,30} or [\\p{L}\\p{N}' .-]{1,30}\\?+\\W*$|" +
            "\\b(just )?(answer|pick|choose)( it| one| the question| already)?\\W*$|" +
            // pressed for an answer: "do you?" / "well?" / "yes or no"
            "^\\W*(do|would|will|are|did|have) (you|u)\\?+\\W*$|^\\W*(well|so|answer me|yes or no)\\W*\\??\\W*$)")
        /** "you're the (server's) (official) X now" / "cardinal is our resident X" → X (1-3 words). */
        private val TITLE_RE = Regex("(?i)\\b(?:you'?re|you are|ur|u r|cardinal(?:'s| is))\\s+(?:now\\s+)?(?:(?:the|our|this|a)\\s+)(?:(?:server|chat|group)'?s\\s+)?(?:(?:official|new|resident|designated|certified|local)\\s+)?(\\p{L}+(?:\\s+\\p{L}+){0,2}?)(?=\\s+(?:now|here|forever|lol|lmao|fr|officially)\\b|\\s*[,.!?]|\\s*$)")
        private val TITLE_NOT = setOf("so", "kinda", "really", "too", "very", "such", "not", "being", "gonna", "going", "just", "worst", "best", "same", "only", "one", "reason", "problem", "bot", "ai")
        private val SELF_ASK_RE = Regex("(?i)\\b(your (job|role|title|thing|deal|purpose|vibe|personality|gimmick)|(are|r) (you|u) known for|who (are|r) (you|u)\\b|about yourself|what (are|r) (you|u) (like|about)|what do (you|u) do (here|around here))")
        private val NOW_RE = Regex("\\b(rn|atm|right now|at the moment|as we speak|tonight|this (morning|afternoon|evening)|for now|in a (bit|while)|for a bit|lately|been (so |really |super )?busy)\\b")
        private val BEING_DONE_RE = Regex("\\b(is|are|getting|being)\\s+(being\\s+)?[\\p{L}]+(ed|en)\\s+(rn|right now|atm|today|at the moment)\\b|\\bbeing (fixed|repaired|redone|renovated|replaced|done|built|painted|cleaned)\\b")
        private val WHAT_DOING_RE = Regex("\\b(wbu|hbu|wby|wyd|whatcha|what about (you|u)|how about (you|u)|what (are|r) (you|u) (up to|doing)|what('?s| is) up|what you (up to|doing)|what u (up to|doing)|up to anything)\\b")
        private val HYPOTHETICAL_RE = Regex("(?i)^\\W*(if|imagine|what if|pretend|say|suppose|hypothetically)\\b|\\bif i (had|was|were|could|lived)\\b|\\bi wish\\b|\\bwould(n'?t)? (you|u)\\b")
        private val RELATIVE_RE = Regex("\\b(my|our)\\s+(brother|sister|bro|sis|mom|mum|mother|dad|father|parents?|friend|bestie|cousin|uncle|aunt|bf|gf|boyfriend|girlfriend|wife|husband|partner|son|daughter|kid|roommate|coworker|boss|neighbou?r|grandma|grandpa|nan|family|creator|maker|dev|developer|owner)\\b")
        private val NOT_VERB_ING = setOf("nothing", "something", "anything", "everything", "morning", "evening", "thing",
            "things", "king", "during", "ceiling", "building", "clothing", "boring", "amazing", "interesting", "spring",
            "string", "ring", "wing", "bring", "sing", "ping", "darling", "feeling", "pudding", "wedding", "earring", "sibling")
        private val STATE_VERBS = setOf("lives", "works", "likes", "loves", "hates", "enjoys", "owns", "plays", "uses",
            "wants", "needs", "prefers", "speaks", "goes", "has", "does", "gets", "keeps", "seems", "knows", "thinks",
            "feels", "believes", "comes", "stays", "was", "is", "dislikes", "adores", "mains", "misses", "wears", "calls")
        // Everyday address words — never a nickname for the person who said them.
        private val NICK_SLANG = setOf("bruv", "bro", "bruh", "brah", "dude", "mate", "fam", "man", "sis", "girl", "king",
            "queen", "buddy", "pal", "homie", "chief", "boss", "lad", "lads", "fella", "bud", "cuz", "g", "boi", "boy",
            "guys", "gang", "chat", "bestie", "babe", "hun", "sir", "maam", "ma'am", "gamer", "legend", "goat", "champ",
            "my guy", "my man", "brother", "bestie", "pookie", "twin", "gng", "blud", "mans", "yall", "y'all")
        private val JOKE_RE = Regex("(?i)\\b(lol+|lmf?ao+|rofl|ha(ha)+|he(he)+|xd+|jk|/j|kidding|just kidding)\\b|😂|🤣|😭|💀|😆|🙃|😜")
        private val SERIOUS_RE = Regex("(?i)\\b(genuinely|seriously|for real|not joking|no joke|i mean it|please|pls|plz|actually annoying)\\b")
        private val ENCOURAGE_RE = Regex("(?i)\\b(keep going|keep it up|love (it|this|that)|don'?t stop|never stop|more of this|so good|iconic|canon|lives rent free|we need more)\\b")
        // Someone telling Cardinal to cut something out.
        private val COMPLAINT_RE = Regex("(?i)\\b(stop|drop it|enough|annoying|cringe|got old|getting old|quit|not funny|over it|tired of|shut up about|so done|give it a rest)\\b")
        // A correction, a dispute or a complaint somewhere in a learn batch → correction mode.
        private val CORRECTION_RE = Regex("(?i)\\b(not true|isn'?t true|that'?s (wrong|false|a lie|not true|cap|made up)|you'?re wrong|wrong about|" +
            "no (he|she|they|i|you|we) (don'?t|doesn'?t|isn'?t|aren'?t|never|didn'?t|ain'?t|is not|are not)|" +
            "(he|she|they|i) (doesn'?t|don'?t|isn'?t|aren'?t|never|no longer|stopped|quit|moved)|not anymore|no longer|anymore|used to|actually|" +
            "stop (calling|saying|doing|with|being|the|that)|don'?t call me|quit (calling|it|that|the|with)|" +
            "(hate|hated|don'?t like|dont like|can'?t stand) (it )?when|cringe|annoying|not funny|you'?re not|you aren'?t|" +
            "divorc\\w*|lying|liar|made that up|never said|fake)\\b")
        private const val EMOJI_PREFS = "vrca_discord_emoji"
        // Explicit "react to my message with X" request → react, no reply.
        private val REACT_REQ_RE = Regex("(?i)^\\s*(can you |could you |please |pls |plz )?react\\b|react (to )?(this|that|my|the)\\b")
        private val TRAILING_EMOJI_RE = Regex("(\\s*(:[a-zA-Z0-9_]{2,32}:|<a?:[a-zA-Z0-9_]{2,32}:\\d+>|[\\uD83C-\\uDBFF][\\uDC00-\\uDFFF]|[\\u2600-\\u27BF]\\uFE0F?))+\\s*$")
        private val SHORTCODE_RE = Regex(":([a-zA-Z0-9_]{2,32}):|<a?:([a-zA-Z0-9_]{2,32}):\\d+>")
        private val UNICODE_EMOJI_RE = Regex("[\\uD83C-\\uDBFF][\\uDC00-\\uDFFF]|[\\u2600-\\u27BF\\u2B00-\\u2BFF\\u2190-\\u21FF\\u2900-\\u297F]")

        fun start(context: Context) {
            if (!BuildConfig.IS_ADMIN_BUILD) return
            context.startService(Intent(context, DiscordBotService::class.java).apply { action = ACTION_START })
        }

        fun stop(context: Context) {
            context.startService(Intent(context, DiscordBotService::class.java).apply { action = ACTION_STOP })
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val okClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    @Volatile private var webSocket: WebSocket? = null
    /** Bumped for every new socket AND whenever we abandon one, so late callbacks from an old
     *  socket (its close/failure can land seconds later) can never touch the live connection's state. */
    private val socketGen = java.util.concurrent.atomic.AtomicInteger(0)
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var budgetJob: Job? = null

    // Gateway session state
    @Volatile private var lastSeq: Int? = null
    @Volatile private var sessionId: String? = null
    @Volatile private var resumeUrl: String? = null
    @Volatile private var botId: String = ""
    @Volatile private var botName: String = ""
    @Volatile private var heartbeatIntervalMs: Long = 41_250L
    @Volatile private var ackPending: Boolean = false
    @Volatile private var reconnectAttempt: Int = 0

    private lateinit var cfg: DiscordBotStore.Config

    // Routing state
    private val ambientCooldown = ConcurrentHashMap<String, Long>()     // channel -> last ambient reply ms
    private val lastBotPostMs = ConcurrentHashMap<String, Long>()        // channel -> last bot post ms
    private val perUserReplyAt = ConcurrentHashMap<String, Long>()       // channel:user -> last reply ms
    // Conversation following: who Cardinal is talking with right now (channel:user -> last exchange ms),
    // how many follow-up checks in a row said "not to him", and the recent message flow per channel.
    private val convoWith = ConcurrentHashMap<String, Long>()
    private val convoMisses = ConcurrentHashMap<String, Int>()
    private class FlowMsg(
        val id: String, val authorId: String, val isBot: Boolean, val replyTo: String?, @Volatile var toBot: Boolean,
        val name: String = "", val ts: Long = System.currentTimeMillis(), val text: String = "",
        val theirText: String = "",   // for his replies: the message he was answering
    )
    private val flow = ConcurrentHashMap<String, ArrayDeque<FlowMsg>>()
    private val channelMutex = ConcurrentHashMap<String, Mutex>()        // channel -> reply serialiser
    private val backoffUntil = ConcurrentHashMap<String, Long>()         // channel -> back-off deadline
    private val backoffStopMsg = ConcurrentHashMap<String, String>()     // channel -> id of the "stop" message
    private val nickSetAt = ConcurrentHashMap<String, Pair<Long, String>>()
    private val reactReqAt = ConcurrentHashMap<String, Long>()   // channel:user -> when they last asked him to react
    // channel -> (person -> when, tone they asked for). The tone most people here asked for recently wins
    // ("normal" counts as a vote too), so the room — not one person — decides how he talks.
    private val toneVotes = ConcurrentHashMap<String, ConcurrentHashMap<String, Pair<Long, String>>>()
    private fun effectiveTone(channelId: String, now: Long): String? {
        val votes = toneVotes[channelId]?.values?.filter { now - it.first < TONE_MS } ?: return null
        if (votes.isEmpty()) return null
        val counts = votes.groupingBy { it.second }.eachCount()
        val top = counts.values.max()
        val winner = votes.filter { counts[it.second] == top }.maxBy { it.first }.second
        return TONE_TEXT[winner]
    }   // user -> when they asked for a new name + the name
    private val learnPending = ConcurrentHashMap<String, Int>()          // channel -> msgs since the last learn pass
    private val lastLearnAt = ConcurrentHashMap<String, Long>()          // channel -> last learn pass started
    private val lastLearnedId = ConcurrentHashMap<String, Long>()        // channel -> newest message id a pass has read
    private val learnInFlight = ConcurrentHashMap.newKeySet<String>()    // channels with a pass running
    @Volatile private var digestInFlight = false
    private val digestTriedAt = ConcurrentHashMap<String, Long>()           // day -> last recap attempt
    private val learnLullJobs = ConcurrentHashMap<String, Job>()         // channel -> pending "went quiet" pass
    private val directorAt = ConcurrentHashMap<String, Long>()           // channel -> last director call
    private val ambientReactAt = ConcurrentHashMap<String, Long>()       // channel -> last unprompted reaction
    private val activityWindow = ConcurrentHashMap<String, ArrayDeque<Long>>() // channel -> recent msg times
    private val recentBotReplies = ConcurrentHashMap<String, ArrayDeque<String>>() // channel -> last replies
    private val recentEmojiUse = ConcurrentHashMap<String, ArrayDeque<Boolean>>() // channel -> did each of his last replies use an emoji
    private val lastCoveredId = ConcurrentHashMap<String, Long>()        // channel -> max message id a reply has already seen/answered
    private val routeJobs = ConcurrentHashMap.newKeySet<Job>()           // in-flight route coroutines (cancelled on teardown)
    // Our recent message ids (reaction-learning): id -> the user we replied to.
    private val recentBotMsgIds = object : LinkedHashMap<String, String>(64, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 80
    }

    private data class MsgCtx(
        val channelId: String, val messageId: String, val authorId: String, val authorName: String,
        val userText: String, val addressed: Boolean,
        val refTurn: DiscordBotAi.Turn?, val refId: String?,
        val hasImage: Boolean, val refChannels: List<String>,
        val named: Boolean = false,   // talks ABOUT Cardinal by name ("cardinal is kinda mid") without addressing him
        val followUp: Boolean = false, // someone he's talking with wrote again without @ — check if it's still to him
        val freeFollow: Boolean = false, // a follow-up the free rules already settled as to him
        val signOff: Boolean = false,   // "say goodnight to the chat and stop responding": one sign-off line, then quiet
    )

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                DiscordBotStore.setEnabled(this, false)
                teardown("Stopped")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                if (intent == null &&
                    (com.vrca.app.AppShutdown.isManualKillFresh(this) ||
                        com.vrca.app.AppShutdown.isSwipedAway(this))) {
                    stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY
                }
                if (!BuildConfig.IS_ADMIN_BUILD) { stopSelf(); return START_NOT_STICKY }
                if (intent == null && !DiscordBotStore.isEnabled(this)) {
                    stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY
                }

                cfg = DiscordBotStore.load(this)
                DiscordBotState.configureLadder(
                    cfg.dailyBudget, cfg.trimEnabled, cfg.cheapEnabled, cfg.reactOnlyEnabled, cfg.hardStopEnabled)
                if (!cfg.isComplete) {
                    DiscordBotState.setStatus(
                        DiscordBotState.Status.FAILED,
                        "Add your bot token + Cloudflare account id and Workers AI token"
                    )
                    stopSelf(); return START_NOT_STICKY
                }

                val started = startForegroundSafely(NOTIF_ID, buildNotif("Connecting…"), TAG)
                if (!started) return START_NOT_STICKY

                if (webSocket == null) {
                    DiscordBotStore.setEnabled(this, true)
                    DiscordBotState.setRunning(true)
                    DiscordBotState.setStatus(DiscordBotState.Status.CONNECTING, "Connecting to Discord…")
                    DiscordBotState.setMood(PersonalityStore.mood(this))
                    DiscordBotState.setNeuronsAbsolute(NeuronBudget.current(this))
                    DiscordBotState.log("Starting Cardinal")
                    DiscordBotAi.billingSink = { n -> chargeExact(n) }
                    EmojiConvert.restoreUsage(getSharedPreferences(EMOJI_PREFS, MODE_PRIVATE).getString("usage", null))
                    ConversationStore.restore(this)
                    reconnectAttempt = 0
                    openSocket(resume = false)
                    startBudgetLoop()
                }
                return START_STICKY
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        teardown("Destroyed")
        scope.cancel()
        super.onDestroy()
    }

    // ── Gateway connection ────────────────────────────────────────────────

    private fun openSocket(resume: Boolean) {
        val base = if (resume && resumeUrl != null) "${resumeUrl!!.trimEnd('/')}/?v=10&encoding=json" else BotEndpoints.gatewayUrl
        val req = Request.Builder().url(base).build()
        ackPending = false
        val gen = socketGen.incrementAndGet()
        val closeHandled = java.util.concurrent.atomic.AtomicBoolean(false)
        fun live() = gen == socketGen.get()
        webSocket = okClient.newWebSocket(req, object : WebSocketListener() {
            override fun onMessage(ws: WebSocket, text: String) {
                if (!live()) return
                try { handleFrame(text, resumeWanted = resume) } catch (_: Exception) { }
            }
            // Discord closed the socket: answer the close right away (OkHttp won't on its own) and
            // act on the code now, instead of sitting deaf until a heartbeat goes un-ACKed.
            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                try { ws.close(1000, null) } catch (_: Exception) { }
                if (live() && closeHandled.compareAndSet(false, true)) handleServerClose(code, reason)
            }
            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                if (live() && closeHandled.compareAndSet(false, true)) handleServerClose(code, reason)
            }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                if (!live() || !closeHandled.compareAndSet(false, true)) return
                DiscordBotState.log("Gateway error: ${t.message ?: t.javaClass.simpleName}")
                scheduleReconnect(resume = true)
            }
        })
    }

    /**
     * Act on a gateway close code. Codes Discord documents as "don't reconnect" (bad token, bad
     * shard/version, invalid or not-allowed intents) stop with a plain reason in the admin tab
     * instead of retrying forever; 4007/4009 need a fresh session; everything else resumes.
     */
    private fun handleServerClose(code: Int, reason: String) {
        val fatal = when (code) {
            4004 -> "Discord rejected the bot token (4004). Paste a fresh token in Config, then press Start."
            4010, 4011 -> "Discord needs sharding for this bot ($code). Cardinal doesn't support that."
            4012 -> "Discord rejected the gateway version (4012)."
            4013 -> "Discord rejected the bot's intents (4013)."
            4014 -> "Discord refused the Message Content intent (4014). Turn on MESSAGE CONTENT INTENT " +
                "(and SERVER MEMBERS if asked) in the Discord Developer Portal → Bot, then press Start."
            else -> null
        }
        if (fatal != null) {
            heartbeatJob?.cancel(); reconnectJob?.cancel()
            socketGen.incrementAndGet(); webSocket = null
            DiscordBotState.log("Gateway closed $code: $fatal")
            DiscordBotState.setStatus(DiscordBotState.Status.FAILED, fatal)
            return
        }
        if (code == 4007 || code == 4009) { sessionId = null; lastSeq = null }
        DiscordBotState.log("Gateway closed $code${if (reason.isNotBlank()) " ($reason)" else ""} — reconnecting")
        scheduleReconnect(resume = code != 1000 && code != 1001 && sessionId != null)
    }

    private fun handleFrame(text: String, resumeWanted: Boolean) {
        val frame = JSONObject(text)
        val op = frame.optInt("op", -1)
        if (!frame.isNull("s")) lastSeq = frame.optInt("s")
        when (op) {
            10 -> {
                heartbeatIntervalMs = frame.optJSONObject("d")?.optLong("heartbeat_interval")
                    ?.takeIf { it > 1000 } ?: 41_250L
                startHeartbeat()
                if (resumeWanted && sessionId != null && lastSeq != null) sendResume() else sendIdentify()
            }
            11 -> ackPending = false
            1 -> sendHeartbeat()
            7 -> scheduleReconnect(resume = true)
            9 -> {
                val resumable = frame.optBoolean("d", false)
                if (!resumable) { sessionId = null; lastSeq = null }
                scheduleReconnect(resume = resumable, delayMs = Random.nextLong(1000, 5000))
            }
            0 -> dispatch(frame.optString("t"), frame.optJSONObject("d"))
        }
    }

    private fun dispatch(type: String?, d: JSONObject?) {
        when (type) {
            "READY" -> {
                botId = d?.optJSONObject("user")?.optString("id").orEmpty()
                val name = d?.optJSONObject("user")?.optString("username").orEmpty()
                botName = d?.optJSONObject("user")?.optString("global_name")?.takeIf { it.isNotBlank() && it != "null" } ?: name
                sessionId = d?.optString("session_id")
                resumeUrl = d?.optString("resume_gateway_url")?.takeIf { it.isNotBlank() }
                reconnectAttempt = 0
                DiscordBotState.setBotName(name)
                DiscordBotState.setStatus(DiscordBotState.Status.CONNECTED, "Connected as $name")
                DiscordBotState.log("Ready as $name")
            }
            "RESUMED" -> {
                reconnectAttempt = 0
                DiscordBotState.setStatus(DiscordBotState.Status.CONNECTED, "Reconnected")
                DiscordBotState.log("Session resumed")
            }
            "GUILD_CREATE" -> {
                EmojiConvert.putGuildEmojis(d?.optJSONArray("emojis"))
                ChannelInfoStore.putGuildChannels(d?.optJSONArray("channels"), d?.optString("name").orEmpty().takeUnless { it == "null" }.orEmpty())
                DiscordBotState.log("Loaded ${EmojiConvert.customNames().size} emojis, ${ChannelInfoStore.size()} channels")
            }
            "CHANNEL_CREATE", "CHANNEL_UPDATE" ->
                if (d != null) ChannelInfoStore.putGuildChannels(JSONArray().put(d))
            "GUILD_EMOJIS_UPDATE" -> EmojiConvert.putGuildEmojis(d?.optJSONArray("emojis"))
            "MESSAGE_CREATE" -> if (d != null) handleMessage(d)
            "MESSAGE_REACTION_ADD" -> if (d != null) handleReactionAdd(d)
        }
    }

    private fun sendIdentify() {
        val payload = JSONObject().put("op", 2).put("d", JSONObject()
            .put("token", cfg.botToken)
            .put("intents", INTENTS)
            .put("properties", JSONObject()
                .put("os", "android").put("browser", "vrc-a").put("device", "vrc-a")))
        webSocket?.send(payload.toString())
    }

    private fun sendResume() {
        val payload = JSONObject().put("op", 6).put("d", JSONObject()
            .put("token", cfg.botToken).put("session_id", sessionId).put("seq", lastSeq ?: 0))
        webSocket?.send(payload.toString())
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            delay((heartbeatIntervalMs * Random.nextDouble(0.0, 1.0)).toLong())
            while (true) {
                if (ackPending) { DiscordBotState.log("Heartbeat not ACKed — reconnecting"); scheduleReconnect(resume = true); return@launch }
                sendHeartbeat()
                delay(heartbeatIntervalMs)
            }
        }
    }

    private fun sendHeartbeat() {
        ackPending = true
        webSocket?.send(JSONObject().put("op", 1).put("d", lastSeq ?: JSONObject.NULL).toString())
    }

    private fun scheduleReconnect(resume: Boolean, delayMs: Long = -1L) {
        if (reconnectJob?.isActive == true) return
        heartbeatJob?.cancel()
        val old = webSocket
        socketGen.incrementAndGet()   // the old socket's late callbacks are now ignored
        webSocket = null
        try { old?.close(if (resume) 4000 else 1000, "reconnect") } catch (_: Exception) {}
        DiscordBotState.setStatus(DiscordBotState.Status.RECONNECTING, "Reconnecting…")
        reconnectJob = scope.launch {
            val backoff = if (delayMs >= 0) delayMs
            else (1000L shl reconnectAttempt.coerceAtMost(5)).coerceAtMost(MAX_BACKOFF_MS) + Random.nextLong(0, 1000)
            reconnectAttempt++
            delay(backoff)
            openSocket(resume = resume && sessionId != null)
        }
    }

    // ── Budget sync (real Cloudflare usage) ───────────────────────────────

    private fun startBudgetLoop() {
        if (budgetJob?.isActive == true) return
        budgetJob = scope.launch {
            while (true) {
                delay(DiscordBotLimits.USAGE_SYNC_INTERVAL_MS)
                try {
                    val real = NeuronBudget.syncReal(this@DiscordBotService, cfg)
                    if (real != null) DiscordBotState.setNeuronsAbsolute(real)
                } catch (_: Exception) { }
            }
        }
    }

    /** Charge a model call to the persisted budget + reflect the authoritative total in the UI. */
    private fun charge(est: Long) {
        DiscordBotState.setNeuronsAbsolute(NeuronBudget.add(this, est))
    }

    // Workers AI reports the exact neurons of every call; whole neurons go to the day's budget and
    // the fraction carries over so tiny 8B calls (~0.3-2 neurons) still add up.
    private var neuronFraction = 0.0
    @Synchronized private fun chargeExact(neurons: Double) {
        if (neurons <= 0.0 || neurons.isNaN()) return
        neuronFraction += neurons
        val whole = kotlin.math.floor(neuronFraction).toLong()
        if (whole >= 1) { neuronFraction -= whole; charge(whole) }
    }

    // ── Message routing ───────────────────────────────────────────────────

    private fun handleMessage(d: JSONObject) {
        val author = d.optJSONObject("author") ?: return
        if (author.optBoolean("bot", false)) return
        val authorId = author.optString("id")
        if (authorId.isBlank() || authorId == botId) return

        val channelId = d.optString("channel_id")
        val messageId = d.optString("id")
        if (channelId.isBlank()) return
        if (channelId in cfg.mutedChannels) return
        DiscordBotState.bumpSeen()

        val now = System.currentTimeMillis()
        noteActivity(channelId, now)
        ConversationStore.touch(this, channelId, now)
        learnPending.merge(channelId, 1, Int::plus)

        val rawContent = DiscordRest.withStickers(d.optString("content"), d)
        EmojiConvert.noteUsage(rawContent)
        persistEmojiUsageIfDirty()
        // "call me X" / "update my nickname to X" lands on their card right away, not at the next
        // learn pass, so the reply that says "ash it is" and the card agree.
        if (!CALL_ME_NOT_RE.containsMatchIn(rawContent)) CALL_ME_RE.find(rawContent)?.groupValues?.get(2)?.let { nick ->
            if (nick.lowercase() !in CALL_ME_STOP) {
                UserMemoryStore.applyDelta(this, authorId, DiscordRest.displayName(author, ""), JSONObject().put("preferredName", nick))
                nickSetAt[authorId] = now to nick
            }
        }
        val mentioned = messageMentionsBot(d, rawContent)
        val ref = d.optJSONObject("referenced_message")
        // "be nicer" / "can we lighten the chat up" / "talk nicely": change how he talks here for a while.
        if (mentioned || calledByName(rawContent) || namesBot(rawContent) ||
            (botId.isNotBlank() && ref?.optJSONObject("author")?.optString("id") == botId) ||
            flow[channelId]?.let { q -> synchronized(q) { q.lastOrNull()?.isBot } } == true) {
            toneFor(rawContent)?.let { kind -> toneVotes.getOrPut(channelId) { ConcurrentHashMap() }[authorId] = now to kind }
        }
        val repliedToBot = botId.isNotBlank() && ref?.optJSONObject("author")?.optString("id") == botId
        val addressed = mentioned || repliedToBot || calledByName(rawContent)
        // "cardinal is kinda mid" isn't talking TO him, but a person would notice: it skips the random
        // ambient roll and goes straight to the director (reply / react / ignore).
        val named = !addressed && namesBot(rawContent)
        // Someone Cardinal is mid-conversation with, writing again with no @ and no reply: free rules first
        // (the chat hasn't moved on → still to him), the cheap check only when it's genuinely unclear.
        val follow = if (addressed) Follow.NONE else followUpKind(channelId, authorId, d, rawContent, now)
        val addressedEff = addressed || follow == Follow.FREE
        recordFlow(channelId, FlowMsg(messageId, authorId, false, ref?.optJSONObject("author")?.optString("id"), addressedEff,
            DiscordRest.displayName(author, ""), now, rawContent.lowercase().take(300)))

        val userMentionsResolved = DiscordRest.resolveMentions(stripBotMentions(rawContent), d.optJSONArray("mentions"))
        // Which other channels does this message point at (e.g. "did you see that in #media")?
        val refChannels = ChannelInfoStore.referencedIds(userMentionsResolved)
        // Resolve <#id> to #name for readability once the ids are captured.
        val userTextRaw = ChannelInfoStore.resolveMentions(userMentionsResolved)
        val hasImage = imageAttached(d.optJSONArray("attachments"))

        // Recognise + reinforce any shared-culture / channel-bit reference in what people say.
        ServerMemoryStore.reinforceReferenced(this, userTextRaw, now)
        ChannelMemoryStore.reinforceReferenced(this, channelId, userTextRaw, now)

        // Learn from the channel in batches (cheap, off the reply path).
        maybeLearn(channelId, now)

        // "Stop" directed at the bot → back off in this channel (don't be annoying).
        var signOff = false
        if (addressedEff && isStopRequest(userTextRaw)) {
            backoffUntil[channelId] = now + DiscordBotLimits.BACKOFF_MS
            backoffStopMsg[channelId] = messageId   // replies still being written for earlier messages get dropped
            DiscordBotState.log("backing off in $channelId")
            val who = DiscordRest.displayName(author, "someone")
            DiscordBotState.addTrace(DiscordBotState.Trace(now, channelId, who, "addressed/stop", "heuristic", "back-off",
                "quiet here for ${DiscordBotLimits.BACKOFF_MS / 60_000} min"))
            // "say goodnight to the chat and stop responding": do the first part (one short line), then go quiet.
            // He used to go silent without the goodnight.
            if (!SIGNOFF_RE.containsMatchIn(userTextRaw)) return
            signOff = true
        }

        // ── Free heuristic prefilter ──
        if (!addressedEff) {
            if (now < (backoffUntil[channelId] ?: 0L)) return
            if (follow == Follow.CHECK) Unit else {
            val eligible = rawContent.isNotBlank() && cfg.ambientPercent > 0 && (named ||
                now - (directorAt[channelId] ?: 0L) >= DiscordBotLimits.DIRECTOR_MIN_GAP_MS &&
                Random.nextInt(100) < effectiveAmbient(channelId) &&
                ambientCooldownOk(channelId, now) &&
                !postedRecently(channelId, now))
            if (!eligible) return
            }
        }

        val userTextBase = userTextRaw.ifBlank { if (hasImage) "" else PING_ONLY }
        // A situational cue so the model + channel-bit retrieval know an image was posted.
        val userText = if (hasImage) (userTextBase + " (posted an image)").trim() else userTextBase
        val authorName = DiscordRest.displayName(author, "someone")

        var refTurn: DiscordBotAi.Turn? = null
        var refId: String? = null
        if (ref != null) {
            val rAuthor = ref.optJSONObject("author")
            val rText = DiscordRest.resolveMentions(stripBotMentions(DiscordRest.withStickers(ref.optString("content"), ref)), ref.optJSONArray("mentions"))
            if (rText.isNotBlank() && rAuthor != null) {
                refId = ref.optString("id")
                refTurn = DiscordBotAi.Turn(
                    isBot = rAuthor.optString("id") == botId,
                    name = DiscordRest.displayName(rAuthor, "someone"),
                    text = tokenize(rText),
                )
            }
        }

        val ctx = MsgCtx(channelId, messageId, authorId, authorName, tokenize(userText), addressedEff, refTurn, refId, hasImage, refChannels, named,
            followUp = follow == Follow.CHECK, freeFollow = follow == Follow.FREE, signOff = signOff)

        // No upfront wait: fire immediately. Ordering is the per-channel reply mutex; a follow-up
        // that lands while a reply is generating is either folded into the fresh in-lock context or
        // caught by the free "already covered" check — so latency is never the coalescing mechanism.
        val job = scope.launch { try { route(ctx) } catch (_: Exception) { } }
        routeJobs.add(job)
        job.invokeOnCompletion { routeJobs.remove(job) }
    }

    /** True if the message carries an image attachment (the "posted an image" situational cue). */
    private fun imageAttached(attachments: JSONArray?): Boolean {
        if (attachments == null) return false
        for (i in 0 until attachments.length()) {
            val a = attachments.optJSONObject(i) ?: continue
            if (a.optString("content_type").startsWith("image")) return true
            if (Regex("(?i)\\.(png|jpe?g|gif|webp)$").containsMatchIn(a.optString("filename"))) return true
        }
        return false
    }

    private suspend fun route(ctx: MsgCtx) {
        val rung = DiscordBotState.currentRung()
        if (rung == DiscordBotState.Rung.SILENT) { trace(ctx, "silent", "heuristic", "dropped", "budget spent"); return }

        // Trivial addressed message → a human just reacts, no model call.
        if (ctx.addressed && isTrivial(ctx.userText)) { reactTo(ctx, pickEmoji(ctx.userText)); return }

        // Explicit "react to my message with X" → do exactly that (custom :name: or unicode), no
        // reply, no model call. Fixes Cardinal typing `react: [:mpreg:]` as a MESSAGE instead of reacting.
        if (ctx.addressed) {
            val rkey = "${ctx.channelId}:${ctx.authorId}"
            val asked = parseReactRequest(ctx.userText) ?: parseReactFollowUp(ctx.userText,
                System.currentTimeMillis() - (reactReqAt[rkey] ?: 0L) < 120_000L)
            if (asked != null) {
                reactReqAt[rkey] = System.currentTimeMillis()
                for (e in asked) { reactTo(ctx, e, record = false); delay(250) }
                trace(ctx, "react-req", "heuristic", "react", asked.joinToString(" "))
                return
            }
        }
        if (rung == DiscordBotState.Rung.REACT_ONLY) {
            if (ctx.addressed) reactTo(ctx, pickEmoji(ctx.userText))
            else trace(ctx, "budget", "ladder", "dropped", "react-only rung")
            return
        }

        // Everything real happens under the per-channel lock (finish-the-thought), and the context
        // is fetched INSIDE it — so a message that queued while a reply was generating sees the
        // freshest state, and the ambient/covered decision is made against reality, not a stale snapshot.
        val mutex = channelMutex.getOrPut(ctx.channelId) { Mutex() }
        mutex.withLock { generateLocked(ctx, rung) }
    }

    private class Built(
        val turns: List<DiscordBotAi.Turn>,
        val ids: Set<String>,
        val nameToId: Map<String, String>,
        val keywords: Set<String>,     // words of the recent conversation → what's "relevant now"
        val targetIsLatest: Boolean,   // nothing newer than the message we're answering
        val maxSeenId: Long,           // highest message id folded in (for the "already covered" check)
        val emphasizeIds: Set<String>, // people the message ASKED ABOUT → inject their FULL card
        val namedIds: List<String>,    // people the message names (not the author), in order
        val otherSpeakers: List<String>, // other humans talking in the transcript, most recent first
        val recall: Boolean,           // a memory question ("what do you know about…", "who runs…?")
        val asking: Boolean,           // any question (or a memory question)
        val windowFull: Boolean,       // the conversation goes back further than the transcript
        val refOutOfWindow: Boolean,   // they replied to a message older than the transcript
        val dayAsk: DayQuestion.Ask? = null, // "what happened yesterday?" → that day's log
    )

    private suspend fun generateLocked(ctx: MsgCtx, rung: DiscordBotState.Rung) {
        var short = rung == DiscordBotState.Rung.TRIM || rung == DiscordBotState.Rung.CHEAP

        if (ctx.addressed) {
            // Addressed dequeue re-check (zero cost, no upfront wait): if we JUST replied to this
            // person, only continue when THIS message is genuinely new — i.e. it arrived AFTER the
            // last reply's context. A message the last reply already saw is a fragment we've covered.
            val ukey = "${ctx.channelId}:${ctx.authorId}"
            if (System.currentTimeMillis() - (perUserReplyAt[ukey] ?: 0L) < DiscordBotLimits.PER_USER_REPLY_COOLDOWN_MS) {
                val thisId = ctx.messageId.toLongOrNull() ?: 0L
                if (thisId <= (lastCoveredId[ctx.channelId] ?: 0L)) {
                    trace(ctx, "covered", "heuristic", "dropped", "already answered in last reply"); return
                }
                short = true   // a real follow-up since the last reply → keep it tight, like a person
            }
            // Typing dots the moment we know we'll answer; the history fetch + model run behind them.
            showTyping(ctx.channelId)
        }

        // A channel the message points at is fetched alongside the history, not after it.
        val crossRefJob = if (ctx.refChannels.any { it != ctx.channelId }) scope.async { buildCrossRef(ctx) } else null
        val built = buildContext(ctx)   // fresh: folds in anything sent while this was queued

        if (!ctx.addressed) {
            val now = System.currentTimeMillis()
            directorAt[ctx.channelId] = now
            val exchange = if (ctx.followUp) lastExchangeWith(ctx.channelId, ctx.authorId) else null
            val plan = DiscordBotAi.director(cfg, built.turns, ChannelInfoStore.describe(ctx.channelId), ctx.named,
                followWith = if (ctx.followUp) ctx.authorName else "", exchange = exchange)
            val ckey = "${ctx.channelId}:${ctx.authorId}"
            if (ctx.followUp) {
                if (plan == null || plan.action == DiscordBotAi.Act.IGNORE) {
                    // Twice in a row "not to him" → they've moved on; stop checking their messages.
                    if (convoMisses.merge(ckey, 1, Int::plus)!! >= DiscordBotLimits.FOLLOW_MISSES_TO_END) {
                        convoWith.remove(ckey); convoMisses.remove(ckey)
                    }
                    crossRefJob?.cancel()
                    trace(ctx, "follow-up", "check", "ignore", "not to Cardinal"); return
                }
                convoMisses.remove(ckey)
                markToBot(ctx.channelId, ctx.messageId)
                // A question to him gets an answer, not an emoji ("lol ok but why cats").
                if (plan.action == DiscordBotAi.Act.REACT && !QUESTION_RE.containsMatchIn(ctx.userText)) {
                    crossRefJob?.cancel()
                    reactTo(ctx, plan.emoji.ifBlank { pickEmoji(ctx.userText) }); return
                }
                showTyping(ctx.channelId)
            } else
            when (plan?.action) {
                null, DiscordBotAi.Act.IGNORE -> {
                    crossRefJob?.cancel()
                    trace(ctx, "ambient", "director", "ignore", plan?.let { "no add" } ?: "no plan"); return
                }
                DiscordBotAi.Act.REACT -> {
                    crossRefJob?.cancel()
                    if (!ctx.named && now - (ambientReactAt[ctx.channelId] ?: 0L) < DiscordBotLimits.AMBIENT_REACT_COOLDOWN_MS) {
                        trace(ctx, "ambient", "director", "ignore", "reacted recently"); return
                    }
                    ambientReactAt[ctx.channelId] = now
                    reactTo(ctx, plan.emoji.ifBlank { pickEmoji(ctx.userText) }); return
                }
                DiscordBotAi.Act.REPLY -> {
                    ambientCooldown[ctx.channelId] = now
                    if (plan.short) short = true
                    showTyping(ctx.channelId)
                }
            }
        }

        // Length follows the need: banter, reactions and statements get one short line; only a real question
        // or an ask for detail ("explain…", "tell me about…") gets room for more. Replies to "your memory is a
        // mess" used to run three sentences because every @ / reply got the full budget.
        if (!QUESTION_RE.containsMatchIn(ctx.userText) && !DETAIL_ASK_RE.containsMatchIn(ctx.userText) &&
            built.dayAsk == null && !built.recall) short = true
        val model = if (rung == DiscordBotState.Rung.CHEAP) DiscordBotLimits.CHEAP_MODEL else cfg.model
        val turns = if (rung == DiscordBotState.Rung.TRIM || rung == DiscordBotState.Rung.CHEAP)
            built.turns.takeLast(DiscordBotLimits.CONTEXT_RAW_TURNS / 2) else built.turns

        val rc = buildReplyCtx(ctx, built, turns, short, crossRefJob?.await().orEmpty())

        when (val res = DiscordBotAi.reply(cfg, model, turns, rc)) {
            is DiscordBotAi.ReplyResult.Ok -> {
                // Someone told him to stop while this reply was being written: drop it rather than talk over them.
                val stopId = backoffStopMsg[ctx.channelId]?.toLongOrNull()
                val msgId = ctx.messageId.toLongOrNull()
                if (System.currentTimeMillis() < (backoffUntil[ctx.channelId] ?: 0L) &&
                    stopId != null && msgId != null && msgId < stopId) {
                    trace(ctx, "reply", "backed off", "dropped", "told to stop while writing")
                    return
                }
                val replyText = tameEmoji(ctx.channelId, oneMessage(res.text))
                val outText = EmojiConvert.convert(replyText)
                val now = System.currentTimeMillis()
                if (cfg.shadowMode) {
                    DiscordBotState.log("shadow ↩ ${ctx.authorName}: ${outText.take(60)}")
                    trace(ctx, "reply", "shadow", "shadow", outText.take(90))
                } else {
                    // Only @-reply-thread when the convo moved past their message (out-of-order);
                    // if we're answering the latest message, just talk plainly like a person.
                    val out = DiscordRest.send(cfg.botToken, ctx.channelId, outText,
                        replyToMessageId = if ((ctx.addressed || ctx.followUp) && !built.targetIsLatest) ctx.messageId else null)
                    if (out.error == null) {
                        out.messageId?.let { synchronized(recentBotMsgIds) { recentBotMsgIds[it] = ctx.authorId } }
                        recordFlow(ctx.channelId, FlowMsg(out.messageId.orEmpty(), botId, true, ctx.authorId, false,
                            text = replyText.take(200), theirText = ctx.userText.take(200)))
                        // Only a real exchange opens (or keeps open) a conversation: they @'d / replied to / named
                        // him, or carried on one he was already in. His own jump-ins don't — in a busy room that
                        // snowballed into him answering half the chat.
                        if ((ctx.addressed && !ctx.freeFollow) || ctx.freeFollow || ctx.followUp)
                            convoWith["${ctx.channelId}:${ctx.authorId}"] = now
                        lastBotPostMs[ctx.channelId] = now
                        rememberBotReply(ctx.channelId, replyText)
                        trace(ctx, "reply", model.substringAfterLast('/').substringBefore("-instruct").take(24), "reply", outText.take(90))
                        learnPending.merge(ctx.channelId, 1, Int::plus)   // its own line is part of what gets learned
                    } else {
                        DiscordBotState.log("Send failed: ${out.error}")
                        trace(ctx, "reply", model.substringAfterLast('/').substringBefore("-instruct").take(24), "send-fail", out.error)
                    }
                }
                UserMemoryStore.touch(this, ctx.authorId, ctx.authorName)
                maybeLearn(ctx.channelId, now)
                DiscordBotState.bumpReplied()
                perUserReplyAt["${ctx.channelId}:${ctx.authorId}"] = now
                // Everything up to here is answered — so an earlier same-person fragment won't
                // re-fire, while a genuinely newer message still will (the "already covered" check).
                lastCoveredId[ctx.channelId] = maxOf(lastCoveredId[ctx.channelId] ?: 0L, built.maxSeenId)
            }
            is DiscordBotAi.ReplyResult.Error -> {
                DiscordBotState.log("AI error: ${res.message}")
                trace(ctx, "reply", model.substringAfterLast('/').substringBefore("-instruct").take(24), "error", res.message)
            }
        }
    }

    /** Fire the typing indicator without waiting on it. */
    private fun showTyping(channelId: String) {
        if (cfg.shadowMode) return
        scope.launch { DiscordRest.triggerTyping(cfg.botToken, channelId) }
    }

    // ── Learning (cheap 8B pass: memory / summary / culture / self) ────────

    /**
     * Decide whether a channel is due a learn pass. Every message counts (replied or not, Cardinal's
     * own too): a pass runs after [DiscordBotLimits.LEARN_TRIGGER_MSGS] new messages, sooner in a very
     * busy channel, and when the channel goes quiet with a few unlearned messages left. Only on the
     * FULL/TRIM budget rungs — the cheaper rungs exist to save neurons.
     */
    private fun maybeLearn(channelId: String, now: Long) {
        val pending = learnPending[channelId] ?: 0
        if (pending <= 0 || channelId in learnInFlight) return
        val rung = DiscordBotState.currentRung()
        if (rung != DiscordBotState.Rung.FULL && rung != DiscordBotState.Rung.TRIM) return
        val since = now - (lastLearnAt[channelId] ?: 0L)
        val due = (pending >= DiscordBotLimits.LEARN_TRIGGER_MSGS && since >= DiscordBotLimits.LEARN_MIN_INTERVAL_MS) ||
            (pending >= DiscordBotLimits.LEARN_FORCE_MSGS && since >= DiscordBotLimits.LEARN_FORCE_MIN_GAP_MS)
        if (due) { startLearn(channelId, now); return }
        // Not due yet: learn whatever is left once the channel goes quiet.
        learnLullJobs.remove(channelId)?.cancel()
        learnLullJobs[channelId] = scope.launch {
            delay(DiscordBotLimits.LEARN_LULL_MS)
            // Right after a pass, wait out the minimum gap instead of skipping (else the tail is lost).
            val wait = (lastLearnAt[channelId] ?: 0L) + DiscordBotLimits.LEARN_LULL_MIN_GAP_MS - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            if ((learnPending[channelId] ?: 0) >= DiscordBotLimits.LEARN_LULL_MIN_MSGS) startLearn(channelId, System.currentTimeMillis())
        }
    }

    private fun startLearn(channelId: String, now: Long) {
        if (!learnInFlight.add(channelId)) return
        lastLearnAt[channelId] = now
        learnLullJobs.remove(channelId)?.cancel()
        scope.launch {
            try { runLearn(channelId) } catch (_: Exception) { } finally {
                learnInFlight.remove(channelId)
                // Messages that arrived during the pass still need one (or a quiet-room pass).
                if ((learnPending[channelId] ?: 0) > 0) maybeLearn(channelId, System.currentTimeMillis())
            }
        }
    }

    /** One learn pass over every message since the last pass (up to [DiscordBotLimits.LEARN_FETCH]). */
    private suspend fun runLearn(channelId: String) {
        if (!::cfg.isInitialized || !cfg.isComplete) return
        val taken = learnPending.put(channelId, 0) ?: 0
        val recent = DiscordRest.fetchRecentMessages(cfg.botToken, channelId, DiscordBotLimits.LEARN_FETCH)
        val after = lastLearnedId[channelId] ?: 0L
        val fresh = recent.filter { (it.id.toLongOrNull() ?: 0L) > after }
        val turns = fresh.mapNotNull { m ->
            val text = tokenize(ChannelInfoStore.resolveMentions(stripBotMentions(m.content)))
            if (text.isBlank()) null else DiscordBotAi.Turn(m.authorId == botId, m.authorName, text)
        }
        if (turns.size < 3) { learnPending.merge(channelId, taken, Int::plus); return }
        val nameToId = HashMap<String, String>()
        recent.forEach { if (it.authorId != botId) nameToId[it.authorName.lowercase().trim()] = it.authorId }
        // Someone corrected a fact, disputed one of Cardinal's traits, or complained → show the learner
        // what's stored about the people involved so it can take the wrong bits back out.
        val humanText = turns.filter { !it.isBot }.joinToString("\n") { it.text }
        val items = if (CORRECTION_RE.containsMatchIn(humanText)) correctionItems(humanText, fresh) else emptyList()
        val stored = items.mapIndexed { i, it -> "[${i + 1}] ${it.second}: ${it.third}" }.joinToString("\n")
        val known = PersonalityStore.traitTexts(this, DiscordBotLimits.MAX_TRAITS)
        val speakers = fresh.filter { it.authorId != botId }.map { it.authorId }.distinct()
        val knownPeople = if (items.isEmpty()) UserMemoryStore.knownFactsLine(this, speakers) else ""
        val bitFocus = bitInPlay(turns, known)
        val obs = DiscordBotAi.observe(cfg, turns, ConversationStore.summary(channelId), stored, known, knownPeople, bitFocus)
        if (obs == null) { learnPending.merge(channelId, taken, Int::plus); return }   // retried with the next batch
        lastLearnedId[channelId] = maxOf(after, fresh.maxOf { it.id.toLongOrNull() ?: 0L })
        applyObservation(channelId, obs, nameToId, System.currentTimeMillis(), turns, correcting = items.isNotEmpty(), items = items, known = known, bitFocus = bitFocus)
        DiscordBotState.log("learned $channelId (${turns.size} msgs, ${obs.memDeltas.size} people" +
            (if (stored.isNotBlank()) ", checked corrections" else "") + ")")
        maybeDigestDay()
    }

    /**
     * Numbered stored items the learner may mark wrong: the facts/nicknames of the batch's speakers and
     * anyone it names (id, name, item), then Cardinal's own traits (id = "").
     */
    private fun correctionItems(humanText: String, fresh: List<DiscordRest.HistMsg>): List<Triple<String, String, String>> {
        val ids = LinkedHashSet<String>()
        fresh.asReversed().forEach { if (it.authorId != botId) ids.add(it.authorId) }
        val low = " " + humanText.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ") + " "
        UserMemoryStore.nameEntries(this).forEach { nk ->
            if (nk.key.length >= 3 && nk.id != botId && low.contains(" ${nk.key} ")) ids.add(nk.id)
        }
        return UserMemoryStore.correctionItems(this, ids) +
            PersonalityStore.traitTexts(this, 12).map { Triple("", "Cardinal", it) }
    }

    private val mergeAt = ConcurrentHashMap<String, Long>()
    private fun maybeMergeFacts(id: String) {
        val now = System.currentTimeMillis()
        if (now - (mergeAt[id] ?: 0L) < DiscordBotLimits.FACT_MERGE_COOLDOWN_MS) return
        mergeAt[id] = now
        scope.launch {
            try {
                val card = UserMemoryStore.load(this@DiscordBotService, id) ?: return@launch
                val (topic, pile) = UserMemoryStore.crowdedTopic(card) ?: return@launch
                val merged = DiscordBotAi.mergeFacts(cfg, card.name, topic, pile) ?: return@launch
                if (UserMemoryStore.replaceFacts(this@DiscordBotService, id, pile, merged))
                    DiscordBotState.log("merged ${card.name}'s \"$topic\" facts: ${pile.size} → ${merged.size}")
            } catch (_: Exception) { }
        }
    }

    /**
     * Once a busy day is over, condense its log into a short recap (one cheap call per day). Runs after a
     * learn pass, so it only ever happens while the bot is active and within budget.
     */
    private fun maybeDigestDay() {
        if (digestInFlight || !::cfg.isInitialized || !cfg.isComplete) return
        val rung = DiscordBotState.currentRung()
        if (rung != DiscordBotState.Rung.FULL && rung != DiscordBotState.Rung.TRIM) return
        val now = System.currentTimeMillis()
        val day = DayLogStore.needsDigest(this, now) ?: return
        val key = day.date.toString()
        if (now - (digestTriedAt[key] ?: 0L) < 30 * 60_000L) return
        digestTriedAt[key] = now
        digestInFlight = true
        scope.launch {
            try {
                val label = DayLogStore.label(day.date, DayLogStore.dateOf(now))
                val recap = DiscordBotAi.digestDay(cfg, label, DayLogStore.digestInput(day))
                if (recap != null) {
                    DayLogStore.setDigest(this@DiscordBotService, day.date, recap)
                    DiscordBotState.log("recapped ${day.date} (${day.entries.size} notes)")
                }
            } catch (_: Exception) { } finally { digestInFlight = false }
        }
    }

    /** Persist one learn pass: summary, day log, people, server culture, channel bit, self. */
    private fun applyObservation(
        channelId: String, obs: DiscordBotAi.Observation, nameToId: Map<String, String>, now: Long,
        turns: List<DiscordBotAi.Turn>, correcting: Boolean = false,
        items: List<Triple<String, String, String>> = emptyList(),
        known: List<String> = emptyList(),
        bitFocus: String = "",
    ) {
        val globalIndex = UserMemoryStore.nameIndex(this)
        if (obs.summary.isNotBlank()) ConversationStore.updateSummary(this, channelId, obs.summary, now, turns.map { it.name })
        // The day log: what's going on + funny/notable moments, only when the chat backs them up.
        val chatWords = groundWords(turns.joinToString(" ") { it.name + " " + it.text })
        val moments = (obs.moments + listOf(obs.serverEvent)).map { it.trim() }.filter { m ->
            val w = groundWords(m); m.isNotBlank() && (w.isEmpty() || w.count { it in chatWords } * 2 >= w.size)
        }.distinct()
        DayLogStore.record(this, ChannelInfoStore.name(channelId) ?: channelId, now, obs.summary, moments)
        val dropped = ArrayList<String>()
        val misfiled = ArrayList<Pair<String, String>>()   // (who it was filed under, fact) the chat didn't back up for them
        for (md in obs.memDeltas) {
            val id = resolveObserveAbout(md.about, nameToId, globalIndex)
            if (id != null && id != botId)
                UserMemoryStore.applyDelta(this, id, md.about,
                    groundDelta(md, id, turns, nameToId, { dropped.add(it) }, { misfiled.add(id to it) }), correcting)
        }
        // The 8B sometimes files a fact under the wrong person (RTL's "48 crate of doctor pepper" landed on the
        // person who asked "mexican dr pepper?"). A dropped fact that exactly one OTHER person's own lines back
        // up is theirs — it still goes through the same grounding for them.
        for ((fromId, fact) in misfiled) {
            val fw = factWords(unhedge(fact)); if (fw.size < 2) continue
            val owners = turns.filter { !it.isBot }.groupBy { nameToId[it.name.lowercase().trim()] }
                .filterKeys { it != null && it != fromId && it != botId }
                .filter { (_, lines) -> val c = factWords(lines.joinToString(" ") { it.text }); fw.count { it in c } * 2 >= fw.size }
            val (ownerId, lines) = owners.entries.singleOrNull() ?: continue
            val name = lines.first().name
            val md = DiscordBotAi.MemDelta(name, JSONObject().put("facts", JSONArray().put(fact)))
            UserMemoryStore.applyDelta(this, ownerId!!, name, groundDelta(md, ownerId, turns, nameToId, { dropped.add(it) }), correcting)
        }
        // A card whose one topic piled up paraphrases gets a cheap merge pass (rare, per-card cooldown).
        for (md in obs.memDeltas) {
            val id = resolveObserveAbout(md.about, nameToId, globalIndex) ?: continue
            if (id == botId) continue
            val card = UserMemoryStore.load(this, id) ?: continue
            if (UserMemoryStore.crowdedTopic(card) != null) maybeMergeFacts(id)
        }
        if (dropped.isNotEmpty())
            DiscordBotState.log("learn: skipped ${dropped.size} unsupported fact(s), e.g. \"${dropped.first().take(60)}\"")
        // Inside jokes: the model's event, or (free) a phrase three or more different people repeated.
        // A memory has to say what happened and who was in it — a bare title ("Crazy Opus 5.5") tells
        // Cardinal nothing later, so short ones are dropped (the catchphrase fallback names the people).
        val event = obs.serverEvent.takeIf { describes(it) }.orEmpty().ifBlank { catchphraseEvent(turns, obs) }
        if (event.isNotBlank()) ServerMemoryStore.remember(this, event, now)
        if (describes(obs.channelBit)) ChannelMemoryStore.remember(this, channelId, obs.channelBit, now)
        // Stored items the chat said are wrong / unwanted: a person's fact or nickname is dropped; one of
        // Cardinal's traits is toned down (gone if it was new). Never re-added in the same pass.
        val disputed = ArrayList<String>()
        // Only honoured when the humans in this batch actually talked about it (its words were said) —
        // the small model sometimes marks every stored item "wrong" at once.
        val humanLow = turns.filter { !it.isBot }.joinToString(" ") { it.text }.lowercase()
        val humanWords = groundWords(humanLow)
        val seriousWords = groundWords(turns.filter { !it.isBot && !isJoking(it.text) }.joinToString(" ") { it.text })
        for (n in obs.wrong.distinct()) {
            val it = items.getOrNull(n - 1) ?: continue
            // One of Cardinal's traits needs someone meaning it (teasing / laughing along doesn't count).
            val mentioned = when {
                it.third.startsWith("goes by ") -> humanLow.contains(it.third.removePrefix("goes by ").lowercase())
                it.first.isEmpty() -> groundWords(it.third).any { w -> w in seriousWords }
                else -> groundWords(it.third).any { w -> w in humanWords }
            }
            if (!mentioned) continue
            // The person themselves just backed it up ("no it's miso lol, bob is trolling") → keep it.
            if (it.first.isNotEmpty() && affirmedBySubject(it.first, it.third, turns, nameToId)) continue
            if (it.first.isEmpty()) PersonalityStore.disputeTrait(this, it.third)?.let { t -> disputed.add(t) }
            else UserMemoryStore.forgetItem(this, it.first, it.third)
        }
        // Free backstop for people's facts: a stored fact whose key word is negated right before it ("left
        // toronto", "you're not a chef", "never been a chef") is dropped when the person themselves says so,
        // or two different people do. Negation must be within 3 words, so "not a big fan of toronto" doesn't count.
        if (correcting) {
            val handled = obs.wrong.mapNotNull { items.getOrNull(it - 1) }.toSet()
            for (item in items) {
                if (item.first.isEmpty() || item in handled || item.third.startsWith("goes by ")) continue
                val keys = Regex("[\\p{L}\\p{N}]+").findAll(item.third.lowercase()).map { it.value }
                    .filter { it.length >= 4 && it !in GROUND_STOP && it !in NEGATION_NOISE }.toList()
                if (keys.isEmpty()) continue
                val sayers = turns.filter { t -> !t.isBot && keys.any { k ->
                    Regex("\\b(not|never|no longer|isn'?t|aren'?t|wasn'?t|left|quit|stopped|ex|former|moved (out of|from|away from))\\b(\\W+[\\p{L}']+){0,3}?\\W+" +
                        Regex.escape(k)).containsMatchIn(t.text.lowercase()) } }.map { it.name.lowercase().trim() }.toSet()
                val bySubject = sayers.any { nameToId[it] == item.first }
                if (bySubject || sayers.size >= 2) UserMemoryStore.forgetItem(this, item.first, item.third)
            }
        }
        // "call me X" / "i go by X" from the person themselves → their preferred name (free, no model slot).
        for (t in turns) {
            if (t.isBot || CALL_ME_NOT_RE.containsMatchIn(t.text)) continue
            val id = nameToId[t.name.lowercase().trim()] ?: continue
            val m = CALL_ME_RE.find(t.text) ?: continue
            val nick = m.groupValues[2]
            if (nick.lowercase() !in CALL_ME_STOP) UserMemoryStore.applyDelta(this, id, t.name, JSONObject().put("preferredName", nick))
        }
        // "i'm a firefighter" → "you're not a firefighter lol" → "yes i am, station 12": the learner goes into
        // correction mode on the dispute and sometimes never writes the fact down at all. Someone who says what
        // they are and stands by it when challenged has told us who they are (free backstop).
        for ((i, t) in turns.withIndex()) {
            if (t.isBot || isJoking(t.text)) continue
            val id = nameToId[t.name.lowercase().trim()] ?: continue
            val what = SELF_STATED_RE.find(t.text.lowercase())?.groupValues?.get(3)?.trim()
                ?.split(' ')?.let { w -> if (w.size > 1 && w[1] in SELF_STATED_TAIL) w.take(1) else w }?.joinToString(" ") ?: continue
            if (what.split(' ').first() in SELF_STATED_TAIL) continue
            val key = what.split(' ').last()
            val later = turns.drop(i + 1)
            val disputed = later.any { o -> !o.isBot && nameToId[o.name.lowercase().trim()] != id &&
                Regex("\\b(not|no|isn'?t|aint|ain'?t)\\b.{0,20}\\b" + Regex.escape(key)).containsMatchIn(o.text.lowercase()) }
            val stands = later.any { o -> !o.isBot && nameToId[o.name.lowercase().trim()] == id && STANDS_BY_RE.containsMatchIn(o.text.lowercase()) }
            if (disputed && stands) UserMemoryStore.applyDelta(this, id, t.name, JSONObject().put("facts", JSONArray().put("is a $what")))
        }
        // "do you like dr pepper?" → "obviously. it's basically my only personality trait": his own taste, said
        // out loud, is who he is — the learner often files it as a one-off. Yes → "loves X", no → "can't stand X".
        for ((i, t) in turns.withIndex()) {
            if (t.isBot) continue
            val m = LIKE_Q_RE.find(t.text) ?: continue
            val thing = m.groupValues[2].trim().trimEnd('.', '!', ',')
            if (thing.split(' ').any { it.lowercase() in setOf("it", "that", "this", "me", "us", "them", "him", "her") }) continue
            val answer = turns.drop(i + 1).take(3).firstOrNull { it.isBot }?.text?.lowercase()?.trim() ?: continue
            val trait = when {
                TASTE_YES.containsMatchIn(answer) -> "loves $thing"
                TASTE_NO.containsMatchIn(answer) -> "can't stand $thing"
                else -> null
            } ?: continue
            if (!PersonalityStore.tooVague(trait)) PersonalityStore.noteSelf(this, trait, null, null)
        }
        // "stop calling me X" / "don't call me X" from the person themselves → retire that name (free).
        if (correcting) for (t in turns) {
            if (t.isBot) continue
            val id = nameToId[t.name.lowercase().trim()] ?: continue
            val m = CALL_ME_NOT_RE.find(t.text) ?: continue
            UserMemoryStore.applyDelta(this, id, t.name, JSONObject().put("notNickname", m.groupValues[2]), correcting = true)
        }
        // Free backstop: two or more different people complaining about something one of Cardinal's traits
        // is about ("enough birds", "the bird thing got old") tones it down even if the model missed it.
        if (correcting) {
            val complaints = turns.filter { !it.isBot && COMPLAINT_RE.containsMatchIn(it.text) && !isJoking(it.text) }
            val fans = turns.filter { !it.isBot && ENCOURAGE_RE.containsMatchIn(it.text) }
            for (t in PersonalityStore.traitTexts(this, DiscordBotLimits.MAX_TRAITS)) {
                if (disputed.any { PersonalityStore.isSameTrait(it, t) }) continue
                val tw = groundWords(t)
                val who = complaints.filter { c -> groundWords(c.text).any { it in tw } }.map { it.name.lowercase() }.toSet()
                val cheering = fans.filter { c -> groundWords(c.text).any { it in tw } || c.text.length < 40 }.map { it.name.lowercase() }.toSet() - who
                if (who.size >= 2 && who.size > cheering.size) PersonalityStore.disputeTrait(this, t)?.let { disputed.add(it) }
            }
        }
        if (obs.wrong.isNotEmpty() || disputed.isNotEmpty())
            DiscordBotState.log("corrected ${obs.wrong.size} stored item(s)" + (if (disputed.isNotEmpty()) ", toned down \"${disputed.first().take(50)}\"" else ""))
        // A trait needs Cardinal in the batch (he spoke, or people talked to/about him) — otherwise the
        // small model is describing someone else's joke as his.
        val cardinalInBatch = turns.any { it.isBot || Regex("(?i)\\bcardinal\\b").containsMatchIn(it.text) }
        val emojiNames = EmojiConvert.customNames().map { it.lowercase() }.toSet()
        val batchWords = groundWords(turns.joinToString(" ") { it.text })
        // While the room riffs on one of his bits, a "new" trait about the same subject is that bit's update.
        val bitVariant = bitFocus.isNotBlank() && obs.selfTrait.isNotBlank() && groundWords(obs.selfTrait).any { it in groundWords(bitFocus) }
        val roomGiven = if (!bitVariant && cardinalInBatch) roomTitle(turns) else null
        val learnedTrait = obs.selfTrait.takeIf { !bitVariant }?.takeIf { t ->
            // Named in the chat's words: "discerning gourmet" for "official pizza critic" is the small model's gloss.
            val tw = groundWords(t)
            cardinalInBatch && t.isNotBlank() && disputed.none { PersonalityStore.isSameTrait(t, it) } &&
                // (A trait is now a descriptive phrase, so a third of its words from the chat is enough.)
                (tw.isEmpty() || tw.count { it in batchWords } * 3 >= tw.size) && !PersonalityStore.tooVague(t) &&
                // The 8B copies the example from its own instructions ("thinks every new movie is overrated").
                LEARNER_EXAMPLES.none { PersonalityStore.isSameTrait(t, it) } &&
                // A lasting quirk shows up more than once: one throwaway line of his ("stay out of the kitchen")
                // isn't a trait ("Kitchen Elite").
                (tw.isEmpty() || turns.count { m -> groundWords(m.text).any { it in tw } } >= 2) &&
                t.trim(':', ' ').lowercase() !in emojiNames   // "clueless" from :clueless: isn't a personality
        }
        // The room handed him a title ("you're the server's official pizza critic now") and others picked it up:
        // that's the trait, in their words — the 8B often renames it ("pizza connoisseur") or glosses it past
        // the check above. Free.
        val titled = roomGiven?.takeIf { t -> known.none { PersonalityStore.isSameTrait(it, t) } && disputed.none { PersonalityStore.isSameTrait(t, it) } }
        val newTrait = when {
            titled == null -> learnedTrait
            learnedTrait == null || groundWords(learnedTrait).any { it in groundWords(titled) } -> titled
            else -> { PersonalityStore.noteSelf(this, titled, null, null); learnedTrait }   // two different things
        }
        // A trait that evolved replaces the one it came from (the learner names the old one in self.replaces).
        val replaced = newTrait?.let { nt ->
            val r = obs.selfReplaces.trim().trimEnd('.')
            if (r.length < 3) return@let null
            // The learner copies the old trait's text; match it to a known trait (exact or same trait reworded).
            val old = (known.firstOrNull { it.equals(r, true) } ?: known.firstOrNull { PersonalityStore.isSameTrait(it, r) })
                // The bit the room is riffing on only changes through the bit slot (checked against the chat).
                ?.takeUnless { bitFocus.isNotBlank() && (it.equals(bitFocus, true) || PersonalityStore.isSameTrait(it, bitFocus)) }
            // A "new" trait that's just another known trait repeated isn't a change.
            val repeat = known.any { k -> !k.equals(old, true) && PersonalityStore.isSameTrait(k, nt) }
            old?.takeIf { !repeat && !PersonalityStore.isSameTrait(it, nt) && PersonalityStore.replaceTrait(this, it, nt) }
        }
        if (replaced != null) DiscordBotState.log("self: \"${replaced.take(40)}\" → \"${newTrait.take(40)}\"")
        // The bit the room was riffing on, as it stands now (asked in the same learn pass — no extra call).
        // A bit people are complaining about is being toned down, not evolved.
        val bitDisputed = bitFocus.isNotBlank() && (disputed.any { PersonalityStore.isSameTrait(it, bitFocus) || it.equals(bitFocus, true) } ||
            turns.any { !it.isBot && COMPLAINT_RE.containsMatchIn(it.text) && !isJoking(it.text) && groundWords(it.text).any { w -> w in groundWords(bitFocus) } })
        if (replaced == null && bitFocus.isNotBlank() && !bitDisputed) {
            applyBitNow(bitFocus, obs.bitNow, if (bitVariant) obs.selfTrait else "", turns)
        }
        // Mood too: only when Cardinal was part of it (someone else's bad day isn't his mood).
        val mood = obs.selfMood.takeIf { cardinalInBatch && it.isNotBlank() }
        if ((newTrait != null && replaced == null) || mood != null) {
            // "a film enthusiast with a somewhat sarcastic tone" is two traits: store them separately.
            val parts = if (replaced == null && newTrait != null) splitTrait(newTrait) else listOf(null)
            parts.drop(1).forEach { PersonalityStore.noteSelf(this, it, null, null) }
            PersonalityStore.noteSelf(this, parts.first(), null, mood)
            DiscordBotState.setMood(PersonalityStore.mood(this))
        }
    }

    /**
     * A title the room gave Cardinal ("you're the server's official pizza critic now", "cardinal is our
     * resident bird expert") whose key word at least two different people used in this batch.
     */
    private fun roomTitle(turns: List<DiscordBotAi.Turn>): String? {
        val humans = turns.filter { !it.isBot }
        for (t in humans) {
            val m = TITLE_RE.find(t.text) ?: continue
            val title = m.groupValues[1].trim().lowercase()
            val words = title.split(Regex("\\s+"))
            if (words.first() in TITLE_NOT || words.size > 3 || title.length < 4) continue
            val head = discordStem(words.last())
            if (head.length < 3) continue
            val users = humans.filter { h -> Regex("[\\p{L}]+").findAll(h.text.lowercase()).any { discordStem(it.value) == head } }
                .map { it.name.lowercase() }.toSet()
            if (users.size >= 2) return title
        }
        return null
    }

    /**
     * A running joke the room made while nobody tagged it: a two-word phrase ("fork soup") said by three or
     * more different people in this batch. Described with the moment/summary that mentions it. Free.
     */
    private fun catchphraseEvent(turns: List<DiscordBotAi.Turn>, obs: DiscordBotAi.Observation): String {
        val speakers = HashMap<String, MutableSet<String>>()
        for (t in turns) {
            if (t.isBot) continue
            val w = Regex("[\\p{L}\\p{N}']+").findAll(t.text.lowercase()).map { it.value }.toList()
            for (i in 0 until w.size - 1) {
                val a = w[i]; val b = w[i + 1]
                if (a.length < 3 || b.length < 3 || a in PHRASE_STOP || b in PHRASE_STOP) continue
                speakers.getOrPut("$a $b") { HashSet() }.add(t.name.lowercase())
            }
        }
        val (phrase, who) = speakers.entries.filter { it.value.size >= 3 }.maxByOrNull { it.value.size }?.toPair() ?: return ""
        val words = phrase.split(' ')
        val names = turns.filter { !it.isBot && it.name.lowercase() in who }.map { it.name }.distinct().take(4).joinToString(", ")
        // Say what it's about: the first line that had the joke's words in it (in any order — "soup with a
        // fork" started "fork soup"), else a moment that mentions it.
        val origin = turns.firstOrNull { t -> !t.isBot && words.all { w -> Regex("\\b" + Regex.escape(w)).containsMatchIn(t.text.lowercase()) } }
        val base = when {
            origin != null -> "\"$phrase\" — started when ${origin.name} said \"${origin.text.take(110)}\""
            else -> (obs.moments + listOf(obs.summary)).firstOrNull { d -> words.all { d.lowercase().contains(it) } }
                ?.let { "\"$phrase\" — $it" } ?: "\"$phrase\" became a running joke"
        }
        return "$base; $names kept bringing it up".trim().take(240).also {
            DiscordBotState.log("inside joke: \"$phrase\" (${who.size} people)")
        }
    }
    /** One of Cardinal's bits the room is riffing on right now: 2+ people using its words, him taking part. Free. */
    private fun bitInPlay(turns: List<DiscordBotAi.Turn>, known: List<String>): String {
        if (turns.none { it.isBot || Regex("(?i)\\bcardinal\\b").containsMatchIn(it.text) }) return ""
        val humans = turns.filter { !it.isBot }
        return known.firstOrNull { t ->
            val keys = groundWords(t + " " + PersonalityStore.wasOf(this, t))   // its old subject counts too
            keys.isNotEmpty() && humans.filter { h -> groundWords(h.text).any { it in keys } }.map { it.name.lowercase() }.toSet().size >= 2
        }.orEmpty()
    }

    /** Take the learner's "how the bit stands now" when it's the same bit, changed, in the chat's words. */
    private fun applyBitNow(old: String, bitAnswer: String, variant: String, turns: List<DiscordBotAi.Turn>) {
        fun toks(x: String) = Regex("[\\p{L}\\p{N}]+").findAll(x.lowercase()).map { it.value }
            .filter { it.length >= 3 && it !in BIT_FILLER }.map { discordStem(it) }.toSet()
        val oldW = toks(old + " " + PersonalityStore.wasOf(this, old))
        val chat = toks(turns.joinToString(" ") { it.text })
        // The twist has to come from the room: words only Cardinal used ("center of this swamp drama") are his
        // own riff, and a bit named that way stops saying what it is.
        val room = toks(turns.filter { !it.isBot }.joinToString(" ") { it.text })
        // The bit slot answered about THIS bit ("single era" after a breakup needn't repeat "Shrek"); a general
        // trait only counts as this bit's update when it keeps the bit's subject.
        fun ok(next: String, needSubject: Boolean): Boolean {
            val w = toks(next); val fresh = w - oldW
            return next.split(Regex("\\s+")).size <= 10 && w.isNotEmpty() &&
                !next.equals(old, true) && fresh.isNotEmpty() &&            // a change…
                (!needSubject || w.any { it in oldW }) &&                    // …of this bit…
                fresh.any { it in room } && w.count { it in chat || it in oldW } * 2 >= w.size &&   // …in the chat's words
                !BIT_COMMENTARY.containsMatchIn(next)                         // not "the group loves the idea"
        }
        // The shortest valid version reads best as a trait ("divorced from Shrek").
        fun clean(c: String) = c.substringAfterLast(" is now ", c).replace(Regex("\\s*\\([^)]*\\)"), "")   // "(no change, …)"
            .trim().trim('\'', '"', '.', ' ').replace(Regex("(?i)^cardinal('s|’s| is| has)?\\s+"), "").trim()
        val next = listOf(clean(bitAnswer) to false, clean(variant) to true)
            .filter { (c, needSubject) -> c.isNotBlank() && ok(c, needSubject) }.map { it.first }.minByOrNull { it.length } ?: return
        if (PersonalityStore.replaceTrait(this, old, next))
            DiscordBotState.log("self: bit evolved \"${old.take(40)}\" → \"${next.take(40)}\"")
    }
    private val BIT_FILLER = setOf("the", "and", "with", "now", "his", "him", "is", "are", "was", "has", "one", "who", "for", "but", "still")
    private val BIT_COMMENTARY = Regex("(?i)\\b(the group|the chat|the room|everyone|people|the idea|loves the|likes the|is joking|jokes about|annoying|cringe|got old|enough|stop|drop it)\\b")

    /** The card's owner said one of the item's key words in this batch without negating it. */
    private fun affirmedBySubject(id: String, item: String, turns: List<DiscordBotAi.Turn>, nameToId: Map<String, String>): Boolean {
        val keys = Regex("[\\p{L}\\p{N}]+").findAll(item.lowercase()).map { it.value }
            .filter { it.length >= 4 && it !in GROUND_STOP && it !in NEGATION_NOISE }.toList()
        if (keys.isEmpty()) return false
        val neg = Regex("\\b(not|never|no longer|isn'?t|aren'?t|wasn'?t|don'?t|doesn'?t|left|quit|stopped|ex|former|moved (out of|from|away from))\\b(\\W+[\\p{L}']+){0,3}?\\W+$")
        return turns.any { t ->
            !t.isBot && nameToId[t.name.lowercase().trim()] == id && keys.any { k ->
                val low = t.text.lowercase()
                val at = Regex("\\b" + Regex.escape(k)).find(low)?.range?.first ?: return@any false
                !neg.containsMatchIn(low.substring(0, at))
            }
        }
    }

    /** A full-ish sentence (5+ words), not a bare title. */
    private fun describes(s: String): Boolean = s.isNotBlank() && s.trim().split(Regex("\\s+")).size >= 5

    private fun splitTrait(t: String): List<String?> {
        val parts = t.trim().trimEnd('.').split(Regex("(?i)\\s*(?:;|,\\s+and\\s+|,|\\s+with\\s+(?:a|an)\\s+|\\s+and\\s+(?:a|an|is)\\s+)\\s*"))
            .map { it.trim().replace(Regex("(?i)^(a|an|the|is|he'?s|he is)\\s+"), "") }
        // Only when every piece stands on its own ("film enthusiast" + "sarcastic tone"); "dating Shrek, and Bob"
        // stays whole (a lone name isn't a trait).
        val ok = parts.all { p -> p.split(' ').size >= 2 || (p.length >= 6 && p.first().isLowerCase()) }
        return if (ok && parts.size in 2..3) parts else listOf(t)
    }

    private val PHRASE_STOP = setOf("the", "and", "you", "your", "that", "this", "what", "just", "like", "lol", "lmao",
        "was", "are", "for", "with", "have", "not", "but", "its", "it's", "i'm", "can", "all", "get", "got", "yeah",
        "who", "how", "why", "now", "his", "her", "him", "she", "they", "them", "one", "out", "too", "any", "cardinal")

    /**
     * Keep only what the chat actually supports. A learned fact must mostly use words that were said
     * by that person or by people talking about them in this batch; a nickname / preferred name must
     * appear in the chat. Catches the cheap model inventing specifics (a hometown or job nobody
     * mentioned) without any extra model call. Facts with no checkable words pass.
     */
    private fun groundDelta(
        md: DiscordBotAi.MemDelta, id: String, turns: List<DiscordBotAi.Turn>, nameToId: Map<String, String>,
        onDrop: (String) -> Unit,
        onUnsupported: (String) -> Unit = {},
    ): JSONObject {
        val out = JSONObject(md.json.toString())
        val card = UserMemoryStore.load(this, id)
        val names = (listOf(md.about) + nameCandidates(md.about) + listOfNotNull(card?.name, card?.preferredNick) +
            card?.nicknames.orEmpty()).map { it.lowercase().trim() }.filter { it.length >= 2 }.toSet()
        val humans = turns.filter { !it.isBot }
        val about = humans.filter { t ->
            nameToId[t.name.lowercase().trim()] == id ||
                names.any { n -> Regex("(^|[^\\p{L}\\p{N}])" + Regex.escape(n) + "([^\\p{L}\\p{N}]|$)").containsMatchIn(t.text.lowercase()) }
        }
        // Someone else's line that names them counts, but not the parts about the speaker ("bye ali, gotta walk
        // my dog" doesn't give ali a dog).
        val aboutText = about.joinToString(" ") { t -> if (nameToId[t.name.lowercase().trim()] == id) t.text else withoutFirstPerson(t.text) }
        val corpus = factWords(aboutText)
        val rawCorpus = Regex("[\\p{L}\\p{N}]+").findAll(aboutText.lowercase()).map { it.value }.toSet()
        val nameWords = names.flatMap { factWords(it) }.toSet()
        md.json.optJSONArray("facts")?.let { arr ->
            val kept = JSONArray()
            // The learner sometimes packs several facts into one ("plays JJ's on pc, has a phone that can't
            // run it, has a dog"): the real parts then ground the invented one. Check each part on its own.
            val parts = (0 until arr.length()).flatMap { splitFusedFact(arr.optString(it)) }
            for (raw in parts) {
                val f = unhedge(raw.trim()); if (f.isBlank()) continue
                // Short words count too: "has a dog" used to pass with no support at all (only 4+ letter words were checked).
                val words = factWords(f) - nameWords
                val need = (words.size + 1) / 2
                val grounded = words.isEmpty() || words.count { it in corpus } >= need
                // The lines that back this fact up. If every one is a joke, something they're doing right
                // now, a what-if, or about someone else ("my brother lives in tokyo"), it isn't a fact about them.
                val support = about.filter { t -> factWords(t.text).any { it in words } }
                val why = when {
                    !grounded -> "unsupported"
                    support.isNotEmpty() && support.all { isJoking(it.text) } -> "joke"
                    // "mexican dr pepper?" / "do you like dr pepper?" ask about something; they don't say it's theirs.
                    support.isNotEmpty() && support.all { ASKING_RE.containsMatchIn(it.text.trim()) } -> "unsupported"
                    support.isNotEmpty() && support.all { isRightNow(it, turns) } -> "right now"
                    support.isNotEmpty() && support.all { HYPOTHETICAL_RE.containsMatchIn(it.text) } -> "hypothetical"
                    support.isNotEmpty() && (support.all { t -> aboutSomeoneElse(t.text, f) } ||
                        // judged by the line that matches the fact best ("my creator isnt a 24/7 vrchat player"), not
                        // every line that happens to share a word like "play"
                        aboutSomeoneElse(support.maxBy { t -> factWords(t.text).count { it in words } }.text, f)) -> "someone else"
                    // "atleast my creator isnt a 24/7 vrchat player" came back as "has a creator who is a VRChat player":
                    // the learner dropped the "not". A positive fact whose every backing line negates it is flipped.
                    support.isNotEmpty() && !FACT_NEGATION.containsMatchIn(f) && support.all { t -> negates(t.text, words) } -> "flipped"
                    implausibleAge(f) -> "joke age"
                    !verbSaid(f, names, rawCorpus) -> "reworded"
                    else -> null
                }
                if (why == "unsupported") onUnsupported(f)
                if (why == null) kept.put(f) else onDrop("${md.about}: $f ($why)")
            }
            out.put("facts", kept)
        }
        val chat = " " + humans.joinToString(" ") { it.text.lowercase() } + " "
        fun said(v: String) = v.isNotBlank() && chat.contains(v.lowercase().trim())
        // "forget" only for things the chat actually talked about (the model sometimes "forgets" a fact
        // just to reword it).
        md.json.optJSONArray("forget")?.let { arr ->
            val chatWords = groundWords(chat)
            val kept = JSONArray()
            for (i in 0 until arr.length()) {
                val f = arr.optString(i).trim()
                if (f.isNotBlank() && groundWords(f).any { it in chatWords }) kept.put(f)
            }
            out.put("forget", kept)
        }
        // A nickname is what OTHER people call them (said by someone else, not everyday slang like
        // "bruv" / "bro" — the speaker saying "peak bruv" to a friend isn't a name for the speaker).
        val selfLines = humans.filter { nameToId[it.name.lowercase().trim()] == id }
        fun calledByOthers(v: String): Boolean {
            val k = v.lowercase().trim()
            if (k.isBlank() || k in NICK_SLANG) return false
            val re = Regex("(^|[^\\p{L}\\p{N}])" + Regex.escape(k) + "([^\\p{L}\\p{N}]|$)")
            return humans.any { it !in selfLines && re.containsMatchIn(it.text.lowercase()) } ||
                selfLines.any { CALL_ME_RE.find(it.text)?.groupValues?.get(2)?.lowercase() == k }
        }
        if (!said(out.optString("nickname")) || !calledByOthers(out.optString("nickname"))) out.remove("nickname")
        val pref = out.optString("preferredName")
        if (!said(pref) || pref.lowercase().trim() in NICK_SLANG) out.remove("preferredName")
        return out
    }

    /** Something they're doing right now: "rn"/"atm", "listening to music" as a status, "floors being
     *  fixed", or an "-ing" answer to "wbu / wyd / what are you up to". */
    private fun isRightNow(t: DiscordBotAi.Turn, turns: List<DiscordBotAi.Turn>): Boolean {
        val low = t.text.lowercase()
        if (NOW_RE.containsMatchIn(low) || BEING_DONE_RE.containsMatchIn(low)) return true
        val lead = Regex("^\\W*(?:(?:just|still|also|currently|rn|and|oh|um|uh|mostly|mainly)\\s+)*([\\p{L}']+)").find(low)?.groupValues?.get(1).orEmpty()
        val i = turns.indexOf(t)
        val askedWhatDoing = i > 0 && turns.subList(maxOf(0, i - 3), i).any { it.name != t.name && WHAT_DOING_RE.containsMatchIn(it.text.lowercase()) }
        if (!askedWhatDoing) return false
        return (lead.endsWith("ing") && lead.length >= 5 && lead !in NOT_VERB_ING) ||
            Regex("\\b(i'?m|im|i am|am|just|been)\\s+(just\\s+|still\\s+)?[\\p{L}]+ing\\b").containsMatchIn(low)
    }

    /** "my brother lives in tokyo" backs up a fact about the brother, not the speaker. */
    private fun aboutSomeoneElse(line: String, fact: String): Boolean {
        val m = RELATIVE_RE.find(line.lowercase()) ?: return false
        return !fact.lowercase().contains(m.groupValues[2])
    }

    /** "is 82 years old" / "is 9" — ages outside 13..70 in a Discord chat are jokes. */
    private fun implausibleAge(fact: String): Boolean {
        val m = Regex("(?i)\\b(?:is|aged?|turn(?:ed|ing)?)\\s+(\\d{1,3})(\\s*(years?|yrs?)(\\s*old)?|\\s*yo)?\\b").find(fact)
            ?: Regex("(?i)\\b(\\d{1,3})\\s*(years?|yrs?)\\s*old\\b").find(fact) ?: return false
        val n = m.groupValues[1].toIntOrNull() ?: return false
        // "is 3" alone could be anything; only a phrase that reads as an age counts.
        val ageish = m.groupValues.getOrNull(2)?.isNotBlank() == true || Regex("(?i)\\b(years?|yrs?|yo|age|aged|turn)").containsMatchIn(m.value)
        return ageish && (n < 13 || n > 70)
    }

    /** The fact's action verb ("makes mix tapes") has to be something they actually said ("printing stuff
     *  for my mix tapes" isn't making them). Common state verbs (is/has/lives/likes…) pass. */
    // The learner hedges a disputed fact ("claims to be a firefighter"): the person said it about themselves,
    // so keep the plain claim ("is a firefighter") and let the usual checks decide.
    private val HEDGE_RE = Regex("(?i)^(?:(?:they|he|she)\\s+)?(?:claims|claimed|says|said|states|stated|mentions|mentioned)\\s+(?:to\\s+be|(?:that\\s+)?(?:they|he|she)(?:'s|\\s+is|\\s+are|\\s+was|\\s+were))\\s+")
    private val HEDGE_WORD_RE = Regex("(?i)\\b(apparently|reportedly|supposedly|allegedly)\\s+")
    private fun unhedge(f: String): String = HEDGE_WORD_RE.replace(HEDGE_RE.replace(f, "is "), "").trim()

    private fun verbSaid(fact: String, names: Set<String>, raw: Set<String>): Boolean {
        val toks = Regex("[\\p{L}\\p{N}'-]+").findAll(fact.lowercase()).map { it.value }.toMutableList()
        val nameToks = names.flatMap { it.split(Regex("\\s+")) }.toSet()
        while (toks.isNotEmpty() && (toks[0] in nameToks || toks[0] in setOf("they", "he", "she", "their", "often", "usually", "sometimes", "also", "always", "still"))) toks.removeAt(0)
        val v = toks.firstOrNull() ?: return true
        if (!v.endsWith("s") || v.length < 4 || v.endsWith("ss") || v in STATE_VERBS) return true
        val stem = discordStem(v)
        return raw.any { w -> discordStem(w) == stem || (w.startsWith(stem) && w.length <= stem.length + 3) }
    }

    // Words too general to anchor a "that's not true" match on.
    private val NEGATION_NOISE = setOf("lives", "live", "works", "work", "plays", "play", "owns", "named", "professional", "their", "this")
    private val GROUND_STOP = setOf(
        "likes", "like", "loves", "love", "really", "always", "usually", "often", "they", "their", "them", "have",
        "been", "being", "with", "from", "into", "about", "some", "very", "much", "lots", "also", "still", "just",
        "that", "this", "what", "when", "where", "which", "while", "would", "could", "should", "does", "doing",
        "make", "made", "getting", "gets", "goes", "going", "enjoy", "enjoys", "person", "someone", "thing", "things",
    )
    private val FACT_STOP = GROUND_STOP + setOf("has", "had", "the", "and", "for", "can", "are", "was", "not", "but", "you",
        "her", "his", "him", "she", "too", "own", "its", "got", "get", "try", "off", "out", "one", "all", "any", "who", "how",
        "why", "now", "yet", "per", "via", "use", "big", "lot", "way", "day", "new", "old", "bit")
    /** Like [groundWords] but keeps 3-letter words (dog, cat, gym, pc game names) — for checking learned facts. */
    private fun factWords(s: String): Set<String> =
        Regex("[\\p{L}\\p{N}]+").findAll(s.lowercase().replace(Regex("['’]s\\b"), "")).map { it.value }
            .filter { it.length >= 3 && it !in FACT_STOP }.map { discordStem(it) }.toSet()
    /** A line with the speaker's own clauses ("my dog", "i'm moving") taken out. */
    private val FACT_VERB = "(?:has|have|had|is|are|was|plays|play|likes|like|loves|love|hates|hate|works|work|lives|live|" +
        "owns|own|goes|go|can|can'?t|cannot|does|doesn'?t|uses|use|watches|streams|makes|wants|studies|speaks|drives|runs)"
    private val FUSED_SPLIT = Regex("(?i)\\s*;\\s*|,\\s*(?:and\\s+)?(?=$FACT_VERB\\b)|\\s+and\\s+(?=$FACT_VERB\\b)")
    internal fun splitFusedFact(f: String): List<String> =
        f.split(FUSED_SPLIT).map { it.trim() }.filter { it.isNotBlank() }

    private val FACT_NEGATION = Regex("(?i)\\b(not|never|no|isn'?t|doesn'?t|don'?t|can'?t|won'?t|ain'?t|hasn'?t|without)\\b")
    /** The line says "not" (isn't/never/…) within three words before one of the fact's key words. */
    private fun negates(line: String, keys: Collection<String>): Boolean {
        val toks = Regex("[\\p{L}\\p{N}']+").findAll(line.lowercase()).map { it.value }.toList()
        return toks.indices.any { i ->
            discordStem(toks[i]) in keys.map { discordStem(it) } &&
                (maxOf(0, i - 4) until i).any { j -> Regex("^(not|never|no|isnt|isn't|aint|ain't|doesnt|doesn't|dont|don't|cant|can't|wont|won't|hasnt|hasn't|aren't|arent|wasnt|wasn't)$").matches(toks[j]) }
        }
    }

    private fun withoutFirstPerson(text: String): String =
        // Any clause with a first-person word is about the speaker, not whoever they named
        // ("bye ashoska, gotta walk my dog" says nothing about ashoska's dog).
        text.split(Regex("[.!?,;]|\\s+(?:and|but|so|then)\\s+")).filterNot {
            Regex("(?i)\\b(i|i'?m|im|i'?ve|ive|i'?ll|i'?d|me|my|mine|we|we'?re|our|us)\\b").containsMatchIn(it)
        }.joinToString(" ")
    private fun groundWords(s: String): Set<String> =
        Regex("[\\p{L}\\p{N}]+").findAll(s.lowercase()).map { it.value }
            .filter { it.length >= 4 && it !in GROUND_STOP }.map { discordStem(it) }.toSet()

    private suspend fun reactTo(ctx: MsgCtx, emoji: String, record: Boolean = true) {
        if (cfg.shadowMode) { if (record) trace(ctx, "react", "heuristic", "shadow", emoji); return }
        DiscordRest.addReaction(cfg.botToken, ctx.channelId, ctx.messageId, EmojiConvert.reactionToken(emoji))
        DiscordBotState.bumpReacted()
        if (record) trace(ctx, "react", "heuristic", "react", emoji)
    }

    // ── Context assembly (ordered, retrieval-limited) ─────────────────────

    private suspend fun buildContext(ctx: MsgCtx): Built {
        val turns = ArrayList<DiscordBotAi.Turn>()
        val ids = HashSet<String>().apply { add(ctx.authorId) }
        val nameToId = HashMap<String, String>()
        nameToId[ctx.authorName.lowercase().trim()] = ctx.authorId
        val seen = HashSet<String>()
        var newerExists = false
        val ctxIdL = ctx.messageId.toLongOrNull() ?: Long.MAX_VALUE
        var maxSeenId = ctx.messageId.toLongOrNull() ?: 0L
        var fetchedCount = 0
        var transcriptCut = false
        val speakerOrder = ArrayList<String>()
        if (cfg.contextTurns > 0) {
            val recent = DiscordRest.fetchRecentMessages(cfg.botToken, ctx.channelId, cfg.contextTurns)
            fetchedCount = recent.size
            // Only the conversation that's happening now: walking back from the newest message, stop at the
            // first quiet gap longer than CONVO_GAP_MS. Lines from hours ago read as live context otherwise
            // (a dog walk at 01:54 turned a 05:53 question into "since when are we a scheduling committee").
            val staleCutId = staleCutoff(recent.map { it.id } + ctx.messageId)
            if (staleCutId > 0L) transcriptCut = true
            for (m in recent) {
                val mid = m.id.toLongOrNull() ?: 0L
                if (mid <= staleCutId) continue
                if (mid > maxSeenId) maxSeenId = mid
                if (m.id == ctx.messageId) continue
                // A higher snowflake id = a message that arrived AFTER the one we're answering.
                if (mid > ctxIdL) newerExists = true
                seen.add(m.id)
                val text = tokenize(ChannelInfoStore.resolveMentions(stripBotMentions(m.content)))
                if (text.isBlank()) continue
                ids.add(m.authorId)
                if (m.authorId != botId) { nameToId[m.authorName.lowercase().trim()] = m.authorId; speakerOrder.add(m.authorId) }
                turns.add(DiscordBotAi.Turn(m.authorId == botId, m.authorName, text))
            }
        }
        val windowFull = cfg.contextTurns > 0 && fetchedCount >= cfg.contextTurns && !transcriptCut
        val refOutOfWindow = ctx.refTurn != null && (ctx.refId == null || ctx.refId !in seen)
        // A Discord reply says which message it answers — inline, so the transcript stays in order and
        // an old quoted message is clearly a quote, not a new turn.
        // A bare ping ("@Cardinal" and nothing else) points him at something: the message it replies to (anyone's,
        // their own included — a question to answer, or just something to look at), else their own last question.
        val barePing = ctx.userText == PING_ONLY
        val ownLast = if (barePing && ctx.refTurn == null)
            turns.lastOrNull { !it.isBot && it.name == ctx.authorName && it.text.contains('?') }?.text else null
        val target = if (barePing && ctx.refTurn != null) {
            val r = ctx.refTurn
            val who = if (r.isBot) "your" else if (r.name == ctx.authorName) "their own" else "${r.name}'s"
            "(pinged you with no text on $who message: \"${r.text.take(200)}\" — they're pointing you at it: answer it if it asks you something, otherwise give your take on it)"
        } else if (ownLast != null) {
            "(pinged you with no text right after saying: \"${ownLast.take(200)}\" — they probably want you in on that)"
        } else ctx.refTurn?.let { r ->
            // When his message was aimed at someone else, say who: "damn michael got egoed" under his put-down of
            // RTL is about RTL, not him.
            val aimedAt = if (r.isBot) ctx.refId?.let { rid -> flow[ctx.channelId]?.let { q -> synchronized(q) { q.firstOrNull { it.id == rid } } } }
                ?.replyTo?.takeIf { it != ctx.authorId && it != botId }
                ?.let { id -> UserMemoryStore.load(this, id)?.name?.takeIf { it.isNotBlank() } ?: nameToId.entries.firstOrNull { it.value == id }?.key }
                else null
            val who = if (r.isBot) (if (aimedAt != null) "what you said to $aimedAt" else "you") else r.name
            // Their reply to one of Cardinal's messages always shows which one — "ohh shit" under his
            // answer is a reaction to that answer, not a new greeting.
            if (refOutOfWindow || r.isBot) "(replying to $who: \"${r.text.take(160)}\") ${ctx.userText}"
            else "(replying to $who) ${ctx.userText}"
        } ?: ctx.userText
        turns.add(DiscordBotAi.Turn(false, ctx.authorName, target))
        // Newest message first, so the words of what's being answered can never be cut off by older chat.
        val keywords = LinkedHashSet<String>().apply {
            for (t in turns.takeLast(DiscordBotLimits.RELEVANCE_WINDOW_TURNS).asReversed()) addAll(keywordList(t.text))
        }.take(DiscordBotLimits.RELEVANCE_KEYWORDS_MAX).toHashSet()
        val otherSpeakers = LinkedHashSet<String>().apply {
            for (i in speakerOrder.indices.reversed()) {
                val uid = speakerOrder[i]
                if (uid != ctx.authorId && uid != botId) add(uid)
            }
        }.toList()

        // Absent-person / recall injection: if the message NAMES someone Cardinal has a card for
        // (even if they aren't speaking), pull their card into context so it can actually answer
        // instead of claiming it doesn't know them. On a recall query ("who is X", "info about X",
        // "from your profile") the named card renders FULL (all facts).
        val emphasize = HashSet<String>()
        val named = ArrayList<String>()
        val selfRecall = SELF_RECALL_RE.containsMatchIn(ctx.userText)
        val isRecall = selfRecall || RECALL_RE.containsMatchIn(ctx.userText) || WHO_Q_RE.containsMatchIn(ctx.userText)
        val asking = isRecall || ctx.userText.contains('?')
        val nameKeys = UserMemoryStore.nameEntries(this)
        if (nameKeys.isNotEmpty()) {
            val lower = ctx.userText.lowercase()
            val normMsg = " " + lower
                .replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ").trim() + " "
            var injected = 0
            for (nk in nameKeys) {
                val uid = nk.id
                if (uid == botId || uid == ctx.authorId || nk.key.length < 3 || uid in named) continue
                if (!normMsg.contains(" ${nk.key} ")) continue
                // A short nickname or an everyday word only counts when it clearly points at a person.
                val weak = nk.key in COMMON_NICK_WORDS || (!nk.isRealName && nk.key.length < 4)
                if (weak && !asking && !lower.contains("@${nk.key}") &&
                    !Regex("(^|[^\\p{L}])" + Regex.escape(nk.key) + "['’]s\\b").containsMatchIn(lower)) continue
                ids.add(uid); if (isRecall) emphasize.add(uid)
                named.add(uid)
                if (++injected >= 3) break   // bound the prompt
            }
        }
        if (selfRecall) emphasize.add(ctx.authorId)

        return Built(turns, ids, nameToId, keywords, targetIsLatest = !newerExists, maxSeenId = maxSeenId,
            emphasizeIds = emphasize, namedIds = named, otherSpeakers = otherSpeakers, recall = isRecall,
            asking = asking, windowFull = windowFull, refOutOfWindow = refOutOfWindow,
            dayAsk = DayQuestion.parse(ctx.userText, DayLogStore.dateOf(System.currentTimeMillis())))
    }

    /**
     * Decide what this reply's prompt carries. Always: personality, channel, who you're answering,
     * the server's most-used emojis. Only when relevant now: server memories, other people, the
     * earlier-conversation summary, the names / memory-question rules. All local and free.
     */
    private fun buildReplyCtx(
        ctx: MsgCtx, built: Built, turns: List<DiscordBotAi.Turn>, short: Boolean, crossRef: String,
    ): DiscordBotAi.ReplyCtx {
        val now = System.currentTimeMillis()
        val selfRecall = built.recall && SELF_RECALL_RE.containsMatchIn(ctx.userText)
        // What's relevant about THEM is decided by what they just said (plus what they replied to), not the last six
        // lines: "i dont play fortnite" from someone else pulled "plays JJ's on PC" into "what should i eat tonight?".
        val answerKeys = (keywordList(ctx.userText) + (ctx.refTurn?.text?.let { keywordList(it) } ?: emptyList())).toHashSet()
        val answering = UserMemoryStore.answeringLine(this, ctx.authorId, ctx.authorName, answerKeys, selfRecall)

        // Other people: the ones the message names first (whole card when asked about), then — for a
        // "who …?" question with nobody named — the cards that best match it, then other speakers
        // but only when there's something worth knowing about them right now.
        val others = ArrayList<UserMemoryStore.PromptLine>()
        val used = HashSet<String>().apply { add(ctx.authorId); add(botId) }
        fun addOther(uid: String, full: Boolean, namedLimit: Int = 0) {
            if (others.size >= DiscordBotLimits.OTHER_PEOPLE_MAX || !used.add(uid)) return
            val name = built.nameToId.entries.firstOrNull { it.value == uid }?.key.orEmpty()
            UserMemoryStore.otherLine(this, uid, name, built.keywords, full, namedLimit)?.let { others.add(it) }
        }
        // Named people are the topic: always what Cardinal knows about them (more for a question).
        built.namedIds.forEach { addOther(it, full = it in built.emphasizeIds, namedLimit = if (built.asking) 4 else 2) }
        if (built.recall && built.namedIds.isEmpty() && !selfRecall)
            UserMemoryStore.searchCards(this, keywordsOf(ctx.userText), used, DiscordBotLimits.RECALL_SEARCH_MAX)
                .forEach { addOther(it, full = false) }
        built.otherSpeakers.forEach { addOther(it, full = false) }

        // The model already sees its own lines that are in the transcript; only list older ones.
        val visible = turns.filter { it.isBot }.map { normLine(it.text) }.toSet()
        val older = (recentBotReplies[ctx.channelId]?.toList() ?: emptyList()).filter { normLine(it) !in visible }

        return DiscordBotAi.ReplyCtx(
            selfDigest = PersonalityStore.snapshot(this),
            // The date always rides along (a few tokens): without it he guessed his training year ("a robot joke
            // in 2024?") and argued when told it's 2026.
            channelInfo = ChannelInfoStore.describe(ctx.channelId).let { c -> listOf(c, "today is ${todayLine(now)}").filter { it.isNotBlank() }.joinToString("; ") },
            serverCulture = buildServerCulture(ctx.channelId, built.keywords, built.recall),
            channelBits = ChannelMemoryStore.pickDeployable(this, ctx.channelId, ctx.userText, situationCues(ctx), now).orEmpty(),
            crossRef = crossRef,
            answering = answering.text + (nickSetAt[ctx.authorId]?.takeIf { System.currentTimeMillis() - it.first < NICK_NOTE_MS }
                ?.let { " You now call them \"${it.second}\" (they asked; it's saved and done)." } ?: ""),
            othersPresent = built.otherSpeakers.isNotEmpty(),
            othersBlock = others.joinToString("\n") { it.text },
            summary = if (built.windowFull || built.refOutOfWindow)
                ConversationStore.freshSummary(ctx.channelId, now, DiscordBotLimits.CONVO_GAP_MS) else "",
            olderBotLines = older,
            ownLinesVisible = visible.isNotEmpty(),
            emojiHint = emojiHint(built.keywords),
            // An English line right after other languages drifts into them ("que sera sera, what are you up to?"
            // got Spanish): say English explicitly then.
            langHint = askedLang(ctx.userText).ifBlank { detectLang(ctx.userText) }.ifBlank {
                val low = Regex("[\\p{L}']+").findAll(ctx.userText.lowercase()).map { it.value }.toList()
                if (low.count { it in EN_WORDS } >= 2 && turns.takeLast(8).any { detectLang(it.text).isNotBlank() }) "English" else ""
            },
            namesRule = answering.hasNick || others.any { it.hasNick },
            recall = built.recall || built.dayAsk != null,
            dayLog = built.dayAsk?.let {
                DayLogStore.render(this, it.days, now, it.funny, DiscordBotLimits.DAY_BLOCK_MAX_CHARS)
            }.orEmpty(),
            shortHint = short,
            aboutSelf = SELF_ASK_RE.containsMatchIn(ctx.userText),
            verdictAsk = VERDICT_ASK_RE.containsMatchIn(ctx.userText),
            clock = if (TIME_ASK_RE.containsMatchIn(ctx.userText)) clockLine(now) else "",
            bitCue = PersonalityStore.traitTexts(this, DiscordBotLimits.MAX_TRAITS).firstOrNull { t ->
                val k = groundWords(t); k.isNotEmpty() && groundWords(ctx.userText).any { it in k }
            }.orEmpty(),
            nameHint = listOf(if (ctx.signOff) "They asked you to sign off: do what they asked (e.g. say goodnight to the chat) in one short line; you'll go quiet after." else "",
                effectiveTone(ctx.channelId, now).orEmpty(), politicsHint(ctx, turns), slangHint(ctx), nickDoneHint(ctx), serverHint(ctx), unknownNameHint(ctx, built).ifBlank { selfRefHint(built) })
                .filter { it.isNotBlank() }.joinToString(" "),
            reactingToYou = (ctx.refTurn?.isBot == true || ctx.followUp || ctx.freeFollow) &&
                ctx.userText.trim().split(Regex("\\s+")).size <= 4 && '?' !in ctx.userText,
        )
    }

    /** A reply is one chat message: a blank-line second paragraph reads like a speech, so it's joined up. */
    private fun oneMessage(text: String): String = text.trim().replace(Regex("\\s*\\n\\s*\\n\\s*"), " ")

    // Capitalised words that aren't people.
    private val NOT_NAMES = setOf("i", "im", "ive", "ill", "id", "ok", "okay", "lol", "lmao", "lmfao", "omg", "god", "bro", "bruh",
        "discord", "vrchat", "english", "cardinal", "yes", "yeah", "nah", "no", "the", "and", "but", "why", "what", "how", "who",
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday", "january", "february", "march", "april",
        "may", "june", "july", "august", "september", "october", "november", "december", "christmas", "halloween", "easter",
        "quest", "steam", "youtube", "spotify", "twitch", "google", "tiktok", "instagram", "twitter", "reddit", "minecraft",
        "fortnite", "roblox", "valorant", "america", "american", "europe", "japan", "japanese", "canada", "australia", "uk", "usa")

    /**
     * A capitalised name in their message that isn't anyone Cardinal knows ("damn Michael got egoed", where Michael is
     * RTL's real name). It isn't him: when they're replying to what he said to someone, it most likely means that person.
     */
    /** People talk ABOUT him in the third person ("btw he's constantly on now", "cardinal is mid") right
     *  next to talking TO him; without a nudge the model picked that up and said "don't encourage him"
     *  about itself. Only when a recent human line uses he/him/his or his name. Free. */
    private fun selfRefHint(built: Built): String {
        val recent = built.turns.filter { !it.isBot }.takeLast(4)
        val re = Regex("(?i)\\b(he|him|his|he'?s|hes)\\b")
        val line = recent.lastOrNull { re.containsMatchIn(it.text) } ?: return ""
        val quote = line.text.replace(Regex("^\\(replying to [^)]*\\)\\s*"), "").take(80)
        return "${line.name} said \"$quote\": that \"he\" is probably you. Refer to yourself only as I/me (\"don't encourage me\", never \"him\"), and don't bring this up."
    }

    /** "you didnt do it" right after they asked for a new name: it IS saved (he used to sass back "fix it
     *  yourself, i'm not a sysadmin"). */
    private fun nickDoneHint(ctx: MsgCtx): String {
        val set = nickSetAt[ctx.authorId]?.takeIf { System.currentTimeMillis() - it.first < NICK_NOTE_MS } ?: return ""
        if (!Regex("(?i)\\b(didn'?t|did not|didnt|never|not|haven'?t|hasn'?t)\\b.{0,20}\\b(do|did|done|change|changed|save|saved|update|updated|work|worked|it)\\b|\\bdidn'?t work\\b")
                .containsMatchIn(ctx.userText)) return ""
        return "They think you didn't change their name, but you did: you call them \"${set.second}\" now and it's saved in your memory of them (not their Discord profile). Tell them it's done."
    }

    /** Short slang reactions ("peakkk" under his answer = that's great) got misread ("don't act like you aren't
     *  impressed"). Stretched letters are squeezed ("peakkk" → "peak"), then a one-line meaning. Free. */
    private fun slangHint(ctx: MsgCtx): String {
        val words = Regex("[\\p{L}]+").findAll(ctx.userText.lowercase()).map { it.value }.toList()
        if (words.isEmpty() || words.size > 8) return ""
        val hits = words.mapNotNull { w ->
            val sq = w.replace(Regex("(\\p{L})\\1{2,}"), "$1$1")
            (SLANG[w] ?: SLANG[sq] ?: SLANG[w.replace(Regex("(\\p{L})\\1+"), "$1")])?.let { w to it }
        }.distinctBy { it.second }.take(2)
        if (hits.isEmpty()) return ""
        return "Slang: " + hits.joinToString("; ") { (w, m) -> "\"$w\" = $m" } + "."
    }

    /** "i bet you love israel" / "do you?": a bait. He stonewalled ("we aren't doing this", "seek help"). The owner's
     *  call: political jokes about any side are fine here, so he plays along. Free, only when the topic is on the table. */
    private fun politicsHint(ctx: MsgCtx, turns: List<DiscordBotAi.Turn>): String {
        val recent = (turns.takeLast(4).map { it.text } + ctx.userText).joinToString(" ")
        if (!POLITICS_RE.containsMatchIn(recent)) return ""
        return "Politics as a bit: political jokes are welcome here, about any side (politicians, parties, scandals — equal-opportunity roasting). Answer with a joke that actually plays along; no dodging, no 'we aren't doing this', no lecture."
    }

    /** Asked about the server ("can you even see this server's name?"): he knew it but just said "of course". */
    private fun serverHint(ctx: MsgCtx): String {
        if (!Regex("(?i)\\b(server|guild|discord)\\b").containsMatchIn(ctx.userText)) return ""
        val n = ChannelInfoStore.serverName(ctx.channelId) ?: return ""
        return "They're asking about this server: it's called \"$n\". Say the name in your answer."
    }

    private fun unknownNameHint(ctx: MsgCtx, built: Built): String {
        val words = Regex("[\\p{L}']+").findAll(ctx.userText).toList()
        val known = (built.nameToId.keys + UserMemoryStore.nameEntries(this).map { it.key } + listOf(botName))
            .map { it.lowercase().trim() }.filter { it.length >= 2 }
        val candidates = words.filterIndexed { i, m ->
            val w = m.value
            if (i == 0 || w.length < 3 || !w[0].isUpperCase() || w.drop(1).any { it.isUpperCase() }) return@filterIndexed false
            // Not the start of a sentence.
            if (Regex("[.!?]\\s*$").containsMatchIn(ctx.userText.substring(0, m.range.first))) return@filterIndexed false
            // "update my Nickname", "the Server": a noun after a determiner/possessive, not a name.
            if (Regex("(?i)\\b(my|your|his|her|their|our|the|a|an|this|that|these|those|some|any)\\s*$")
                    .containsMatchIn(ctx.userText.substring(0, m.range.first))) return@filterIndexed false
            val lw = w.lowercase().trim('\'')
            lw !in NOT_NAMES && known.none { k -> k == lw || k.split(Regex("[^\\p{L}\\p{N}]+")).any { it == lw } }
        }.map { it.value }.distinct()
        val n = candidates.firstOrNull() ?: return ""
        val refFlow = ctx.refId?.let { rid -> flow[ctx.channelId]?.let { q -> synchronized(q) { q.firstOrNull { it.id == rid } } } }
        val targetId = when {
            ctx.refTurn?.isBot == true -> refFlow?.replyTo
            ctx.refTurn != null -> built.nameToId[ctx.refTurn.name.lowercase().trim()]
            else -> null
        }?.takeIf { it != ctx.authorId && it != botId }
        val targetName = targetId?.let { id ->
            UserMemoryStore.load(this, id)?.name?.takeIf { it.isNotBlank() } ?: built.nameToId.entries.firstOrNull { it.value == id }?.key
        }
        val whose = if (ctx.refTurn?.isBot == true) "what you said to $targetName" else "$targetName's message"
        return if (targetName != null)
            "\"$n\" isn't a name you know, and it isn't you. They're reacting to $whose, so \"$n\" means $targetName " +
                "(another name for them): treat it that way, don't ask who it is."
        else "\"$n\" isn't a name you know, and it isn't you. Don't guess who it is."
    }

    // ── Conversation following ────────────────────────────────────────────

    private enum class Follow { NONE, FREE, CHECK }

    /** Their last message to Cardinal and his reply to it (what a follow-up would be continuing). */
    private fun lastExchangeWith(channelId: String, authorId: String): Pair<String, String>? {
        val q = flow[channelId] ?: return null
        val m = synchronized(q) { q.lastOrNull { it.isBot && it.replyTo == authorId } } ?: return null
        return m.theirText to m.text
    }

    private fun recordFlow(channelId: String, m: FlowMsg) {
        val q = flow.getOrPut(channelId) { ArrayDeque() }
        synchronized(q) { q.addLast(m); while (q.size > 24) q.removeFirst() }
    }

    private fun markToBot(channelId: String, messageId: String) {
        val q = flow[channelId] ?: return
        synchronized(q) { q.lastOrNull { it.id == messageId }?.toBot = true }
    }

    /**
     * Is this (un-@'d) message from someone Cardinal is talking with still to him? NONE = they're not in a
     * conversation with him, or it's clearly aimed elsewhere (@someone else, a reply to someone else).
     * FREE = the chat hasn't moved on since his reply to them (only they, him, or other people talking TO
     * him since) → treat as addressed, no model call. CHECK = unclear → one cheap check.
     */
    private fun followUpKind(channelId: String, authorId: String, d: JSONObject, raw: String, now: Long): Follow {
        val key = "$channelId:$authorId"
        // "anyways lets forget that … how was your day" right after Cardinal's own line, to nobody else: it's to
        // him even if they weren't in a conversation with him (it used to go unanswered until they re-sent it
        // as a reply).
        if (convoWith[key] == null) {
            val prev = flow[channelId]?.let { q -> synchronized(q) { q.lastOrNull() } }
            if (prev != null && prev.isBot && now - prev.ts < DiscordBotLimits.FOLLOW_FREE_MS && YOU_Q_RE.containsMatchIn(raw) &&
                followUpKindInner(channelId, authorId, d, raw, now) != Follow.NONE) return Follow.FREE
        }
        val last = convoWith[key] ?: return Follow.NONE
        if (now - last > DiscordBotLimits.FOLLOW_WINDOW_MS) { convoWith.remove(key); convoMisses.remove(key); return Follow.NONE }
        val kind = followUpKindInner(channelId, authorId, d, raw, now)
        // They turned to talk with someone else → that conversation with Cardinal is over (they'll @ him again).
        if (kind == Follow.NONE) { convoWith.remove(key); convoMisses.remove(key) }
        return kind
    }

    private fun followUpKindInner(channelId: String, authorId: String, d: JSONObject, raw: String, now: Long): Follow {
        val refAuthor = d.optJSONObject("referenced_message")?.optJSONObject("author")?.optString("id")
        if (refAuthor != null && refAuthor != botId) return Follow.NONE
        val mentions = d.optJSONArray("mentions") ?: JSONArray()
        for (i in 0 until mentions.length()) {
            val id = mentions.optJSONObject(i)?.optString("id")
            if (id != null && id != botId && id != authorId) return Follow.NONE
        }
        if (d.optBoolean("mention_everyone", false)) return Follow.NONE
        val q = flow[channelId] ?: return Follow.CHECK
        val snap = synchronized(q) { q.toList() }
        // "yeah i'll be there bob" names someone else who's in the chat → it's to them.
        val low = " " + raw.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ") + " "
        val others = snap.filter { !it.isBot && it.authorId != authorId }.flatMap { m ->
            val n = m.name.lowercase().trim()
            listOf(n, n.substringBefore(' ')) + UserMemoryStore.load(this, m.authorId)?.let { c -> c.nicknames + c.preferredNick }.orEmpty()
        }.map { it.lowercase().trim() }.filter { it.length >= 3 && it !in COMMON_NICK_WORDS }.toSet()
        if (others.any { low.contains(" $it ") }) return Follow.NONE
        // The line right before theirs was someone else talking TO them ("hana are you making avatars now?"):
        // they're answering that person.
        val prev = snap.lastOrNull()
        if (prev != null && !prev.isBot && prev.authorId != authorId) {
            if (prev.replyTo == authorId) return Follow.NONE
            val me = DiscordRest.displayName(d.optJSONObject("author"), "").lowercase().trim()
            val myNames = (listOf(me, me.substringBefore(' ')) + UserMemoryStore.load(this, authorId)?.let { c -> c.nicknames + c.preferredNick }.orEmpty())
                .map { it.lowercase().trim() }.filter { it.length >= 3 }
            val prevLow = " " + prev.text.replace(Regex("[^\\p{L}\\p{N} ]"), " ").replace(Regex("\\s+"), " ") + " "
            if (myNames.any { prevLow.contains(" $it ") } || prev.text.contains("<@$authorId>") || prev.text.contains("<@!$authorId>")) return Follow.NONE
        }
        val idx = snap.indexOfLast { it.isBot && it.replyTo == authorId }
        if (idx < 0) return Follow.CHECK
        // Free only when they answer him straight away: nothing but their own lines since his reply to them.
        val since = snap.subList(idx + 1, snap.size)
        val straight = since.all { it.authorId == authorId && it.toBot } &&
            now - snap[idx].ts <= DiscordBotLimits.FOLLOW_FREE_MS
        return if (straight && !ROOM_RE.containsMatchIn(raw.lowercase())) Follow.FREE else Follow.CHECK
    }

    private fun normLine(s: String): String = EmojiConvert.normalizeForPrompt(s).lowercase().replace(Regex("\\s+"), " ").trim().take(80)

    private fun persistEmojiUsageIfDirty() {
        val snap = EmojiConvert.usageSnapshotIfDirty(20) ?: return
        getSharedPreferences(EMOJI_PREFS, MODE_PRIVATE).edit().putString("usage", snap).apply()
    }

    /** Situational cue words for channel-bit retrieval (e.g. someone posted an image). */
    private fun situationCues(ctx: MsgCtx): Set<String> =
        if (ctx.hasImage) setOf("image", "images", "pic", "post", "posting") else emptySet()

    /**
     * When a message points at another channel ("did you see that in #media"), pull that channel's
     * recent messages as a clearly-labelled block so Cardinal can talk ABOUT it without confusing it
     * with the current channel. Bounded (one channel, [DiscordBotLimits.CROSSREF_FETCH] messages) and
     * permission-aware: if the bot can't read it, it says so instead of hallucinating.
     */
    private suspend fun buildCrossRef(ctx: MsgCtx): String {
        val targetCh = ctx.refChannels.firstOrNull { it != ctx.channelId } ?: return ""
        val name = ChannelInfoStore.name(targetCh) ?: return ""   // unknown → skip silently
        val msgs = DiscordRest.fetchRecentMessages(cfg.botToken, targetCh, DiscordBotLimits.CROSSREF_FETCH)
        if (msgs.isEmpty()) return "#$name: (you can't see this channel right now)"
        val sb = StringBuilder("#").append(name).append(":\n")
        for (m in msgs) {
            val t = tokenize(ChannelInfoStore.resolveMentions(stripBotMentions(m.content)))
            if (t.isBlank()) continue
            sb.append("- ").append(if (m.authorId == botId) "Cardinal" else m.authorName).append(": ").append(t).append('\n')
        }
        return sb.toString().trim()
    }

    /**
     * Server memories relevant to the recent conversation (+ any revived dead topic). No always-on
     * "core" memories: forcing the same two into every reply made Cardinal bring them up constantly.
     * A memory question lowers the bar to one shared word.
     */
    private fun buildServerCulture(channelId: String, keywords: Set<String>, recall: Boolean): String {
        val mem = ServerMemoryStore.retrieveFor(this, keywords, if (recall) 1 else 2)
        val topics = ConversationStore.reviveFor(this, channelId, keywords.joinToString(" "))
        val sb = StringBuilder()
        mem.forEach { sb.append("- ").append(it).append('\n') }
        if (topics.isNotEmpty()) {
            sb.append("Earlier here:\n")
            topics.forEach { sb.append("- ").append(it).append('\n') }
        }
        return sb.toString().trim()
    }

    /**
     * Resolve a learn-pass "about" NAME/nickname to a Discord id — EXACT match only (conversation
     * names first, then the global name/nickname index). A fuzzy substring fallback was the cause of
     * one person's facts/nicknames landing on a DIFFERENT person (John Pork getting Michael's nicks),
     * so an unresolved name is DROPPED rather than guessed — safer than contaminating a card.
     */
    private fun resolveObserveAbout(about: String, nameToId: Map<String, String>, global: Map<String, String>): String? {
        for (key in nameCandidates(about)) {
            nameToId[key]?.let { return it }
            global[key]?.let { return it }
        }
        return null
    }

    /** "@Alice", "Alice (ali)", "alice's" → the plain forms to look up (each still an EXACT match). */
    private fun nameCandidates(about: String): List<String> {
        val out = LinkedHashSet<String>()
        fun add(s: String) {
            val k = s.trim().trim('"', '\'', '.', ',', ':', ';').trim().removePrefix("@")
                .removeSuffix("'s").removeSuffix("’s").trim()
            if (k.isNotBlank()) out.add(k)
        }
        val base = about.lowercase().trim()
        add(base)
        Regex("^(.*?)\\s*\\((.*?)\\)\\s*$").find(base)?.let { m -> add(m.groupValues[1]); add(m.groupValues[2]) }
        return out.toList()
    }

    private fun handleReactionAdd(d: JSONObject) {
        val messageId = d.optString("message_id")
        val reactorId = d.optString("user_id")
        if (messageId.isBlank() || reactorId.isBlank() || reactorId == botId) return
        val mine = synchronized(recentBotMsgIds) { recentBotMsgIds.containsKey(messageId) }
        if (!mine) return
        val emoji = d.optJSONObject("emoji")?.optString("name").orEmpty()
        if (!d.optJSONObject("emoji")?.optString("id").isNullOrBlank()) EmojiConvert.noteReaction(emoji)
        val sentiment = when {
            emoji in POSITIVE_REACTS -> "warm (reacted ${emoji})"
            emoji in NEGATIVE_REACTS -> "cool (reacted ${emoji})"
            else -> return
        }
        scope.launch {
            val cur = UserMemoryStore.load(this@DiscordBotService, reactorId) ?: UserMemoryStore.Card(id = reactorId)
            // A reaction only fills an empty (or reaction-derived) vibe — never overwrites what was learned.
            if (cur.sentiment.isBlank() || cur.sentiment.contains("(reacted"))
                UserMemoryStore.save(this@DiscordBotService, cur.copy(sentiment = sentiment))
            DiscordBotState.log("$reactorId reacted ${emoji} to Cardinal")
        }
    }

    // ── Chattiness + activity ─────────────────────────────────────────────

    private fun noteActivity(channelId: String, now: Long) {
        val dq = activityWindow.getOrPut(channelId) { ArrayDeque() }
        synchronized(dq) {
            dq.addLast(now)
            while (dq.isNotEmpty() && now - dq.first() > 60_000L) dq.removeFirst()
        }
    }

    private fun channelActivity(channelId: String): Int =
        activityWindow[channelId]?.let { synchronized(it) { it.size } } ?: 0

    /** Ambient chance scales with how busy the channel is (more chatter → a little more likely). */
    private fun effectiveAmbient(channelId: String): Int {
        val base = cfg.ambientPercent
        val boost = channelActivity(channelId).coerceAtMost(10)   // +0..+50%
        return (base * (1.0 + boost / 20.0)).toInt().coerceIn(0, 100)
    }

    /**
     * People don't end every message with an emoji. When Cardinal already used a custom emoji in 2 of his
     * last 3 messages here — or would repeat the same one — a TRAILING emoji is dropped from this reply
     * (mid-sentence ones stay). Free; emojis remain available whenever they're not overused.
     */
    private fun tameEmoji(channelId: String, text: String): String {
        val m = TRAILING_EMOJI_RE.find(text) ?: return text
        val recent = recentBotReplies[channelId]?.let { synchronized(it) { it.toList() } }.orEmpty().takeLast(3)
        val usedRecently = recentEmojiUse[channelId]?.let { synchronized(it) { it.toList() } }.orEmpty()
            .takeLast(2).any { it }
        val same = recent.any { it.contains(m.value.trim()) }
        if (!usedRecently && !same) return text
        val cut = text.substring(0, m.range.first).trimEnd()
        return cut.ifBlank { text }
    }

    private fun rememberBotReply(channelId: String, text: String) {
        val dq = recentBotReplies.getOrPut(channelId) { ArrayDeque() }
        synchronized(dq) {
            dq.addLast(text.take(DiscordBotLimits.MAX_MSG_CHARS))
            while (dq.size > DiscordBotLimits.ANTI_REPEAT_REPLIES) dq.removeFirst()
        }
        // Emoji use is kept as a flag: the stored text is truncated, which hid a long reply's closing emoji.
        val used = recentEmojiUse.getOrPut(channelId) { ArrayDeque() }
        synchronized(used) {
            used.addLast(SHORTCODE_RE.containsMatchIn(text) || UNICODE_EMOJI_RE.containsMatchIn(text))
            while (used.size > 4) used.removeFirst()
        }
    }

    /**
     * Spoken TO by name without a ping ("cardinal, rate my fit", "hey cardinal you up?", "thoughts, cardinal?")
     * — people do that with friends. Talking ABOUT him ("cardinal is kinda mid", "cardinal said…") isn't.
     */
    internal fun calledByName(text: String): Boolean {
        val name = botName.ifBlank { "Cardinal" }.lowercase().trim()
        if (name.length < 3) return false
        val t = text.lowercase().replace(Regex("<[@#:][^>]*>"), " ").trim()
        val n = Regex.escape(name)
        val lead = Regex("^(?:(?:hey|hi|hello|yo|oi|ok|okay|so|and|also|but|pls|please|ayo|ay|sup|yeah|yea|lol|lmao|bro|omg|wait|damn|dang|oh|well|alright)\\s*,?\\s+)?$n\\b\\s*([,:!?]?)\\s*(\\S+)?")
        lead.find(t)?.let { m ->
            val punct = m.groupValues[1]
            val next = m.groupValues[2].trim(',', '.', '!', '?')
            if (punct.isNotEmpty()) return true
            if (next.isEmpty()) return m.value.length < t.length || t == name
            if (next in ABOUT_WORDS) return false
            // "cardinal rates every pizza" describes him; "cardinal thoughts?" / "cardinal thanks" talk to him.
            if (next.length >= 4 && next.endsWith("s") && !next.endsWith("ss") && '?' !in t && next !in VOCATIVE_S) return false
            return true
        }
        // "... cardinal?" / "..., cardinal" at the end
        return Regex("(?:,|\\?|\\b(?:hey|yo|right|huh|lol))\\s*$n\\s*[?!.]*\\s*$").containsMatchIn(t) ||
            Regex("\\?\\s*$n\\s*$").containsMatchIn(t) ||
            // "can you even see the name of this server Cardinal?": a question to "you" ending on his name
            (Regex("\\b(you|your|u|ur|you'?re|youre|ya)\\b").containsMatchIn(t) && Regex("\\b$n\\s*[?!]+\\s*$").containsMatchIn(t)) ||
            // "anyway can we lighten that chat up a lil Cardinal?" — any question ending on his name, unless the word
            // before makes it about him ("what do you think of cardinal?", "have you seen cardinal?").
            Regex("([\\p{L}']+)\\s+$n\\s*\\?+\\s*$").find(t)?.groupValues?.get(1)?.let { it !in ABOUT_BEFORE_NAME } == true ||
            Regex("\\b(thanks|thank you|ty|thx|night|gn|gm|morning|bye|cya|love you|ily|welcome back)\\s+$n\\s*[!.]*\\s*$").containsMatchIn(t)
    }

    /** Cardinal's name appears as a word (not inside another word). */
    internal fun namesBot(text: String): Boolean {
        val name = botName.ifBlank { "Cardinal" }.lowercase().trim()
        if (name.length < 3) return false
        return Regex("(^|[^\\p{L}\\p{N}])" + Regex.escape(name) + "('s|’s)?([^\\p{L}\\p{N}]|$)").containsMatchIn(text.lowercase())
    }

    private val ABOUT_BEFORE_NAME = setOf("of", "about", "to", "with", "at", "for", "from", "like", "than", "seen", "ask", "tell",
        "is", "was", "and", "or", "call", "called", "named", "meet", "met", "ping", "pinged", "on", "by", "the", "hate", "love")
    private val VOCATIVE_S = setOf("thanks", "thoughts", "lets", "please", "yes", "ideas", "opinions", "pls", "plz", "guess", "wyds")
    private val ABOUT_WORDS = setOf("is", "was", "has", "had", "does", "did", "doesn't", "didn't", "isn't", "wasn't",
        "keeps", "always", "never", "just", "said", "says", "thinks", "can't", "cant", "will", "would", "and", "or",
        "the", "'s", "s", "likes", "hates", "loves", "got", "gets", "be", "being", "been", "tho", "though", "lmao", "lol")

    // ── Language / emoji hints ────────────────────────────────────────────

    /** Nudge the reply language from the message's script (the person switching, per the rule). */
    private fun detectLang(text: String): String {
        val byScript = when {
            text.any { it in '぀'..'ヿ' || it in 'ㇰ'..'ㇿ' } -> "Japanese (日本語)"
            text.any { it in '가'..'힣' } -> "Korean (한국어)"
            text.any { it in '一'..'鿿' } -> "Chinese (中文)"
            text.any { it in 'Ѐ'..'ӿ' } -> "Russian (Русский)"
            text.any { it in '؀'..'ۿ' } -> "Arabic (العربية)"
            else -> ""
        }
        if (byScript.isNotBlank()) return byScript
        return latinLang(text)
    }

    /**
     * Latin-script languages share an alphabet with English, so they're told apart by their own letters and
     * common little words. Needs two signals and more of them than English words, so an English line with
     * one borrowed word ("que sera") stays English.
     */
    /** "can you answer in spanish?" / "say it in japanese" / "en español": they asked for a language, so use it. */
    private fun askedLang(text: String): String {
        val low = text.lowercase()
        val m = ASKED_LANG_RE.find(low) ?: return ""
        val want = m.groupValues[1].ifBlank { m.groupValues[2] }
        return ASKED_LANG_NAMES[want] ?: want.replaceFirstChar { it.uppercase() }
    }

    private val ASKED_LANG_RE = Regex(
        "\\b(?:answer|reply|respond|say|talk|speak|write|text|tell|translate)\\b[^.!?\\n]{0,30}?\\b(?:in|into) " +
            "(spanish|español|espanol|french|français|german|deutsch|italian|portuguese|dutch|polish|russian|japanese|korean|chinese|mandarin|arabic|hindi|turkish|swedish|greek|hebrew|thai|vietnamese|indonesian|tagalog)\\b" +
            "|\\ben (español|espanol|français|francais)\\b"
    )
    private val ASKED_LANG_NAMES = mapOf(
        "español" to "Spanish", "espanol" to "Spanish", "français" to "French", "francais" to "French",
        "deutsch" to "German", "mandarin" to "Chinese",
    )

    private fun latinLang(text: String): String {
        val low = text.lowercase()
        val words = Regex("[\\p{L}']+").findAll(low).map { it.value }.toList()
        if (words.size < 2) return ""
        val english = words.count { it in EN_WORDS }
        var best = ""; var bestScore = 0
        for ((lang, cues) in LATIN_CUES) {
            val score = words.count { it in cues.words } + (if (low.any { it in cues.chars }) 2 else 0)
            if (score > bestScore) { best = lang; bestScore = score }
        }
        return if (bestScore >= 2 && bestScore > english) best else ""
    }

    private class LangCues(val words: Set<String>, val chars: String)
    private val EN_WORDS = setOf("the", "and", "you", "is", "are", "what", "do", "i", "it", "to", "of", "a", "my", "your", "that", "this", "in", "on", "for", "with", "was", "have", "how", "why")
    private val LATIN_CUES = linkedMapOf(
        "Spanish (español)" to LangCues(setOf("qué", "que", "los", "las", "el", "es", "muy", "pero", "por", "para", "cómo", "como", "tú", "yo", "está", "estás", "hola", "gracias", "opinas", "tienes", "una", "del", "y", "sí", "también"), "¿¡ñ"),
        "Portuguese (português)" to LangCues(setOf("você", "voce", "não", "nao", "é", "os", "as", "uma", "muito", "obrigado", "obrigada", "tudo", "bem", "está", "isso", "com", "para", "que", "eu", "tá"), "ãõç"),
        "French (français)" to LangCues(setOf("je", "tu", "est", "les", "des", "une", "pas", "c'est", "quoi", "pourquoi", "bonjour", "salut", "merci", "avec", "très", "vous", "nous", "mais", "et", "le", "la"), "àâçéèêëîïôûœ"),
        "German (Deutsch)" to LangCues(setOf("ich", "du", "ist", "und", "nicht", "das", "der", "die", "was", "wie", "danke", "hallo", "bist", "mit", "ein", "eine", "auch", "sehr"), "äöüß"),
        "Italian (italiano)" to LangCues(setOf("che", "non", "sono", "come", "ciao", "grazie", "perché", "molto", "il", "gli", "della", "una", "anche", "cosa", "sei"), "àèìòù"),
        "Dutch (Nederlands)" to LangCues(setOf("ik", "je", "het", "een", "niet", "wat", "hoe", "dank", "hallo", "jij", "zijn", "met", "ook", "maar", "dat"), ""),
        "Polish (polski)" to LangCues(setOf("jest", "nie", "się", "jak", "co", "czy", "dzięki", "cześć", "ty", "ja", "to", "tak"), "ąęłńśźż"),
    )

    /**
     * The server's most-used custom emojis, plus any whose name matches what's being talked about
     * (":pizza_cat:" when pizza comes up) — so every emoji is reachable without paying for all of them
     * on every reply. Unicode emojis are always available.
     */
    private fun emojiHint(keywords: Set<String>): String {
        val top = EmojiConvert.topNames(DiscordBotLimits.EMOJI_HINT_MAX)
        val want = keywords.filter { it.length >= 3 }.map { discordStem(it.lowercase()) }.toSet()
        val topical = if (want.isEmpty()) emptyList() else EmojiConvert.customNames().filter { n ->
            n !in top && n.lowercase().split('_', '-').plus(n.lowercase()).any { p -> p.length >= 3 && discordStem(p) in want }
        }.take(DiscordBotLimits.EMOJI_TOPICAL_MAX)
        return (top + topical).joinToString(", ") { ":$it:" }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private val triviaWords = setOf(
        "lol", "lmao", "lmfao", "lel", "kek", "ok", "kk", "k", "nice", "fr", "real",
        "bruh", "true", "yep", "yup", "nah", "w", "l", "based", "same", "mood"
    )
    /**
     * Banter, not a real request: laughing/teasing markers ("shut up lmao", "stop 😂", "you're so cringe
     * lol") with nothing that makes it serious ("genuinely", "for real", "please", "i mean it").
     */
    private fun isJoking(text: String): Boolean =
        (JOKE_RE.containsMatchIn(text) || NONSENSE_RE.containsMatchIn(text) || BANTER_AT_HIM_RE.containsMatchIn(text)) &&
            !SERIOUS_RE.containsMatchIn(text)
    // "i release my houd dogs to go kill cardinal": roleplay aimed at him is a bit, not a fact about the speaker.
    private val BANTER_AT_HIM_RE = Regex("(?i)\\b(kill|murder|destroy|nuke|beat up|fight|sic|attack|delete|unplug|shut down|hunt|eat)\\b.{0,40}\\bcardinal\\b|" +
        "\\bcardinal\\b.{0,40}\\b(dies|is dead|gets? (killed|deleted|unplugged)|only option is to run)\\b")
    // "pluh pluh pluh im a little fishie": a sound repeated three times is a bit, not a statement about themselves.
    private val NONSENSE_RE = Regex("(?i)\\b(\\p{L}{2,8})\\b(?:\\W+\\1\\b){2,}")

    private fun isStopRequest(text: String): Boolean {
        if (isJoking(text)) return false
        val t = text.lowercase().replace(Regex("[^\\p{L}\\p{N}'’ ]"), " ").replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty()) return false
        if (STOP_WHOLE_RE.matches(t)) return true
        val m = STOP_PHRASE_RE.find(t) ?: return false
        val before = t.substring(0, m.range.first).trimEnd().substringAfterLast(' ').replace('’', '\'')
        return before !in NEGATIONS
    }

    private fun isTrivial(text: String): Boolean {
        val n = text.trim().lowercase().trimEnd('!', '.', '?', ' ')
        if (n.isEmpty()) return true
        if (n in triviaWords) return true
        return n.length <= 2 || n.none { it.isLetterOrDigit() }
    }
    /**
     * Parse an explicit react request into emoji tokens (custom `:name:` / `<:name:id>` or unicode).
     * Returns null unless the message clearly asks Cardinal to REACT (so normal chat is untouched) AND
     * carries at least one emoji. The tokens pass through [EmojiConvert.reactionToken] at react time.
     */
    private fun parseReactRequest(text: String): List<String>? {
        if (!REACT_REQ_RE.containsMatchIn(text)) return null
        val out = LinkedHashSet<String>()
        SHORTCODE_RE.findAll(text).forEach { m ->
            val name = m.groupValues[1].ifBlank { m.groupValues[2] }
            if (name.isNotBlank()) out.add(name)
        }
        UNICODE_EMOJI_RE.findAll(text).forEach { out.add(it.value) }
        if (out.isNotEmpty()) return out.take(4).toList()
        // No emoji in the message: "react with a pregnant man emoji" / "react to my message with a reaction".
        // He used to answer in text ("give me a second, i gotta find it") or claim "there. satisfied?" without
        // reacting. Only for a clear command, so "how would you react to this news?" is still a question.
        if (!REACT_CMD_RE.containsMatchIn(text)) return null
        val named = REACT_NAMED_RE.find(text)?.groupValues?.get(1)?.trim()
        return listOf(named?.let { EmojiConvert.byName(it) } ?: pickEmoji(text))
    }

    /** "give me a pregnant man pretty please" (an emoji by name), or "with a pregnant man" right after asking him
     *  to react ("yeah react to my message with one" → "with a pregnant man"). Only when the name IS an emoji. */
    private fun parseReactFollowUp(text: String, afterRequest: Boolean): List<String>? {
        val phrase = REACT_GIVE_RE.find(text)?.groupValues?.get(1)
            ?: if (afterRequest) REACT_WITH_FRAGMENT_RE.find(text)?.groupValues?.get(1) else null
        // A pronoun/filler is never an emoji name ("do you?" found "I LOVE YOU HAND SIGN").
        if (phrase == null || phrase.trim().lowercase().split(' ').all { it in NOT_EMOJI_WORDS }) return null
        return EmojiConvert.byName(phrase.trim())?.let { listOf(it) }
    }

    private val reactEmojis = listOf("👍", "😂", "💀", "👀", "🔥", "😭", "🙏")
    private fun pickEmoji(text: String): String {
        val t = text.lowercase()
        return when {
            t.contains("lol") || t.contains("lmao") || t.contains("😂") || t.contains("🤣") -> "😂"
            t.contains("💀") -> "💀"
            else -> reactEmojis.random()
        }
    }

    private val KW_STOP = setOf(
        "the","a","an","and","or","but","to","of","in","on","for","with","is","are","was","this",
        "that","it","you","your","they","just","like","lol","cardinal","what","why","how","when",
        "who","has","had","have","his","her","him","she","its","our","out","one","now","too","see",
        "say","let","not","all","any","can","did","get","got","yes","yep","nah","lmao","omg","bro",
        "haha","hey","hi","ok","okay","yeah","wow","way","own","off","yet","also","then","than",
        "there","here","about","know","does","doing","from","into","were","been","being","some",
        "really","very","much","more","most","would","could","should","will","dont","cant","thats",
        "im","ive","its","youre","whats","who's","what's","me","my","we","us","them","their","do",
        // Everyday verbs/fillers: "he really thinks he can handle me" pulled in dave's "thinks JJ's is fun".
        "think","thinks","thought","gonna","wanna","gotta","want","wants","going","make","makes","made",
        "thing","things","stuff","good","feel","feels","actually","still","even","well","back","only",
        "right","though","sure","maybe","literally","kinda","lowkey","ngl","tho","rn","already","need",
        "take","come","look","give","tell","said","says","saying","keep","go","goes","went","use","used"
    )
    // 3+ letters so short-but-meaningful words (cat, dog, gym, art) still drive relevance.
    private fun keywordList(s: String): List<String> =
        Regex("[\\p{L}\\p{N}]+").findAll(s.lowercase()).map { it.value }
            .filter { it.length >= 3 && it !in KW_STOP }.distinct().toList()
    private fun keywordsOf(s: String): Set<String> = keywordList(s).take(20).toHashSet()

    private fun tokenize(content: String): String =
        EmojiConvert.normalizeForPrompt(URL_RE.replace(content, "[link]"))
            .replace(Regex("[ \\t]{2,}"), " ").trim().take(DiscordBotLimits.MAX_MSG_CHARS)

    private fun messageMentionsBot(d: JSONObject, content: String): Boolean {
        if (botId.isBlank()) return false
        val mentions = d.optJSONArray("mentions") ?: JSONArray()
        for (i in 0 until mentions.length()) if (mentions.optJSONObject(i)?.optString("id") == botId) return true
        return content.contains("<@$botId>") || content.contains("<@!$botId>")
    }

    // History text arrives with mentions already resolved to "@Name", the live message as "<@id>";
    // strip both so the transcript is consistent (and a few tokens shorter).
    private fun stripBotMentions(content: String): String {
        var t = content.replace("<@$botId>", "").replace("<@!$botId>", "")
        val name = botName
        if (name.isNotBlank()) t = t.replace(Regex("(?i)@" + Regex.escape(name) + "\\b"), "")
        return t.replace(Regex("[ \\t]{2,}"), " ").trim()
    }

    private fun ambientCooldownOk(channelId: String, now: Long): Boolean =
        now - (ambientCooldown[channelId] ?: 0L) >= cfg.ambientCooldownSec * 1000L

    private fun postedRecently(channelId: String, now: Long): Boolean =
        now - (lastBotPostMs[channelId] ?: 0L) < DiscordBotLimits.SELF_RECENT_QUIET_MS

    private fun trace(ctx: MsgCtx, score: String, plan: String, action: String, detail: String) {
        DiscordBotState.addTrace(DiscordBotState.Trace(
            atMs = System.currentTimeMillis(),
            channel = ctx.channelId,
            author = ctx.authorName,
            score = when { ctx.freeFollow || ctx.followUp -> "follow-up/$score"; ctx.addressed -> "addressed/$score"; else -> "ambient/$score" },
            plan = plan, action = action, detail = detail,
        ))
    }

    // ── Foreground plumbing ───────────────────────────────────────────────

    private fun teardown(reason: String) {
        heartbeatJob?.cancel(); heartbeatJob = null
        reconnectJob?.cancel(); reconnectJob = null
        budgetJob?.cancel(); budgetJob = null
        routeJobs.forEach { it.cancel() }; routeJobs.clear()
        val old = webSocket
        socketGen.incrementAndGet()
        webSocket = null
        try { old?.close(1000, reason) } catch (_: Exception) {}
        DiscordBotState.setRunning(false)
        DiscordBotState.setStatus(DiscordBotState.Status.IDLE, reason)
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        NotificationChannel(NOTIF_CHANNEL, "Discord Bot", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Shows while the Discord AI bot is connected"
            setShowBadge(false); setSound(null, null)
            nm.createNotificationChannel(this)
        }
    }

    private fun buildNotif(status: String): Notification {
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIF_CHANNEL)
            .setSmallIcon(R.drawable.ic_notif_sync)
            .setContentTitle("VRC-A Discord Bot")
            .setContentText(status)
            .setContentIntent(tap)
            .setOngoing(true).setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setGroup("vrca_service")
            .build()
    }
}

private val POSITIVE_REACTS = setOf("👍", "😂", "🤣", "🔥", "❤️", "🙏", "😭", "💯", "😎", "✅")
private val NEGATIVE_REACTS = setOf("👎", "🤡", "💩", "🙄", "❌")
