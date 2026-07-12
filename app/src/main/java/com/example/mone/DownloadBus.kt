package com.example.mone

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/** Lightweight main-thread event bus so activities can observe downloads run by the service. */
object DownloadBus {

    enum class Phase { PROGRESS, DONE_OK, DONE_FAIL, CANCELLED }

    data class Update(
        val jobId: Int,
        val percent: Int,
        val line: String,
        val phase: Phase,
        val message: String,
    )

    private val handler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(Update) -> Unit>()

    fun addListener(listener: (Update) -> Unit) = listeners.add(listener)
    fun removeListener(listener: (Update) -> Unit) = listeners.remove(listener)

    fun emit(update: Update) {
        handler.post { listeners.forEach { it(update) } }
    }
}
