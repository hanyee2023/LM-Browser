package com.proxybrowser.app.ui

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.proxybrowser.app.R
import com.proxybrowser.app.data.NodeParser
import com.proxybrowser.app.data.NodeStore
import com.proxybrowser.app.data.ProxyNode
import java.net.HttpURLConnection

class NodesActivity : AppCompatActivity() {

    private lateinit var rv: RecyclerView
    private lateinit var adapter: NodeAdapter
    private val nodes = mutableListOf<ProxyNode>()
    private var sortByLatency = true

    private lateinit var tvActiveName: TextView
    private lateinit var tvActiveSub: TextView
    private lateinit var statusDot: View
    private lateinit var emptyView: View
    private lateinit var loading: ProgressBar
    private lateinit var btnOpen: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nodes)

        rv = findViewById(R.id.list)
        tvActiveName = findViewById(R.id.activeNodeName)
        tvActiveSub = findViewById(R.id.activeNodeSub)
        statusDot = findViewById(R.id.statusDot)
        emptyView = findViewById(R.id.empty)
        loading = findViewById(R.id.loading)
        btnOpen = findViewById(R.id.btnOpen)

        loadNodes()
        adapter = NodeAdapter(this, nodes,
            onUse = { useNode(it) },
            onDel = { deleteNode(it) },
            onTest = { i -> testSingle(i) }
        )
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter

        findViewById<Button>(R.id.btnAdd).setOnClickListener { addSingle() }
        findViewById<Button>(R.id.btnSub).setOnClickListener { importSubscription() }
        findViewById<Button>(R.id.btnTest).setOnClickListener { testAll() }
        findViewById<Button>(R.id.btnSort).setOnClickListener { toggleSort() }
        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.btnChangeNode).setOnClickListener {
            rv.smoothScrollToPosition(0)
        }
        btnOpen.setOnClickListener {
            val active = NodeStore.getActive(this)
            if (active == null) {
                Toast.makeText(this, "Please select a node first", Toast.LENGTH_SHORT).show()
            } else {
                startActivity(Intent(this, BrowserActivity::class.java))
            }
        }
        refreshActiveBar()
        refreshEmpty()
    }

    override fun onResume() {
        super.onResume()
        loadNodes()
        refreshActiveBar()
        adapter.notifyDataSetChanged()
        refreshEmpty()
    }

    private fun loadNodes() {
        nodes.clear()
        nodes.addAll(NodeStore.load(this))
        applySort()
    }

    private fun applySort() {
        if (sortByLatency) {
            nodes.sortBy { if (it.latencyMs > 0) it.latencyMs else Long.MAX_VALUE }
        }
    }

    private fun toggleSort() {
        sortByLatency = !sortByLatency
        applySort()
        adapter.notifyDataSetChanged()
        Toast.makeText(this, if (sortByLatency) "Sorted by latency (low to high)" else "Sorted by manual order", Toast.LENGTH_SHORT).show()
    }

    private fun refreshActiveBar() {
        val active = NodeStore.getActive(this)
        if (active == null) {
            tvActiveName.text = "No node selected"
            tvActiveSub.text = "Tap Add or Subscribe to import a proxy node"
            statusDot.setBackgroundResource(R.drawable.dot_off)
            btnOpen.isEnabled = false
            btnOpen.alpha = 0.5f
        } else {
            tvActiveName.text = active.name
            tvActiveSub.text = "${active.type.name}  ${active.address}:${active.port}"
            statusDot.setBackgroundResource(R.drawable.dot_on)
            btnOpen.isEnabled = true
            btnOpen.alpha = 1f
        }
    }

    private fun refreshEmpty() {
        if (nodes.isEmpty()) {
            emptyView.visibility = View.VISIBLE
            rv.visibility = View.GONE
        } else {
            emptyView.visibility = View.GONE
            rv.visibility = View.VISIBLE
        }
    }

    private fun addSingle() {
        val et = EditText(this).apply {
            hint = "vless:// / vmess:// / trojan://"
            minHeight = (60 * resources.displayMetrics.density).toInt()
            setSingleLine(false)
            setPadding(24, 16, 24, 16)
            setTextColor(getColor(R.color.text_primary))
        }
        AlertDialog.Builder(this)
            .setTitle("Add Node")
            .setView(et)
            .setPositiveButton("Add") { _, _ ->
                val raw = et.text.toString().trim()
                if (raw.isEmpty()) return@setPositiveButton
                val n = NodeParser.parseSingle(raw)
                if (n == null) {
                    Toast.makeText(this, "Cannot parse node (check format)", Toast.LENGTH_SHORT).show()
                } else {
                    nodes.add(n)
                    NodeStore.save(this, nodes)
                    adapter.notifyItemInserted(nodes.size - 1)
                    refreshEmpty()
                    Toast.makeText(this, "Added: ${n.name}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun importSubscription() {
        val et = EditText(this).apply {
            hint = "https://...  or  sub://..."
            minHeight = (60 * resources.displayMetrics.density).toInt()
            setSingleLine(false)
            setPadding(24, 16, 24, 16)
            setTextColor(getColor(R.color.text_primary))
        }
        AlertDialog.Builder(this)
            .setTitle("Import Subscription")
            .setView(et)
            .setPositiveButton("Import") { _, _ ->
                val raw = et.text.toString().trim()
                if (raw.isEmpty()) return@setPositiveButton
                doImport(raw)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doImport(raw: String) {
        loading.visibility = View.VISIBLE
        Thread {
            var err: String? = null
            var parsed = emptyList<ProxyNode>()
            try {
                val target = normalizeSub(raw)
                val body = fetchWithTimeout(target, 5000, 8000)
                if (body.isEmpty()) {
                    err = "Subscription content is empty"
                } else {
                    parsed = NodeParser.parse(body)
                    if (parsed.isEmpty()) err = "No nodes parsed (unsupported format?)"
                }
            } catch (e: java.net.SocketTimeoutException) {
                err = "Subscription timeout (5s)"
            } catch (e: Exception) {
                err = "Import failed: ${e.javaClass.simpleName}"
            }
            runOnUiThread {
                loading.visibility = View.GONE
                if (err != null) {
                    Toast.makeText(this, err, Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                val before = nodes.size
                val merged = (nodes + parsed).distinctBy { "${it.name}|${it.address}:${it.port}" }
                nodes.clear()
                nodes.addAll(merged)
                NodeStore.save(this, nodes)
                adapter.notifyDataSetChanged()
                refreshEmpty()
                Toast.makeText(this, "Imported ${parsed.size} nodes (+${nodes.size - before} new)", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun normalizeSub(raw: String): String {
        return when {
            raw.startsWith("sub://") -> {
                val body = raw.removePrefix("sub://")
                val host = runCatching {
                    String(android.util.Base64.decode(body, android.util.Base64.DEFAULT))
                }.getOrNull() ?: body
                if (host.startsWith("http")) host else "https://$host"
            }
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            else -> "https://$raw"
        }
    }

    private fun fetchWithTimeout(url: String, connectMs: Int, readMs: Int): String {
        var conn: HttpURLConnection? = null
        return try {
            conn = (Uri.parse(url).toString().let { java.net.URL(it) }.openConnection() as HttpURLConnection).apply {
                connectTimeout = connectMs
                readTimeout = readMs
                requestMethod = "GET"
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13; ProxyBrowser)")
                setRequestProperty("Accept", "*/*")
            }
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun testAll() {
        if (nodes.isEmpty()) {
            Toast.makeText(this, "Please add nodes first", Toast.LENGTH_SHORT).show()
            return
        }
        loading.visibility = View.VISIBLE
        statusDot.setBackgroundResource(R.drawable.dot_busy)
        // Reset all latencies
        nodes.forEach { it.latencyMs = -1L }
        adapter.notifyDataSetChanged()

        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        val total = nodes.size
        val doneCount = intArrayOf(0)
        // Use synchronized list access
        val sortedIndices = nodes.indices.toList()

        for (idx in sortedIndices) {
            val node = nodes[idx]
            pool.execute {
                val ms = testOne(node)
                node.latencyMs = ms
                runOnUiThread {
                    adapter.notifyItemChanged(idx)
                    doneCount[0]++
                    if (doneCount[0] >= total) {
                        loading.visibility = View.GONE
                        NodeStore.save(this, nodes)
                        statusDot.setBackgroundResource(R.drawable.dot_on)
                        val validCount = nodes.count { it.latencyMs > 0 }
                        Toast.makeText(this, "Test done: $validCount/$total valid", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        pool.shutdown()
    }

    private fun testSingle(index: Int) {
        if (index < 0 || index >= nodes.size) return
        loading.visibility = View.VISIBLE
        val node = nodes[index]
        Thread {
            val ms = testOne(node)
            node.latencyMs = ms
            runOnUiThread {
                loading.visibility = View.GONE
                adapter.notifyItemChanged(index)
                NodeStore.save(this, nodes)
                Toast.makeText(this, "${node.name}: ${if (ms > 0) "$ms ms" else "unreachable"}", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    private fun testOne(node: ProxyNode): Long {
        val direct = directPing(node.address, node.port, 3000)
        if (direct < 0) return -1L
        return direct
    }

    private fun directPing(host: String, port: Int, timeoutMs: Int): Long {
        return try {
            val t0 = System.currentTimeMillis()
            java.net.Socket().use { s ->
                s.connect(java.net.InetSocketAddress(host, port), timeoutMs)
                s.close()
            }
            System.currentTimeMillis() - t0
        } catch (_: Exception) { -1L }
    }

    private fun useNode(n: ProxyNode) {
        NodeStore.setActive(this, n)
        Toast.makeText(this, "Switched to: ${n.name}", Toast.LENGTH_SHORT).show()
        refreshActiveBar()
        adapter.notifyDataSetChanged()
        startActivity(Intent(this, BrowserActivity::class.java))
    }

    private fun deleteNode(n: ProxyNode) {
        val idx = nodes.indexOf(n)
        if (idx >= 0) {
            nodes.removeAt(idx)
            NodeStore.save(this, nodes)
            adapter.notifyItemRemoved(idx)
            val active = NodeStore.getActive(this)
            if (active == n) {
                NodeStore.setActive(this, null)
            }
            refreshActiveBar()
            refreshEmpty()
        }
    }
}

private class NodeAdapter(
    private val activity: NodesActivity,
    private val list: MutableList<ProxyNode>,
    private val onUse: (ProxyNode) -> Unit,
    private val onDel: (ProxyNode) -> Unit,
    private val onTest: (Int) -> Unit
) : RecyclerView.Adapter<NodeAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.icon)
        val name: TextView = view.findViewById(R.id.tvName)
        val detail: TextView = view.findViewById(R.id.tvDetail)
        val latency: TextView = view.findViewById(R.id.tvLatency)
        val use: Button = view.findViewById(R.id.btnUse)
        val del: Button = view.findViewById(R.id.btnDel)
        val test: Button = view.findViewById(R.id.btnTest)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_node, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, pos: Int) {
        val n = list[pos]
        h.name.text = n.name
        h.detail.text = "${n.type.name.lowercase()}  ${n.address}:${n.port}"
        h.latency.text = when {
            n.latencyMs > 0 -> "${n.latencyMs} ms"
            else -> "Not tested"
        }
        h.latency.setTextColor(
            when {
                n.latencyMs <= 0 -> Color.parseColor("#9ca3af")
                n.latencyMs < 300 -> Color.parseColor("#10B981")
                n.latencyMs < 800 -> Color.parseColor("#F59E0B")
                else -> Color.parseColor("#EF4444")
            }
        )
        val active = NodeStore.getActive(activity)
        h.icon.setImageResource(if (active == n) R.drawable.ic_node_active else R.drawable.ic_node_idle)
        h.use.text = if (active == n) "Active" else "Use"
        h.use.isEnabled = active != n
        h.use.setOnClickListener { onUse(n) }
        h.del.setOnClickListener { onDel(n) }
        h.test.setOnClickListener { onTest(pos) }
        h.itemView.setOnClickListener { onUse(n) }
    }

    override fun getItemCount(): Int = list.size
}
