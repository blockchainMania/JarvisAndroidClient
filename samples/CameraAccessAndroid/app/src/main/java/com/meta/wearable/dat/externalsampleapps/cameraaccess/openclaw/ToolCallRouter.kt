package com.meta.wearable.dat.externalsampleapps.cameraaccess.openclaw

// NOTE: Routes Gemini function calls to the Jarvis bridge (`OpenClawBridge`
// is the class name kept for wiring compatibility — it's a Jarvis client).
// Dispatch is by function name now, not a single `execute` task string.

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class ToolCallRouter(
    private val bridge: OpenClawBridge,
    private val scope: CoroutineScope,
    private val localToolHandler: (suspend (GeminiFunctionCall) -> ToolResult)? = null,
) {
    companion object {
        private const val TAG = "ToolCallRouter"
        private const val MAX_CONSECUTIVE_FAILURES = 3
    }

    private val inFlightJobs = mutableMapOf<String, Job>()
    private var consecutiveFailures = 0

    fun handleToolCall(
        call: GeminiFunctionCall,
        sendResponse: (JSONObject) -> Unit
    ) {
        val callId = call.id
        val callName = call.name

        Log.d(TAG, "Received: $callName (id: $callId) args: ${call.args}")

        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
            Log.d(TAG, "Circuit breaker open ($consecutiveFailures consecutive failures), rejecting $callId")
            val errorResult = ToolResult.Failure(
                "Tool execution is temporarily unavailable after $consecutiveFailures consecutive failures. " +
                "Please tell the user you cannot complete this action right now and suggest they check the Jarvis API connection."
            )
            sendResponse(buildToolResponse(callId, callName, errorResult))
            return
        }

        val job = scope.launch {
            val result = when {
                localToolHandler != null && callName in LOCAL_TOOL_NAMES -> localToolHandler.invoke(call)
                else -> bridge.dispatch(callName, call.args)
            }

            if (!coroutineContext[Job]!!.isCancelled) {
                Log.d(TAG, "Result for $callName (id: $callId): $result")

                when (result) {
                    is ToolResult.Success -> consecutiveFailures = 0
                    is ToolResult.Failure -> consecutiveFailures++
                }

                sendResponse(buildToolResponse(callId, callName, result))
            } else {
                Log.d(TAG, "Task $callId was cancelled, skipping response")
            }

            inFlightJobs.remove(callId)
        }

        inFlightJobs[callId] = job
    }

    private val LOCAL_TOOL_NAMES = setOf("capture_current_view", "start_recording", "stop_recording")

    fun cancelToolCalls(ids: List<String>) {
        for (id in ids) {
            inFlightJobs[id]?.let { job ->
                Log.d(TAG, "Cancelling in-flight call: $id")
                job.cancel()
                inFlightJobs.remove(id)
            }
        }
        bridge.setToolCallStatus(ToolCallStatus.Cancelled(ids.firstOrNull() ?: "unknown"))
    }

    fun cancelAll() {
        for ((id, job) in inFlightJobs) {
            Log.d(TAG, "Cancelling in-flight call: $id")
            job.cancel()
        }
        inFlightJobs.clear()
        consecutiveFailures = 0
    }

    private fun buildToolResponse(
        callId: String,
        name: String,
        result: ToolResult
    ): JSONObject {
        return JSONObject().apply {
            put("toolResponse", JSONObject().apply {
                put("functionResponses", JSONArray().put(JSONObject().apply {
                    put("id", callId)
                    put("name", name)
                    put("response", result.toJSON())
                }))
            })
        }
    }
}
