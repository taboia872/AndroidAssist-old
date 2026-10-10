package com.satory.graphenosai.service

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.content.Context
import android.content.Intent
import android.util.Log
import com.satory.graphenosai.ui.CompactAssistantActivity

/**
 * Session service for voice interaction.
 */
class AssistantVoiceInteractionSessionService : VoiceInteractionSessionService() {

    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return AssistantVoiceInteractionSession(this)
    }
}

/**
 * Voice interaction session handling.
 * This is invoked when the assistant is triggered via system mechanisms
 * (long-press home, assistant gesture, etc.)
 */
class AssistantVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    companion object {
        private const val TAG = "AssistantVoiceSession"
        private const val HIDE_DELAY_MS = 450L
        private val hideHandler = android.os.Handler(android.os.Looper.getMainLooper())
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)

        try {
            // A VoiceInteractionSession is allowed to surface UI for the active
            // assistant. Start it through the session rather than from the
            // background service; recent OxygenOS/Android builds can reject the
            // latter and previously leave the user with a brief flash.
            val serviceIntent = Intent(context, AssistantService::class.java).apply {
                action = AssistantService.ACTION_ACTIVATE
                putExtra(AssistantService.EXTRA_TRIGGER, "voice_interaction")
                putExtra(AssistantService.EXTRA_LAUNCH_OVERLAY, false)
            }
            context.startForegroundService(serviceIntent)

            val overlayIntent = Intent(context, CompactAssistantActivity::class.java)
            startVoiceActivity(overlayIntent)

            // Hiding the session window in the same frame races the overlay
            // activity launch: on Android 14+ the system can cancel the pending
            // activity start when the session window goes away, leaving the
            // assistant "selected but never appears". Give the launch a head
            // start, then hide the (empty) session window.
            hideHandler.postDelayed({ hide() }, HIDE_DELAY_MS)
        } catch (error: RuntimeException) {
            // Never let a system-assistant callback crash the process. A crash
            // here makes the system tear the VoiceInteractionService down and
            // reset the default-assistant selection to none.
            Log.e(TAG, "Could not launch assistant from voice interaction", error)
        }
    }

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    override fun onHandleAssist(
        data: Bundle?,
        structure: android.app.assist.AssistStructure?,
        content: android.app.assist.AssistContent?
    ) {
        // Handle assist data if available
        @Suppress("DEPRECATION")
        super.onHandleAssist(data, structure, content)
    }
}
