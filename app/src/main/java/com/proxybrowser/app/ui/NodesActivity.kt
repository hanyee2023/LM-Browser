package com.proxybrowser.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.proxybrowser.app.core.V2RayManager
import com.proxybrowser.app.data.NodeParser
import com.proxybrowser.app.data.NodeStore
import com.proxybrowser.app.data.ProxyNode
import com.proxybrowser.app.databinding.ActivityNodesBinding
import com.proxybrowser.app.R
import java.net.URL

/**
 * P5：节点管理页。
 * - 添加单节点（vless/vmess/trojan URI）
 * - 导入订阅（sub:// 或 https 订阅链接）
 * - 一键测速（显示延迟/有效性）
 * - 选用节点 -> 打开浏览器
 */
class NodesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNodesBinding
    private val nodes = mutableListOf<ProxyNode>()
    private lateinit var adapter: NodeAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNodesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        nodes.addAll(NodeStore.load(this))
        adapter = NodeAdapter(nodes, ::useNode, ::deleteNode)
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        binding.btnAdd.setOnClickListener { addSingle() }
        binding.btnSub.setOnClickListener { importSubscription() }
        binding.btnTest.setOnClickListener { testAll() }
    }

    private fun addSingle() {
        val et = android.widget.EditText(this).apply { hint = "vless:// / vmess:// / trojan://" }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("添加节点")
            .setView(et)
            .setPositiveButton("添加") { _, _ ->
                val raw = et.text.toString().trim()
                val n = if (raw.startsWith("http")) null else NodeParser.parseSingle(raw)
                if (n == null) {
                    Toast.makeText(this, "无法解析该节点", Toast.LENGTH_SHORT).show()
                } else {
                    nodes.add(n)
                    NodeStore.save(this, nodes)
                    adapter.notifyItemInserted(nodes.size - 1)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun importSubscription() {
        val et = android.widget.EditText(this).apply { hint = "订阅链接 sub:// 或 https://..." }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("导入订阅")
            .setView(et)
            .setPositiveButton("导入") { _, _ ->
                val raw = et.text.toString().trim()
                if (raw.isEmpty()) return@setPositiveButton
                Thread {
                    try {
                        val content = if (raw.startsWith("http")) URL(raw).readText() else raw
                        val parsed = NodeParser.parse(content)
                        runOnUiThread {
                            if (parsed.isEmpty()) {
                                Toast.makeText(this, "未解析到节点", Toast.LENGTH_SHORT).show()
                            } else {
                                nodes.addAll(parsed)
                                NodeStore.save(this, nodes)
                                adapter.notifyDataSetChanged()
                                Toast.makeText(this, "导入 ${parsed.size} 个节点", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            Toast.makeText(this, "订阅获取失败：${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }.start()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun testAll() {
        if (nodes.isEmpty()) return
        Toast.makeText(this, "测速中…", Toast.LENGTH_SHORT).show()
        val pending = nodes.size
        var done = 0
        nodes.forEachIndexed { i, n ->
            V2RayManager.measure(this, n) { d ->
                n.latencyMs = d ?: -1L
                n.valid = d != null
                adapter.notifyItemChanged(i)
                done++
                if (done == pending) {
                    NodeStore.save(this, nodes)
                    Toast.makeText(this, "测速完成", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun useNode(n: ProxyNode) {
        NodeStore.setActive(this, n)
        V2RayManager.start(this, n)
        startActivity(Intent(this, BrowserActivity::class.java))
    }

    private fun deleteNode(n: ProxyNode) {
        val idx = nodes.indexOf(n)
        if (idx >= 0) {
            nodes.removeAt(idx)
            NodeStore.save(this, nodes)
            adapter.notifyItemRemoved(idx)
        }
    }
}

class NodeAdapter(
    private val list: MutableList<ProxyNode>,
    private val onUse: (ProxyNode) -> Unit,
    private val onDel: (ProxyNode) -> Unit
) : RecyclerView.Adapter<NodeAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.tvName)
        val detail: TextView = view.findViewById(R.id.tvDetail)
        val latency: TextView = view.findViewById(R.id.tvLatency)
        val use: android.widget.Button = view.findViewById(R.id.btnUse)
        val del: android.widget.Button = view.findViewById(R.id.btnDel)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_node, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val n = list[pos]
        h.name.text = n.name
        h.detail.text = "${n.type.name.lowercase()}  ${n.address}:${n.port}"
        h.latency.text = if (n.latencyMs >= 0) "延迟 ${n.latencyMs} ms" else "未测速"
        h.use.setOnClickListener { onUse(n) }
        h.del.setOnClickListener { onDel(n) }
    }

    override fun getItemCount() = list.size
}