package com.moye.pdfink

import android.graphics.Bitmap
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OverviewAdapter(
    private val repo: PdfRepo,
    private val store: InkStore,
    private val bookmarks: Set<Int>,
    private val scope: CoroutineScope,
    private val onClick: (Int) -> Unit,
    private val onSelectionChanged: (Int) -> Unit
) : RecyclerView.Adapter<OverviewAdapter.VH>() {

    private val cache = object : LruCache<Int, Bitmap>(6 * 1024) {
        override fun sizeOf(key: Int, value: Bitmap) =
            (value.byteCount / 1024).coerceAtLeast(1)
    }

    val selected = HashSet<Int>()
    var selectionMode = false
        set(v) {
            field = v
            if (!v) selected.clear()
            notifyDataSetChanged()
            onSelectionChanged(selected.size)
        }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val root: View = v.findViewById(R.id.thumbRoot)
        val image: ImageView = v.findViewById(R.id.thumbImage)
        val inkFlag: View = v.findViewById(R.id.inkFlag)
        val bmFlag: View = v.findViewById(R.id.bookmarkFlag)
        val check: View = v.findViewById(R.id.selFlag)
        val page: TextView = v.findViewById(R.id.tvThumbPage)
        var token = 0
    }

    override fun getItemCount() = repo.count

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_thumb, parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        h.page.text = (position + 1).toString()
        h.inkFlag.visibility =
            if (store.hasInk("p$position") || store.hasInk("d$position")) View.VISIBLE
            else View.GONE
        h.bmFlag.visibility = if (bookmarks.contains(position)) View.VISIBLE else View.GONE
        h.check.visibility = if (selectionMode && selected.contains(position)) View.VISIBLE
        else View.GONE
        h.root.alpha = if (selectionMode && !selected.contains(position)) 0.55f else 1f

        h.itemView.setOnClickListener {
            if (selectionMode) {
                if (selected.contains(position)) selected.remove(position) else selected.add(position)
                notifyItemChanged(position)
                onSelectionChanged(selected.size)
            } else onClick(position)
        }
        h.itemView.setOnLongClickListener {
            if (!selectionMode) selectionMode = true
            selected.add(position)
            notifyItemChanged(position)
            onSelectionChanged(selected.size)
            true
        }

        h.image.setImageBitmap(null)
        cache.get(position)?.let { h.image.setImageBitmap(it); return }
        val token = ++h.token
        h.itemView.post {
            if (h.token != token || h.bindingAdapterPosition != position) return@post
            scope.launch(Dispatchers.IO) {
                val b = try { repo.thumb(position, 180) } catch (e: Exception) { null }
                if (b != null) withContext(Dispatchers.Main) {
                    cache.put(position, b)
                    if (h.token == token && h.bindingAdapterPosition == position) {
                        h.image.setImageBitmap(b)
                    }
                }
            }
        }
    }

    fun selectAll() {
        for (p in 0 until repo.count) selected.add(p)
        notifyDataSetChanged()
        onSelectionChanged(selected.size)
    }

    override fun onViewRecycled(h: VH) {
        h.token++
        h.image.setImageBitmap(null)
        super.onViewRecycled(h)
    }
}
