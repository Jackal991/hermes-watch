package com.hermes.watch

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import org.json.JSONObject

/**
 * Receives the phone app's reply on the Wearable Data Layer.
 *
 * The phone companion sends a message on path "/hermes/result" with a JSON
 * payload {"text": "<reply>"}. This service parses it and hands it to the
 * UI (MainActivity) via [onResult].
 */
class DataLayerListenerService : WearableListenerService() {

    override fun onMessageReceived(messageEvent: MessageEvent) {
        Log.d(TAG, "onMessageReceived path=${messageEvent.path} src=${messageEvent.sourceNodeId}")
        if (messageEvent.path != PATH_RESULT) return
        try {
            val json = JSONObject(String(messageEvent.data, Charsets.UTF_8))
            val reply = json.optString("text")
            Log.d(TAG, "result payload text='$reply' handler=${resultHandler != null}")
            val handler = resultHandler
            if (reply.isNotEmpty() && handler != null) {
                handler(reply)
            }
        } catch (e: Exception) {
            Log.e(TAG, "parse error: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "HermesWatch"
        const val PATH_COMMAND = "/hermes/command"
        const val PATH_RESULT = "/hermes/result"

        /** Callback invoked on the main thread when a reply arrives. */
        var resultHandler: ((String) -> Unit)? = null
    }
}
