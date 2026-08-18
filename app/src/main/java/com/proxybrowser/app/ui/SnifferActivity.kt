package com.proxybrowser.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.proxybrowser.app.R
import com.proxybrowser.app.core.VideoSniffer

/**
 * P4：嗅探到的视频列表页。
 *   - 长按复制链接
 *   - 跳到系统下载（如果用户装了第三方下载器）
 *   - 单条可删除
 */
class SnifferActivity : AppCompatActivity() {

    private lateinit var rv: RecyclerView
    private lateinit var emptyView: View
    private val items = mutableListOf<VideoSniffer.Media>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sniffer)
        rv = findViewById(R.id.list)
        emptyView = findViewById(R.id.empty)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = MediaAdapter()
        findViewById<Button>(R.id.btnClear).setOnClickListener {
            VideoSniffer.clear()
            items.clear()
            refresh()
        }
        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }
    }

    override fun onResume() {
        super.onResume()
        items.clear()
        items.addAll(VideoSniffer.all())
        refresh()
    }

    private fun refresh() {
        if (items.isEmpty()) {
            emptyView.visibility = View.VISIBLE
            rv.visibility = View.GONE
        } else {
            emptyView.visibility = View.GONE
            rv.visibility = View.VISIBLE
        }
        rv.adapter?.notifyDataSetChanged()
    }

    private inner class MediaAdapter : RecyclerView.Adapter<MediaAdapter.VH>() {
        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val ext: TextView = view.findViewById(R.id.tvExt)
            val url: TextView = view.findViewById(R.id.tvUrl)
            val page: TextView = view.findViewById(R.id.tvPage)
            val action: Button = view.findViewById(R.id.btnAction)
            val del: Button = view.findViewById(R.id.btnDel)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_media, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(h: VH, pos: Int) {
            val m = items[pos]
            h.ext.text = m.ext.uppercase().ifEmpty { "?" }
            h.url.text = m.url
            h.page.text = m.title.ifEmpty { m.pageUrl }
            h.action.text = "下载"
            h.action.setOnClickListener {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(m.url))
                    startActivity(intent)
                } catch (_: Exception) {
                    copy(m.url)
                    Toast.makeText(this@SnifferActivity, "未找到下载器，链接已复制", Toast.LENGTH_SHORT).show()
                }
            }
            h.itemView.setOnLongClickListener { copy(m.url); true }
            h.del.setOnClickListener {
                items.removeAt(pos)
                // 同时清掉 VideoSniffer 内的项
                val all = VideoSniffer.all().toMutableList()
                all.removeAll { it.url == m.url }
                // 直接重写 store（这里简化）
                VideoSniffer.clear()
                all.forEach { VideoSniffer.add(this@SnifferActivity, it) }
                refresh()
            }
        }

        override fun getItemCount(): Int = items.size
    }

    private fun copy(s: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("video", s))
        Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
    }
}
