package com.vrca.vrchat

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Handles notification-tap actions that perform a VRChat REST call instead of
 * opening a web page — re-inviting yourself to an instance you were invited to
 * ("invite_me"), or inviting a requester to your current instance
 * ("invite_user"). The same action types back the in-app alert buttons.
 */
class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val actionType = intent.getStringExtra(EXTRA_ACTION_TYPE) ?: return
        val actionData = intent.getStringExtra(EXTRA_ACTION_DATA) ?: ""
        val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, -1)
        val appCtx = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val ok = perform(appCtx, actionType, actionData)
                withContext(Dispatchers.Main) {
                    Toast.makeText(appCtx, feedback(actionType, ok), Toast.LENGTH_SHORT).show()
                }
                if (ok && notifId != -1) {
                    (appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                        .cancel(notifId)
                }
            } catch (e: Exception) {
                Log.w(TAG, "notification action failed: $actionType", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "NotifActionReceiver"
        const val ACTION = "com.vrca.vrchat.NOTIFICATION_ACTION"
        const val EXTRA_ACTION_TYPE = "action_type"
        const val EXTRA_ACTION_DATA = "action_data"
        const val EXTRA_NOTIF_ID = "notif_id"

        const val ACTION_INVITE_ME = "invite_me"
        const val ACTION_INVITE_USER = "invite_user"

        fun buildIntent(
            context: Context,
            actionType: String,
            actionData: String?,
            notifId: Int
        ): Intent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = ACTION
            putExtra(EXTRA_ACTION_TYPE, actionType)
            putExtra(EXTRA_ACTION_DATA, actionData ?: "")
            putExtra(EXTRA_NOTIF_ID, notifId)
        }

        /**
         * Runs the VRChat call for [actionType]. Shared by the notification
         * receiver and the in-app alert buttons. Returns true on success.
         */
        suspend fun perform(context: Context, actionType: String, actionData: String): Boolean =
            when (actionType) {
                ACTION_INVITE_ME -> VrchatAuthManager.inviteSelfToInstance(context, actionData)
                ACTION_INVITE_USER -> {
                    val location = VrchatPipelineState.presence?.location.orEmpty()
                    if (location.isBlank() || !location.startsWith("wrld_")) false
                    else VrchatAuthManager.inviteUserToInstance(context, actionData, location)
                }
                else -> false
            }

        fun feedback(actionType: String, ok: Boolean): String = when (actionType) {
            ACTION_INVITE_ME ->
                if (ok) "Invite sent — check your VRChat notifications" else "Could not send the invite"
            ACTION_INVITE_USER ->
                if (ok) "Invite sent to their account" else "Could not invite them (are you in a joinable instance?)"
            else -> if (ok) "Done" else "Action failed"
        }
    }
}
