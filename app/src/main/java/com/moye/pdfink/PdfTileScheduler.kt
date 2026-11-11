package com.moye.pdfink

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import java.util.PriorityQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** 可见块和当前页的优先级顺序：0 当前可见，1 当前预取，2 邻页可见，3 邻页预取。 */
object PdfTilePriority {
    fun value(currentPage: Boolean, visibleTile: Boolean): Int =
        (if (currentPage) 0 else 2) + if (visibleTile) 0 else 1
}

/**
 * 跨页面共享的 PDF 分块优先队列。所有 PdfRenderer 访问顺序统一，
 * 当前页可见块优先于相邻页面和预取块；同一缓存键只执行一次。
 */
object PdfTileScheduler {
    private data class Subscriber(
        val owner: Long,
        val generation: Int,
        val callback: (Boolean) -> Unit
    )

    private class Task(
        val key: String,
        var priority: Int,
        var sequence: Long,
        val render: () -> Bitmap
    ) {
        val subscribers = ArrayList<Subscriber>()
        var running = false
        var cancelled = false
    }

    private val lock = Any()
    private val queue = PriorityQueue<Task>(
        compareBy<Task> { it.priority }.thenBy { it.sequence }
    )
    private val tasks = HashMap<String, Task>()
    private val sequence = AtomicLong()
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "moye-pdf-render").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    private var workerScheduled = false

    fun submit(
        key: String,
        owner: Long,
        generation: Int,
        priority: Int,
        render: () -> Bitmap,
        callback: (Boolean) -> Unit
    ) {
        var rejected = false
        synchronized(lock) {
            val existing = tasks[key]
            if (existing != null) {
                existing.cancelled = false
                if (existing.subscribers.none {
                    it.owner == owner && it.generation == generation
                }) {
                    existing.subscribers.add(Subscriber(owner, generation, callback))
                }
                if (!existing.running && priority < existing.priority) {
                    queue.remove(existing)
                    existing.priority = priority
                    existing.sequence = sequence.incrementAndGet()
                    queue.offer(existing)
                }
            } else if (queue.size >= MAX_QUEUED) {
                rejected = true
            } else {
                val task = Task(key, priority, sequence.incrementAndGet(), render)
                task.subscribers.add(Subscriber(owner, generation, callback))
                tasks[key] = task
                queue.offer(task)
            }
            if (!rejected) scheduleWorkerLocked()
        }
        if (rejected) main.post { callback(false) }
    }

    /**
     * 移除该 View 当前视口不再需要的订阅。其他页面仍需要同一块时不取消共享任务。
     */
    fun retain(owner: Long, generation: Int, keepKeys: Set<String>) {
        synchronized(lock) {
            val iterator = tasks.values.iterator()
            while (iterator.hasNext()) {
                val task = iterator.next()
                task.subscribers.removeAll {
                    it.owner == owner && it.generation == generation &&
                        task.key !in keepKeys
                }
                if (task.subscribers.isEmpty()) {
                    task.cancelled = true
                    if (!task.running) {
                        queue.remove(task)
                        iterator.remove()
                    }
                }
            }
        }
    }

    /** 当前页切换时提升它已排队的任务，不需要取消再重新提交。 */
    fun reprioritize(owner: Long, generation: Int, basePriority: Int) {
        synchronized(lock) {
            tasks.values.forEach { task ->
                if (task.running || task.subscribers.none {
                    it.owner == owner && it.generation == generation
                }) return@forEach
                val prefetchOffset = task.priority % 2
                val next = basePriority + prefetchOffset
                if (next != task.priority) {
                    queue.remove(task)
                    task.priority = next
                    task.sequence = sequence.incrementAndGet()
                    queue.offer(task)
                }
            }
        }
    }

    fun cancelOwner(owner: Long) {
        synchronized(lock) {
            val iterator = tasks.values.iterator()
            while (iterator.hasNext()) {
                val task = iterator.next()
                task.subscribers.removeAll { it.owner == owner }
                if (task.subscribers.isEmpty()) {
                    task.cancelled = true
                    if (!task.running) {
                        queue.remove(task)
                        iterator.remove()
                    }
                }
            }
        }
    }

    fun cancelAll() {
        synchronized(lock) {
            tasks.values.forEach { task ->
                task.cancelled = true
                task.subscribers.clear()
            }
            queue.clear()
            tasks.entries.removeAll { !it.value.running }
        }
    }

    fun cancelRepository(cacheId: String) {
        val prefix = "tile:$cacheId:"
        val previewPrefix = "tile-preview:$cacheId:"
        synchronized(lock) {
            val iterator = tasks.values.iterator()
            while (iterator.hasNext()) {
                val task = iterator.next()
                if (!task.key.startsWith(prefix) &&
                    !task.key.startsWith(previewPrefix)
                ) continue
                task.cancelled = true
                if (!task.running) {
                    queue.remove(task)
                    iterator.remove()
                } else {
                    task.subscribers.clear()
                }
            }
        }
    }

    private fun scheduleWorkerLocked() {
        if (workerScheduled) return
        workerScheduled = true
        executor.execute(::drain)
    }

    private fun drain() {
        while (true) {
            val task = synchronized(lock) {
                var next: Task? = null
                while (queue.isNotEmpty()) {
                    val candidate = queue.poll()
                    if (tasks[candidate.key] !== candidate ||
                        candidate.cancelled || candidate.subscribers.isEmpty()
                    ) {
                        if (tasks[candidate.key] === candidate) tasks.remove(candidate.key)
                        continue
                    }
                    candidate.running = true
                    next = candidate
                    break
                }
                if (next == null) workerScheduled = false
                next
            } ?: return

            var bitmap: Bitmap? = null
            var success = false
            try {
                bitmap = task.render()
                success = bitmap != null && !bitmap!!.isRecycled
            } catch (_: OutOfMemoryError) {
                PdfBitmapCache.trim(80)
                success = false
            } catch (_: Exception) {
                success = false
            }

            val subscribers = synchronized(lock) {
                if (tasks[task.key] === task) tasks.remove(task.key)
                if (task.cancelled || task.subscribers.isEmpty()) emptyList()
                else task.subscribers.toList()
            }

            if (success && subscribers.isNotEmpty()) {
                PdfBitmapCache.put(task.key, bitmap!!)
                bitmap = null
            } else {
                success = false
            }
            bitmap?.let { if (!it.isRecycled) it.recycle() }

            if (subscribers.isNotEmpty()) {
                val completed = success
                main.post {
                    subscribers.forEach { subscriber ->
                        subscriber.callback(completed)
                    }
                }
            }
        }
    }

    private const val MAX_QUEUED = 48
}
