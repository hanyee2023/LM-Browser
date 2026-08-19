package com.proxybrowser.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.proxybrowser.app.R
import com.proxybrowser.app.core.AdBlocker
import com.proxybrowser.app.core.Settings
import com.proxybrowser.app.core.UserScriptEngine

/**
 * 设置页：开关 + 用户脚本编辑入口 + 自定义 UA。
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<Button>(R.id.btnBack).setOnClickListener { finish() }

        // 广告拦截
        val swAdblock = findViewById<Button>(R.id.swAdblock)
        val renderAd = { enabled: Boolean ->
            swAdblock.text = if (enabled) "● 已启用" else "○ 已关闭"
            swAdblock.setBackgroundResource(if (enabled) R.drawable.bg_btn_primary else R.drawable.bg_btn_secondary)
        }
        renderAd(Settings.isAdblock(this))
        swAdblock.setOnClickListener {
            val next = !Settings.isAdblock(this)
            Settings.setAdblock(this, next)
            AdBlocker.setEnabled(next)
            renderAd(next)
        }

        // 油猴
        val swScript = findViewById<Button>(R.id.swScript)
        val renderScript = { enabled: Boolean ->
            swScript.text = if (enabled) "● 已启用" else "○ 已关闭"
            swScript.setBackgroundResource(if (enabled) R.drawable.bg_btn_primary else R.drawable.bg_btn_secondary)
        }
        renderScript(Settings.isUserScript(this))
        swScript.setOnClickListener {
            val next = !Settings.isUserScript(this)
            Settings.setUserScript(this, next)
            renderScript(next)
        }

        // 嗅探
        val swSniffer = findViewById<Button>(R.id.swSniffer)
        val renderSniffer = { enabled: Boolean ->
            swSniffer.text = if (enabled) "● 已启用" else "○ 已关闭"
            swSniffer.setBackgroundResource(if (enabled) R.drawable.bg_btn_primary else R.drawable.bg_btn_secondary)
        }
        renderSniffer(Settings.isSniffer(this))
        swSniffer.setOnClickListener {
            val next = !Settings.isSniffer(this)
            Settings.setSniffer(this, next)
            renderSniffer(next)
        }

        // DoNotTrack
        val swDnt = findViewById<Button>(R.id.swDnt)
        val renderDnt = { enabled: Boolean ->
            swDnt.text = if (enabled) "● 已启用" else "○ 已关闭"
            swDnt.setBackgroundResource(if (enabled) R.drawable.bg_btn_primary else R.drawable.bg_btn_secondary)
        }
        renderDnt(Settings.isDnt(this))
        swDnt.setOnClickListener {
            val next = !Settings.isDnt(this)
            Settings.setDnt(this, next)
            renderDnt(next)
        }

        // 管理用户脚本
        findViewById<Button>(R.id.btnScripts).setOnClickListener { showScripts() }

        // 自定义 UA
        findViewById<Button>(R.id.btnUa).setOnClickListener {
            val et = EditText(this).apply {
                setText(Settings.userAgent(this@SettingsActivity))
                hint = "留空 = 默认 UA"
                minHeight = (60 * resources.displayMetrics.density).toInt()
            }
            AlertDialog.Builder(this)
                .setTitle("自定义 User-Agent")
                .setView(et)
                .setPositiveButton("保存") { _, _ ->
                    Settings.setUserAgent(this@SettingsActivity, et.text.toString().trim())
                    Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    /**
     * 用户脚本管理（极简）：显示已注册脚本，可编辑 / 启用 / 删除 / 添加。
     * 编辑器就是多行输入框，写完保存。
     */
    private fun showScripts() {
        val list = UserScriptEngine.loadAll(this)
        val titles = mutableListOf<String>()
        titles.add("+ 新增脚本")
        list.forEach { titles.add(if (it.enabled) "● ${it.name}" else "○ ${it.name}") }
        AlertDialog.Builder(this)
            .setTitle("用户脚本")
            .setItems(titles.toTypedArray()) { _, which ->
                if (which == 0) {
                    editScript(null)
                } else {
                    val s = list[which - 1]
                    AlertDialog.Builder(this)
                        .setTitle(s.name)
                        .setItems(arrayOf("编辑", if (s.enabled) "禁用" else "启用", "删除"))
                        { _, idx ->
                            when (idx) {
                                0 -> editScript(s)
                                1 -> {
                                    UserScriptEngine.setEnabled(this, s.name, !s.enabled)
                                    Toast.makeText(this, "已更新", Toast.LENGTH_SHORT).show()
                                }
                                2 -> {
                                    UserScriptEngine.remove(this, s.name)
                                    Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        .setNegativeButton("关闭", null)
                        .show()
                }
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun editScript(s: UserScriptEngine.Script?) {
        val nameView = EditText(this).apply {
            hint = "脚本名（如 解除B站限制）"
            setText(s?.name ?: "")
        }
        val codeView = EditText(this).apply {
            hint = "// ==UserScript== ... code ... // ==/UserScript==\n// @match https://*.example.com/*"
            setText(s?.raw ?: "/* 在此粘贴 .user.js 完整内容（含 // ==UserScript== 头） */")
            minLines = 12
            maxLines = 24
            setSingleLine(false)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
            addView(nameView)
            addView(codeView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 24
            })
        }
        AlertDialog.Builder(this)
            .setTitle(if (s == null) "新增脚本" else "编辑：${s.name}")
            .setView(container)
            .setPositiveButton("保存") { _, _ ->
                val name = nameView.text.toString().trim()
                val code = codeView.text.toString()
                if (name.isEmpty() || code.isBlank()) {
                    Toast.makeText(this, "名字 + 代码不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                UserScriptEngine.addOrReplace(this, name, code, true)
                Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
