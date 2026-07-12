package com.example.mone

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/** In-memory list of all downloads (queued, running, finished) — backs the queue screen. */
object DownloadStore {

    enum class State { QUEUED, DOWNLOADING, DONE, FAILED, CANCELLED }

    data class Task(
        val id: Int,
        val url: String,
        @Volatile var title: String,
        @Volatile var state: State,
        @Volatile var percent: Int,
    )

    private val tasks = CopyOnWriteArrayList<Task>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun snapshot(): List<Task> = tasks.toList()

    fun add(task: Task) { tasks.add(0, task); changed() }

    private fun find(id: Int) = tasks.firstOrNull { it.id == id }

    fun setState(id: Int, state: State) = find(id)?.let {
        if (it.state != state) { it.state = state; changed() }
    }

    fun setTitle(id: Int, title: String) = find(id)?.let {
        if (it.title != title) { it.title = title; changed() }
    }

    fun setProgress(id: Int, percent: Int) = find(id)?.let {
        if (it.percent != percent) { it.percent = percent; changed() }
    }

    fun clearFinished() {
        tasks.removeAll { it.state == State.DONE || it.state == State.FAILED || it.state == State.CANCELLED }
        changed()
    }

    fun counts(): Triple<Int, Int, Int> {
        val snap = snapshot()
        val active = snap.count { it.state == State.DOWNLOADING }
        val queued = snap.count { it.state == State.QUEUED }
        val done = snap.count { it.state == State.DONE }
        return Triple(active, queued, done)
    }

    fun current(): Task? = snapshot().firstOrNull { it.state == State.DOWNLOADING }

    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)
    private fun changed() = main.post { listeners.forEach { it() } }
}
