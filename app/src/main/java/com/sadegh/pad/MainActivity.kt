package com.sadegh.pad

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.*
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Build
import android.os.Bundle
import android.view.*
import java.util.concurrent.Executors
import java.net.*
import android.widget.EditText
import android.os.Handler
import android.os.Looper
import kotlin.math.hypot

// 16 buttons, hat, X Y Z Rz (sticks), Rx Ry (triggers) -> 9 bytes, report id 1
private val DESC = intArrayOf(
    0x05,0x01,0x09,0x05,0xA1,0x01,0x85,0x01,
    0x05,0x09,0x19,0x01,0x29,0x10,0x15,0x00,0x25,0x01,0x75,0x01,0x95,0x10,0x81,0x02,
    0x05,0x01,0x09,0x39,0x15,0x00,0x25,0x07,0x75,0x04,0x95,0x01,0x81,0x42,
    0x75,0x04,0x95,0x01,0x81,0x03,
    0x05,0x01,0x09,0x30,0x09,0x31,0x09,0x32,0x09,0x35,0x15,0x00,0x26,0xFF,0x00,0x75,0x08,0x95,0x04,0x81,0x02,
    0x05,0x01,0x09,0x33,0x09,0x34,0x15,0x00,0x26,0xFF,0x00,0x75,0x08,0x95,0x02,0x81,0x02,
    0xC0
).map { it.toByte() }.toByteArray()

class MainActivity : Activity() {
    private lateinit var pad: PadView
    private var hid: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    private var mode = 0 // 1 = Bluetooth, 2 = cable/Wi-Fi (UDP to PC server)
    private var sock: DatagramSocket? = null
    private var addr: InetAddress? = null
    private val net = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { if (mode == 2 && pad.last.isNotEmpty()) pad.out(pad.last); ui.postDelayed(this, 100) }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        pad = PadView(this)
        pad.out = { r ->
            if (mode == 1) { val h = hid; val d = host; if (h != null && d != null) h.sendReport(d, 1, r) }
            else if (mode == 2) net.execute { try { sock?.send(DatagramPacket(r, r.size, addr, 9000)) } catch (e: Exception) {} }
        }
        pad.onMenu = { menu() }
        setContentView(pad)
        ui.post(tick)
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
        else start()
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        if (r.isNotEmpty() && r[0] == PackageManager.PERMISSION_GRANTED) start()
    }

    override fun onWindowFocusChanged(f: Boolean) {
        if (f) window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    private fun start() {
        val ad = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        ad.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                val h = proxy as BluetoothHidDevice
                hid = h
                val sdp = BluetoothHidDeviceAppSdpSettings("Sadegh Pad", "Phone gamepad", "Sadegh",
                    BluetoothHidDevice.SUBCLASS1_COMBO, DESC)
                h.registerApp(sdp, null, null, Executors.newSingleThreadExecutor(),
                    object : BluetoothHidDevice.Callback() {
                        override fun onConnectionStateChanged(d: BluetoothDevice, s: Int) {
                            if (s == BluetoothProfile.STATE_CONNECTED) { host = d; say("Connected: " + d.name) }
                            else if (s == BluetoothProfile.STATE_DISCONNECTED) { host = null; say("Disconnected - tap MENU") }
                        }
                    })
                say("Ready - pair from PC, then tap MENU")
            }
            override fun onServiceDisconnected(p: Int) { hid = null }
        }, BluetoothProfile.HID_DEVICE)
    }

    private fun say(s: String) = runOnUiThread { pad.status = s; pad.invalidate() }

    private fun menu() = runOnUiThread {
        AlertDialog.Builder(this).setTitle("Connection")
            .setItems(arrayOf("Bluetooth", "Cable / Wi-Fi (PC server)")) { _, i -> if (i == 0) pickBt() else askIp() }
            .show()
    }

    private fun askIp() {
        val prefs = getSharedPreferences("pad", Context.MODE_PRIVATE)
        val et = EditText(this); et.setText(prefs.getString("ip", "192.168.42."))
        AlertDialog.Builder(this).setTitle("PC IP address").setView(et)
            .setPositiveButton("Connect") { _, _ ->
                val ip = et.text.toString().trim()
                prefs.edit().putString("ip", ip).apply()
                net.execute {
                    try {
                        sock?.close(); addr = InetAddress.getByName(ip); sock = DatagramSocket()
                        mode = 2; say("Cable/Wi-Fi mode: $ip")
                    } catch (e: Exception) { say("Bad IP") }
                }
            }.show()
    }

    private fun pickBt() = runOnUiThread {
        mode = 1
        val ad = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val list = ad.bondedDevices.toList()
        AlertDialog.Builder(this).setTitle("Connect to PC")
            .setItems(list.map { it.name ?: it.address }.toTypedArray()) { _, i -> hid?.connect(list[i]) }
            .show()
    }
}

