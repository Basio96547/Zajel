package com.securemessenger.app.ui.screens.chat

import com.securemessenger.app.ui.viewmodel.MessageUiModel
import com.securemessenger.core.crypto.MediaCodec
import java.text.SimpleDateFormat
import java.util.*

// ---------- rows (messages + day separators) ----------
// Extracted from ConversationScreen.kt — see the pointer comment there.

internal sealed class ChatRow(val key: String) {
    class Day(val label: String, id: String) : ChatRow("day-$id")
    class Msg(
        val message: MessageUiModel,
        // Whether this bubble sits first/last in a run of consecutive
        // same-sender messages (same calendar day) — drives the tighter
        // grouped spacing and the "tail only on the last one" shape.
        val isFirstInGroup: Boolean,
        val isLastInGroup: Boolean
    ) : ChatRow("msg-${message.id ?: message.timestamp}")
    // 2+ caption-less photos from the same sender sent within a couple of
    // minutes of each other — rendered as one mosaic grid instead of a
    // stack of separate bubbles.
    class Album(val images: List<MessageUiModel>) : ChatRow("album-${images.first().id ?: images.first().timestamp}")
}

private const val ALBUM_BATCH_WINDOW_MS = 120_000L

internal fun buildRows(messages: List<MessageUiModel>): List<ChatRow> {
    val rows = mutableListOf<ChatRow>()
    var lastDay = ""
    var i = 0
    while (i < messages.size) {
        val m = messages[i]
        val dayId = dayKey(m.timestamp)
        if (dayId != lastDay) {
            rows += ChatRow.Day(dayLabel(m.timestamp), dayId)
            lastDay = dayId
        }

        fun isAlbumable(x: MessageUiModel) =
            !x.isDeleted && x.media?.mediaType == MediaCodec.TYPE_IMAGE && x.media.caption.isNullOrBlank()

        if (isAlbumable(m)) {
            val batch = mutableListOf(m)
            var j = i + 1
            while (j < messages.size) {
                val next = messages[j]
                if (dayKey(next.timestamp) == dayId && isAlbumable(next) && next.direction == m.direction &&
                    next.timestamp - batch.last().timestamp <= ALBUM_BATCH_WINDOW_MS
                ) {
                    batch += next
                    j++
                } else break
            }
            if (batch.size >= 2) {
                rows += ChatRow.Album(batch)
                i = j
                continue
            }
        }

        val prev = messages.getOrNull(i - 1)
        val next = messages.getOrNull(i + 1)
        val groupedWithPrev = prev != null && dayKey(prev.timestamp) == dayId && prev.direction == m.direction
        val groupedWithNext = next != null && dayKey(next.timestamp) == dayId && next.direction == m.direction
        rows += ChatRow.Msg(m, isFirstInGroup = !groupedWithPrev, isLastInGroup = !groupedWithNext)
        i++
    }
    return rows
}

private fun dayKey(ts: Long): String =
    SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date(ts))

private fun dayLabel(ts: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = ts }
    fun sameDay(offset: Int): Boolean {
        val c = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, offset) }
        return c.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
            c.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    }
    return when {
        sameDay(0) -> "اليوم"
        sameDay(-1) -> "أمس"
        else -> SimpleDateFormat("d MMMM yyyy", Locale.getDefault()).format(Date(ts))
    }
}
