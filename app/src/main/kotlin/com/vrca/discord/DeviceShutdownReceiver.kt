package com.vrca.discord

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * On device shutdown / power-off, cleanly close the Discord gateway so the RPC
 * presence DISAPPEARS.
 *
 * A phone/PC clears its Discord presence on shutdown because the OS closes the
 * network socket, so Discord's gateway sees a clean disconnect and drops the
 * session. A Quest FULL-power-off instead severs VRC-A's WebView gateway socket
 * WITHOUT a clean close, so Discord's session (and the RPC) lingers for hours —
 * the reported "shut the headset off but the RPC is still there" bug.
 *
 * This mirrors what the OS does for a normal app: on ACTION_SHUTDOWN we send a
 * clean WebSocket close (VRCA_closeGateway → OP 3 clear + gw.close(1000)), which
 * makes Discord drop the session immediately. Best-effort: goAsync() + a short
 * wait keeps the process alive long enough for the close frame to flush before
 * the device dies. (If a device hard-cuts power with no ACTION_SHUTDOWN, this
 * can't run and we fall back to Discord's own session timeout.)
 */
class DeviceShutdownReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("DiscordRPC", "Device shutdown (${intent.action}) — closing Discord gateway")
        val pending = goAsync()
        runCatching { DiscordRpcService.closeGatewayForShutdown() }
        // Keep the process alive briefly so the posted JS eval + WebSocket close
        // frame actually send before the system tears us down. Off the main thread
        // (the main looper needs to run the eval), then release the broadcast.
        Thread {
            try { Thread.sleep(1200) } catch (_: InterruptedException) {}
            runCatching { pending.finish() }
        }.start()
    }
}