class PadView(c: Context) : View(c) {
    var status = "Starting..."
    var out: (ByteArray) -> Unit = {}
    var onMenu: () -> Unit = {}

    private class Ctl(val label: String, val bit: Int) { val r = RectF(); var on = false }
    private val names = listOf("□","✕","○","△","L1","R1","L2","R2","SHARE","OPT","L3","R3","PS","MENU")
    private val ctl = names.mapIndexed { i, n -> Ctl(n, if (i < 13) i else -1) }
    private val sc = arrayOf(PointF(), PointF()); private var sr = 0f
    private val dc = PointF(); private var dr = 0f
    private val sp = intArrayOf(-1, -1); private val sv = Array(2) { FloatArray(2) }
    private var dU = false; private var dD = false; private var dL = false; private var dR = false
    var last = ByteArray(0)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val W = w.toFloat(); val H = h.toFloat()
        fun set(i: Int, cx: Float, cy: Float, hw: Float, hh: Float) = ctl[i].r.set(cx - hw, cy - hh, cx + hw, cy + hh)
        val f = .10f * H; val o = .19f * H
        set(3, .84f * W, .46f * H - o, f, f); set(1, .84f * W, .46f * H + o, f, f)
        set(0, .84f * W - o, .46f * H, f, f); set(2, .84f * W + o, .46f * H, f, f)
        set(6, .11f * W, .09f * H, .09f * W, .07f * H); set(4, .11f * W, .22f * H, .09f * W, .055f * H)
        set(7, .89f * W, .09f * H, .09f * W, .07f * H); set(5, .89f * W, .22f * H, .09f * W, .055f * H)
        set(8, .26f * W, .12f * H, .05f * W, .05f * H); set(9, .74f * W, .12f * H, .05f * W, .05f * H)
        set(12, .5f * W, .42f * H, .07f * H, .07f * H); set(13, .5f * W, .58f * H, .05f * W, .045f * H)
        set(10, .43f * W, .78f * H, .035f * W, .05f * H); set(11, .57f * W, .78f * H, .035f * W, .05f * H)
        sr = .24f * H; sc[0].set(.28f * W, .74f * H); sc[1].set(.72f * W, .74f * H)
        dr = .26f * H; dc.set(.16f * W, .44f * H)
    }

    override fun onDraw(cv: Canvas) {
        cv.drawColor(0xFF0B0C10.toInt())
        p.style = Paint.Style.FILL; p.color = 0xFF14161C.toInt()
        cv.drawRoundRect(width * .32f, height * .03f, width * .68f, height * .30f, 24f, 24f, p)
        for (s in 0..1) {
            p.style = Paint.Style.FILL; p.color = 0xFF171920.toInt(); cv.drawCircle(sc[s].x, sc[s].y, sr, p)
            p.color = 0xFF4A5063.toInt(); cv.drawCircle(sc[s].x + sv[s][0] * sr * .6f, sc[s].y + sv[s][1] * sr * .6f, sr * .42f, p)
        }
        p.color = 0xFF23262F.toInt()
        cv.drawRoundRect(dc.x - dr * .3f, dc.y - dr, dc.x + dr * .3f, dc.y + dr, 16f, 16f, p)
        cv.drawRoundRect(dc.x - dr, dc.y - dr * .3f, dc.x + dr, dc.y + dr * .3f, 16f, 16f, p)
        p.color = 0xFF6C7590.toInt()
        if (dU) cv.drawCircle(dc.x, dc.y - dr * .65f, dr * .18f, p); if (dD) cv.drawCircle(dc.x, dc.y + dr * .65f, dr * .18f, p)
        if (dL) cv.drawCircle(dc.x - dr * .65f, dc.y, dr * .18f, p); if (dR) cv.drawCircle(dc.x + dr * .65f, dc.y, dr * .18f, p)
        val cols = mapOf("□" to 0xFFFF8BD0, "✕" to 0xFF6C9CFF, "○" to 0xFFFF5A5A, "△" to 0xFF3FD0B0)
        for (c in ctl) {
            p.style = Paint.Style.FILL; p.color = if (c.on) 0xFF5A6178.toInt() else 0xFF1C1F27.toInt()
            val round = c.bit in 0..3 || c.bit == 12
            if (round) cv.drawOval(c.r, p) else cv.drawRoundRect(c.r, 18f, 18f, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 4f; p.color = (cols[c.label] ?: 0xFF343946).toInt()
            if (round) cv.drawOval(c.r, p) else cv.drawRoundRect(c.r, 18f, 18f, p)
            p.style = Paint.Style.FILL; p.color = (cols[c.label] ?: 0xFFCFD3DC).toInt()
            p.textAlign = Paint.Align.CENTER; p.textSize = c.r.height() * .38f
            cv.drawText(c.label, c.r.centerX(), c.r.centerY() + p.textSize / 3, p)
        }
        p.color = 0xFF8A90A0.toInt(); p.textSize = height * .05f
        cv.drawText(status, width / 2f, height * .2f, p)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val a = e.actionMasked
        val cancel = a == MotionEvent.ACTION_CANCEL
        val skip = if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_POINTER_UP) e.actionIndex else -1
        val on = BooleanArray(ctl.size); val live = HashSet<Int>()
        var u = false; var d = false; var l = false; var r = false
        if (!cancel) for (i in 0 until e.pointerCount) {
            if (i == skip) continue
            val id = e.getPointerId(i); val x = e.getX(i); val y = e.getY(i); live.add(id)
            var used = false
            for (s in 0..1) if (sp[s] == id || (sp[s] == -1 && hypot(x - sc[s].x, y - sc[s].y) < sr)) {
                sp[s] = id
                val vx = (x - sc[s].x) / sr; val vy = (y - sc[s].y) / sr
                val m = hypot(vx, vy).coerceAtLeast(1f)
                sv[s][0] = vx / m; sv[s][1] = vy / m; used = true; break
            }
            if (used) continue
            if (hypot(x - dc.x, y - dc.y) < dr) {
                val vx = (x - dc.x) / dr; val vy = (y - dc.y) / dr
                if (vy < -.3f) u = true; if (vy > .3f) d = true; if (vx < -.3f) l = true; if (vx > .3f) r = true
                continue
            }
            ctl.forEachIndexed { k, c -> if (c.r.contains(x, y)) on[k] = true }
        }
        for (s in 0..1) if (sp[s] != -1 && sp[s] !in live) { sp[s] = -1; sv[s][0] = 0f; sv[s][1] = 0f }
        if ((u && !dU) || (d && !dD) || (l && !dL) || (r && !dR)) buzz()
        dU = u; dD = d; dL = l; dR = r
        ctl.forEachIndexed { k, c ->
            if (on[k] != c.on) { c.on = on[k]; if (on[k]) { buzz(); if (c.bit == -1) onMenu() } }
        }
        invalidate()
        val rep = build()
        if (!rep.contentEquals(last)) { last = rep; out(rep) }
        return true
    }

    @Suppress("DEPRECATION")
    private fun buzz() { performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING) }

    private fun build(): ByteArray {
        var m = 0
        for (c in ctl) if (c.on && c.bit >= 0) m = m or (1 shl c.bit)
        val hat = when {
            dU && dR -> 1; dR && dD -> 3; dD && dL -> 5; dL && dU -> 7
            dU -> 0; dR -> 2; dD -> 4; dL -> 6; else -> 8
        }
        fun ax(v: Float) = ((v * 127f) + 128f).toInt().coerceIn(0, 255).toByte()
        val l2 = if (ctl[6].on) 255 else 0; val r2 = if (ctl[7].on) 255 else 0
        return byteArrayOf(m.toByte(), (m shr 8).toByte(), hat.toByte(),
            ax(sv[0][0]), ax(sv[0][1]), ax(sv[1][0]), ax(sv[1][1]), l2.toByte(), r2.toByte())
    }
}
