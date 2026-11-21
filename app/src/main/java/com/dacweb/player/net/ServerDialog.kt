package com.dacweb.player.net

import androidx.activity.ComponentActivity
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.dacweb.player.R
import com.dacweb.player.ui.focusButtons
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 「连接文件服务器」对话框：协议 + 地址 + 端口 + 账号 + 起始路径。 */
object ServerDialog {

    private val TYPES = listOf("smb", "ftp", "sftp", "dav")
    private val LABELS = listOf("SMB / SMB2 / SMB3", "FTP", "SFTP", "WebDAV")
    private val PORTS = listOf(445, 21, 22, 80)

    /**
     * @param presetHost 自动搜索选中（或上次连接）的地址，会预填到地址框
     * @param presetType 预设协议；null = 跟随上次
     */
    fun show(
        activity: ComponentActivity,
        presetHost: String? = null,
        presetType: String? = null,
        onConnected: (NetServer) -> Unit
    ) {
        val ctx = activity
        val last = NetStore.load(ctx)
        val dp = ctx.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(8), px(20), 0)
        }

        val spinner = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, LABELS)
            setSelection(TYPES.indexOf(presetType ?: last?.type ?: "smb").coerceAtLeast(0))
        }
        root.addView(spinner)

        fun field(hint: String, value: String, input: Int = InputType.TYPE_CLASS_TEXT): EditText =
            EditText(ctx).apply {
                this.hint = hint
                setText(value)
                inputType = input
                setSingleLine(true)
            }

        val host = field(ctx.getString(R.string.net_host), presetHost ?: last?.host ?: "")
        val port = field(ctx.getString(R.string.net_port), "", InputType.TYPE_CLASS_NUMBER).apply {
            setText(if (last != null && last.port > 0) last.port.toString() else PORTS[0].toString())
            spinner.onItemSelectedListener = null
        }
        val user = field(ctx.getString(R.string.net_user), last?.user ?: "")
        val pass = field(
            ctx.getString(R.string.net_pass), last?.pass ?: "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        )
        val path = field(ctx.getString(R.string.net_path), last?.startPath ?: "")

        spinner.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                // 只在用户还没改端口时跟随协议切默认端口
                val cur = port.text.toString().trim()
                if (cur.isEmpty() || cur in listOf("445", "21", "22", "80")) {
                    port.setText(PORTS[position].toString())
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        })

        listOf(host, port, user, pass, path).forEach {
            it.setPadding(0, px(10), 0, 0)
            root.addView(it)
        }

        var dialog: androidx.appcompat.app.AlertDialog? = null
        val builder = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.net_connect)
            .setView(android.widget.ScrollView(ctx).apply { addView(root) })
            .setNegativeButton(R.string.net_cancel, null)
            .setPositiveButton(R.string.net_ok, null)

        val d = builder.create()
        dialog = d
        d.setOnShowListener {
            d.focusButtons(lastField = path)
            d.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val h = host.text.toString().trim()
                if (h.isEmpty()) {
                    Toast.makeText(ctx, R.string.net_host_required, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val type = TYPES[spinner.selectedItemPosition]
                val s = NetServer(
                    type = type,
                    host = h,
                    port = port.text.toString().trim().toIntOrNull() ?: PORTS[spinner.selectedItemPosition],
                    user = user.text.toString(),
                    pass = pass.text.toString(),
                    startPath = path.text.toString().trim()
                )
                d.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).isEnabled = false
                ctx.lifecycleScope.launch {
                    val client = withContext(Dispatchers.IO) {
                        runCatching {
                            val c = NetStore.connect(ctx, s)
                            // 立刻试列一次目录：路径写错/共享不存在/认证失败都在这里暴露
                            c.list(normalizePath(s.startPath))
                            c
                        }
                    }
                    d.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).isEnabled = true
                    client.onSuccess {
                        d.dismiss()
                        onConnected(s)
                    }.onFailure {
                        Toast.makeText(
                            ctx,
                            ctx.getString(R.string.net_failed, it.message ?: it.javaClass.simpleName),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
        d.show()
    }
}
