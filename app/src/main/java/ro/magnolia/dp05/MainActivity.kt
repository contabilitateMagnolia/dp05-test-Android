package ro.magnolia.dp05

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private val ACTION_USB_PERMISSION = "ro.magnolia.dp05.USB_PERMISSION"

    // =============================================
    // Cod operator / parolă confirmate funcționale pe acest DP-05
    // =============================================
    private val OP_CODE = "30"
    private val OP_PWD = "0030"

    private lateinit var usbManager: UsbManager
    private var connection: UsbDeviceConnection? = null
    private var dataInterface: UsbInterface? = null
    private var epIn: UsbEndpoint? = null
    private var epOut: UsbEndpoint? = null

    private var seqCounter = 0x20
    @Volatile private var reading = false
    private val rxQueue = LinkedBlockingQueue<ByteArray>()

    private lateinit var logView: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var statusView: TextView
    private lateinit var connectBtn: Button
    private lateinit var cardBtn: Button
    private lateinit var cashBtn: Button
    private lateinit var reportXBtn: Button
    private lateinit var reportZBtn: Button

    private val mainHandler = Handler(Looper.getMainLooper())

    // =============================================
    // Cadru Datecs: <01><LEN><SEQ><CMD><DATA><05><BCC><03>
    // =============================================
    private val STX = 0x01
    private val ETB = 0x05
    private val ETX = 0x03
    private val NAK = 0x15
    private val SYN = 0x16

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                synchronized(this) {
                    @Suppress("DEPRECATION")
                    val dev: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        dev?.let { openDevice(it) }
                    } else {
                        log("❌ Permisiune USB refuzată.")
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        buildUi()
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        ContextCompat.registerReceiver(this, usbReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    // =============================================
    // UI simplu, construit din cod (fără fișiere de layout separate)
    // =============================================
    private fun buildUi() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(32, 64, 32, 32)

        statusView = TextView(this)
        statusView.text = "🔌 Deconectat"
        statusView.textSize = 16f
        root.addView(statusView)

        connectBtn = Button(this).apply { text = "🔗 Conectare USB" }
        connectBtn.setOnClickListener { connectClicked() }
        root.addView(connectBtn)

        val row1 = LinearLayout(this)
        row1.orientation = LinearLayout.HORIZONTAL
        cardBtn = Button(this).apply { text = "💳 Test 0.01 CARD"; isEnabled = false }
        cashBtn = Button(this).apply { text = "💵 Test 0.01 NUMERAR"; isEnabled = false }
        cardBtn.setOnClickListener { sellTest(paidMode = 1, label = "CARD") }
        cashBtn.setOnClickListener { sellTest(paidMode = 0, label = "NUMERAR") }
        row1.addView(cardBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row1.addView(cashBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row1)

        val row2 = LinearLayout(this)
        row2.orientation = LinearLayout.HORIZONTAL
        reportXBtn = Button(this).apply { text = "📄 Raport X"; isEnabled = false }
        reportZBtn = Button(this).apply { text = "🔒 Raport Z"; isEnabled = false }
        reportXBtn.setOnClickListener { report('X') }
        reportZBtn.setOnClickListener { report('Z') }
        row2.addView(reportXBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row2.addView(reportZBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(row2)

        logView = TextView(this)
        logView.textSize = 12f
        logView.setPadding(8, 8, 8, 8)
        scrollView = ScrollView(this)
        scrollView.addView(logView)
        root.addView(
            scrollView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        setContentView(root)
    }

    private fun log(msg: String) {
        mainHandler.post {
            logView.append("\n$msg")
            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun setStatus(msg: String) {
        mainHandler.post { statusView.text = msg }
    }

    private fun enableButtons(enabled: Boolean) {
        mainHandler.post {
            cardBtn.isEnabled = enabled
            cashBtn.isEnabled = enabled
            reportXBtn.isEnabled = enabled
            reportZBtn.isEnabled = enabled
        }
    }

    // =============================================
    // CONECTARE
    // =============================================
    private fun connectClicked() {
        val deviceList = usbManager.deviceList
        if (deviceList.isEmpty()) {
            log("❌ Niciun dispozitiv USB detectat. Verifică adaptorul OTG și cablul.")
            return
        }
        // Ia primul dispozitiv găsit. Dacă ai mai multe conectate simultan,
        // schimbă aici ca să alegi explicit casa de marcat.
        val dev = deviceList.values.first()
        log("🔍 Dispozitiv găsit: ${dev.deviceName} (VID=${dev.vendorId}, PID=${dev.productId}) — cer permisiune...")
        val permissionIntent = PendingIntent.getBroadcast(
            this, 0, Intent(ACTION_USB_PERMISSION), PendingIntent.FLAG_MUTABLE
        )
        usbManager.requestPermission(dev, permissionIntent)
    }

    private fun openDevice(dev: UsbDevice) {
        val conn = usbManager.openDevice(dev)
        if (conn == null) {
            log("❌ Nu am putut deschide dispozitivul.")
            return
        }
        connection = conn

        var foundIface: UsbInterface? = null
        var foundIn: UsbEndpoint? = null
        var foundOut: UsbEndpoint? = null

        for (i in 0 until dev.interfaceCount) {
            val iface = dev.getInterface(i)
            var inEp: UsbEndpoint? = null
            var outEp: UsbEndpoint? = null
            for (e in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(e)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep
                    if (ep.direction == UsbConstants.USB_DIR_OUT) outEp = ep
                }
            }
            if (inEp != null && outEp != null) {
                foundIface = iface
                foundIn = inEp
                foundOut = outEp
                break
            }
        }

        if (foundIface == null || foundIn == null || foundOut == null) {
            log("❌ Nu am găsit o interfață cu endpoint-uri bulk IN/OUT.")
            return
        }

        // forceClaim = true — preia interfața chiar dacă e deja legată de driverul de kernel (cdc_acm).
        // Asta e exact ce nu poate face WebUSB din Chrome fără root.
        val claimed = conn.claimInterface(foundIface, true)
        if (!claimed) {
            log("❌ Nu am putut revendica interfața USB.")
            return
        }
        dataInterface = foundIface
        epIn = foundIn
        epOut = foundOut
        log("✅ Interfață ${foundIface.id} revendicată (IN=${foundIn.address}, OUT=${foundOut.address}).")

        // Configurare CDC-ACM (9600 8N1 + DTR/RTS) — ignorată silențios dacă dispozitivul
        // e vendor-specific și nu răspunde la aceste cereri de control.
        try {
            val lineCoding = byteArrayOf(
                0x80.toByte(), 0x25, 0x00, 0x00, // 9600 baud, little-endian
                0x00, // 1 stop bit
                0x00, // fără paritate
                0x08  // 8 biți date
            )
            conn.controlTransfer(0x21, 0x20, 0, foundIface.id, lineCoding, lineCoding.size, 1000)
            conn.controlTransfer(0x21, 0x22, 0x03, foundIface.id, null, 0, 1000)
            log("✅ Linie serială configurată (9600 8N1, DTR/RTS).")
        } catch (e: Exception) {
            log("ℹ️ Configurare CDC-ACM ignorată: ${e.message}")
        }

        reading = true
        Thread { readLoop() }.start()

        setStatus("✅ Conectat la DP-05")
        enableButtons(true)

        sendAndReceive(74, "", "GET_STATUS")
    }

    private fun readLoop() {
        val buf = ByteArray(64)
        while (reading) {
            val conn = connection ?: break
            val ep = epIn ?: break
            val n = conn.bulkTransfer(ep, buf, buf.size, 500)
            if (n > 0) {
                rxQueue.put(buf.copyOf(n))
            }
        }
    }

    // =============================================
    // CODIFICARE / DECODIFICARE CADRU DATECS
    // =============================================
    private fun encode4(value: Int): IntArray {
        val v = value and 0xFFFF
        return intArrayOf(
            ((v shr 12) and 0xF) + 0x30,
            ((v shr 8) and 0xF) + 0x30,
            ((v shr 4) and 0xF) + 0x30,
            (v and 0xF) + 0x30
        )
    }

    private fun decode4(bytes: List<Int>): Int {
        var value = 0
        for (i in 0 until 4) {
            value = (value shl 4) or ((bytes[i] - 0x30) and 0xF)
        }
        return value
    }

    private fun nextSeq(): Int {
        val s = seqCounter
        seqCounter++
        if (seqCounter > 0xFF) seqCounter = 0x20
        return s
    }

    private fun buildFrame(seq: Int, cmdNumber: Int, dataStr: String): ByteArray {
        val dataBytes = dataStr.toByteArray(Charsets.UTF_8)
        val cmdBytes = encode4(cmdNumber)
        val innerLen = 4 + 1 + 4 + dataBytes.size + 1
        val lenBytes = encode4(innerLen + 0x20)

        val afterPreamble = ArrayList<Int>()
        lenBytes.forEach { afterPreamble.add(it) }
        afterPreamble.add(seq)
        cmdBytes.forEach { afterPreamble.add(it) }
        dataBytes.forEach { afterPreamble.add(it.toInt() and 0xFF) }
        afterPreamble.add(ETB)

        var sum = 0
        for (b in afterPreamble) sum += b
        val bccBytes = encode4(sum and 0xFFFF)

        val frame = ArrayList<Int>()
        frame.add(STX)
        frame.addAll(afterPreamble)
        bccBytes.forEach { frame.add(it) }
        frame.add(ETX)

        return ByteArray(frame.size) { frame[it].toByte() }
    }

    data class ParsedFrame(val seq: Int, val cmdNumber: Int, val data: String)

    // Întoarce Pair("FRAME"/"SYN"/"NAK", payload) sau null dacă bufferul nu conține încă un cadru complet.
    private fun tryParseFrame(buffer: ArrayList<Int>): Pair<String, Any?>? {
        while (buffer.isNotEmpty() && buffer[0] != STX && buffer[0] != NAK && buffer[0] != SYN) {
            buffer.removeAt(0)
        }
        if (buffer.isEmpty()) return null
        if (buffer[0] == NAK) { buffer.removeAt(0); return Pair("NAK", null) }
        if (buffer[0] == SYN) { buffer.removeAt(0); return Pair("SYN", null) }

        val etxIndex = buffer.indexOf(ETX)
        if (etxIndex == -1) return null

        val frame = ArrayList(buffer.subList(0, etxIndex + 1))
        repeat(etxIndex + 1) { buffer.removeAt(0) }

        val seq = frame[5]
        val cmdNumber = decode4(frame.subList(6, 10))
        val bccStart = frame.size - 5
        var etbIndex = -1
        for (i in bccStart - 1 downTo 10) {
            if (frame[i] == ETB) { etbIndex = i; break }
        }
        val middle = if (etbIndex != -1) frame.subList(10, etbIndex) else emptyList()
        val dataBytes = ByteArray(middle.size) { middle[it].toByte() }
        val dataStr = String(dataBytes, Charsets.UTF_8)

        return Pair("FRAME", ParsedFrame(seq, cmdNumber, dataStr))
    }

    // =============================================
    // TRIMITE COMANDĂ ȘI AȘTEAPTĂ RĂSPUNSUL (blocant — rulează pe thread de fundal)
    // =============================================
    private fun sendAndReceive(cmdNumber: Int, dataStr: String, description: String, maxRetries: Int = 2): ParsedFrame? {
        val conn = connection
        val out = epOut
        if (conn == null || out == null) {
            log("❌ Nu ești conectat!")
            return null
        }

        for (attempt in 0..maxRetries) {
            val seq = nextSeq()
            val frame = buildFrame(seq, cmdNumber, dataStr)
            log("📤 TX $description CMD=$cmdNumber DATA=\"$dataStr\"")
            val sent = conn.bulkTransfer(out, frame, frame.size, 2000)
            if (sent < 0) {
                log("❌ Eroare scriere.")
                return null
            }

            val buffer = ArrayList<Int>()
            val deadline = System.currentTimeMillis() + 20000
            while (System.currentTimeMillis() < deadline) {
                val chunk = rxQueue.poll(3, TimeUnit.SECONDS) ?: break
                chunk.forEach { buffer.add(it.toInt() and 0xFF) }
                val parsed = tryParseFrame(buffer) ?: continue
                when (parsed.first) {
                    "SYN" -> continue
                    "NAK" -> { log("⚠️ NAK primit — retrimit"); break }
                    "FRAME" -> {
                        val f = parsed.second as ParsedFrame
                        if (f.seq != seq) continue
                        log("✅ RX CMD=${f.cmdNumber} DATA=\"${f.data}\"")
                        return f
                    }
                }
            }
            log("⏳ Fără răspuns (încercarea ${attempt + 1}/${maxRetries + 1})")
        }
        return null
    }

    private fun errorCodeOf(dataStr: String): Int? {
        val first = dataStr.split("\t").firstOrNull() ?: return null
        return first.trim().toIntOrNull()
    }

    // =============================================
    // TESTE — produs generic 0.01 lei, card sau numerar
    // Secvență: 48 Deschide bon -> 49 Adaugă produs -> 53 Plată -> 56 Închide bon
    // =============================================
    private fun sellTest(paidMode: Int, label: String) {
        Thread {
            val price = "0.01"

            fun checkOk(res: ParsedFrame?, step: String): Boolean {
                if (res == null) { log("❌ $step: fără răspuns"); return false }
                val err = errorCodeOf(res.data)
                if (err != 0) { log("❌ $step: eroare cod $err"); return false }
                return true
            }

            log("💰 Test vânzare $label — $price lei...")
            val openRes = sendAndReceive(48, "$OP_CODE\t$OP_PWD\t1\t", "OPEN_RECEIPT")
            if (!checkOk(openRes, "Deschidere bon")) return@Thread

            val sellRes = sendAndReceive(49, "PRODUS $label\t1\t$price\t1.000\t0\t\t0\tbuc\t", "SELL_ITEM")
            if (!checkOk(sellRes, "Adăugare produs")) return@Thread

            val payRes = sendAndReceive(53, "$paidMode\t$price\t", "PAYMENT")
            if (!checkOk(payRes, "Plată")) return@Thread

            val closeRes = sendAndReceive(56, "", "CLOSE_RECEIPT")
            if (!checkOk(closeRes, "Închidere bon")) return@Thread

            log("✅ Bon $label de $price lei trimis cu succes.")
        }.start()
    }

    private fun report(type: Char) {
        Thread {
            val res = sendAndReceive(69, "$type\t", "REPORT_$type", 0)
            if (res != null) {
                val err = errorCodeOf(res.data)
                if (err == 0) log("✅ Raport $type emis cu succes.")
                else log("❌ Eroare la Raport $type: cod $err")
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        reading = false
        try { unregisterReceiver(usbReceiver) } catch (e: Exception) { }
        try {
            dataInterface?.let { connection?.releaseInterface(it) }
            connection?.close()
        } catch (e: Exception) { }
    }
}
