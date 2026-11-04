package mcp

import lib.IntervalsApiException
import java.io.IOException
import java.nio.channels.UnresolvedAddressException

/** Turns failures into messages Claude can relay to a non-technical coach. */
object Errors {

    fun explain(e: Throwable): String = when (e) {
        is NotConnectedException -> e.message!!
        is IntervalsApiException -> when (e.status) {
            401 -> "intervals.icu rejected the API key (it may have been regenerated or revoked). " +
                "Call connect_intervals so the coach can connect again."
            403 -> "intervals.icu denied access (${e.method} ${e.path}). If this is an athlete, they have not " +
                "shared their account with the coach yet: on intervals.icu the athlete opens Settings and adds " +
                "the coach. Then try again."
            404 -> "Not found on intervals.icu (${e.method} ${e.path}). Check the athlete ID (like i123456) or the " +
                "event/activity ID. If the ID is right, the athlete may not have shared their account with the coach."
            400, 422 -> "intervals.icu refused the data (${e.status}): ${e.body.take(300)}"
            429 -> "intervals.icu is limiting requests right now. Wait a minute and try again."
            in 500..599 -> "intervals.icu is having problems (${e.status}). Try again in a few minutes."
            else -> e.message ?: "intervals.icu error ${e.status}"
        }
        is IOException, is UnresolvedAddressException ->
            "Could not reach intervals.icu. Check the internet connection and try again."
        is IllegalArgumentException -> e.message ?: "Invalid input"
        else -> "Unexpected error: ${e.message?.take(300) ?: e::class.simpleName}"
    }
}

class NotConnectedException : IllegalStateException(
    "Coaching Kit is not connected to intervals.icu yet. Call connect_intervals: it opens a page in the " +
        "coach's browser where they paste their API key (never ask for the key in the chat)."
)
