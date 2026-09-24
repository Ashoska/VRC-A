package com.vrca.discordbot

/**
 * The network endpoints Cardinal talks to, in one place.
 *
 * The defaults are the real Discord + Cloudflare hosts, and the app itself NEVER changes them. The
 * only writer is the JVM **Cardinal Lab** harness (`app/src/test/kotlin/com/vrca/discordbot/lab/`),
 * which points them at a local fake Discord + a recording Workers-AI proxy so the REAL bot code
 * (routing, prompts, memory, learning) can be exercised end-to-end without a phone. Keeping them
 * as plain vars instead of `const` is the whole of the lab's footprint on production code.
 */
internal object BotEndpoints {
    const val PROD_GATEWAY = "wss://gateway.discord.gg/?v=10&encoding=json"
    const val PROD_DISCORD_API = "https://discord.com/api/v10"
    const val PROD_CF_API = "https://api.cloudflare.com/client/v4"
    const val PROD_AI_GATEWAY = "https://gateway.ai.cloudflare.com/v1"

    @Volatile var gatewayUrl: String = PROD_GATEWAY
    @Volatile var discordApi: String = PROD_DISCORD_API
    @Volatile var cfApi: String = PROD_CF_API
    @Volatile var aiGateway: String = PROD_AI_GATEWAY
}
