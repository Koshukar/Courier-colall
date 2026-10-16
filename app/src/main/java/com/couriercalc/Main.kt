package com.couriercalc

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.util.Locale

// ---------- настройки ----------

data class Config(
    val avgSpeedKmh: Double = 25.0,
    val fixedMinutes: Double = 6.0,
    val goodPerKm: Double = 2.5,
    val goodPerHour: Double = 40.0,
)

object Prefs {
    private const val FILE = "courier_calc"

    fun load(ctx: Context): Config {
        val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val d = Config()
        return Config(
            avgSpeedKmh = p.getFloat("speed", d.avgSpeedKmh.toFloat()).toDouble(),
            fixedMinutes = p.getFloat("fixed", d.fixedMinutes.toFloat()).toDouble(),
            goodPerKm = p.getFloat("goodKm", d.goodPerKm.toFloat()).toDouble(),
            goodPerHour = p.getFloat("goodHour", d.goodPerHour.toFloat()).toDouble(),
        )
    }

    fun save(ctx: Context, c: Config) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putFloat("speed", c.avgSpeedKmh.toFloat())
            .putFloat("fixed", c.fixedMinutes.toFloat())
            .putFloat("goodKm", c.goodPerKm.toFloat())
            .putFloat("goodHour", c.goodPerHour.toFloat())
            .apply()
    }
}

// ---------- разбор экрана ----------

data class Metrics(
    val price: Double,
    val km: Double,
    val minutes: Double,
    val perKm: Double,
    val perHour: Double,
)

object OfferParser {
    private const val SP = "[\\s\\u00A0\\u202F]*"

    private val priceRe = Regex(
        "(\\d{1,4}(?:[.,]\\d{1,2})?)${SP}(?:zł|zl|PLN)(?![\\p{L}])",
        RegexOption.IGNORE_CASE,
    )
    private val kmRe = Regex(
        "(\\d{1,3}(?:[.,]\\d{1,2})?)${SP}(?:км|km)(?![\\p{L}/])",
        RegexOption.IGNORE_CASE,
    )
    private val acceptWords = listOf(
        "прийняти", "принять", "accept", "przyjmij", "zaakceptuj",
    )

    private fun String.toNum(): Double = replace(',', '.').toDouble()

    fun parse(texts: List<String>, cfg: Config): Metrics? {
        val hasAccept = texts.any { t ->
            val l = t.trim().lowercase()
            l.length <= 20 && acceptWords.any { l.contains(it) }
        }
        if (!hasAccept) return null

        val price = texts.firstNotNullOfOrNull {
            priceRe.find(it)?.groupValues?.get(1)?.toNum()
        } ?: return null

        val km = texts.sumOf { t ->
            kmRe.findAll(t).sumOf { it.groupValues[1].toNum() }
        }
        if (km <= 0.0) return null

        val minutes = km / cfg.avgSpeedKmh * 60.0 + cfg.fixedMinutes
        return Metrics(price, km, minutes, price / km, price / minutes * 60.0)
    }
}

// ---------- сервис с оверлеем ----------

class EarningsService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var scheduled = false
    private var wm: WindowManager? = null
    private var overlay: LinearLayout? = null
    private var perKmView: TextView? = null
    private var perHourView: TextView? = null
    private var infoView: TextView? = null

    override fun onServiceConnected() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.packageName == packageName) return
        if (scheduled) return
        scheduled = true
        handler.postDelayed({
            scheduled = false
            process()
        }, 300)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        hideOverlay()
        super.onDestroy()
    }

    private fun process() {
        val root = rootInActiveWindow ?: return
        val texts = ArrayList<String>()
        collect(root, texts, 0)
        val metrics = OfferParser.parse(texts, Prefs.load(this))
        if (metrics == null) hideOverlay() else showOverlay(metrics)
    }

    private fun collect(node: AccessibilityNodeInfo?, out: MutableList<String>, depth: Int) {
        if (node == null || depth > 40) return
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        for (i in 0 until node.childCount) collect(node.getChild(i), out, depth + 1)
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    private fun bigText(): TextView = TextView(this).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
    }

    private fun ensureOverlay() {
        if (overlay != null) return
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(10))
        }
        perKmView = bigText()
        perHourView = bigText()
        infoView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
        }
        box.addView(perKmView)
        box.addView(perHourView)
        box.addView(infoView)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(150)
        }
        wm?.addView(box, params)
        overlay = box
    }

    private fun showOverlay(m: Metrics) {
        ensureOverlay()
        val cfg = Prefs.load(this)
        val loc = Locale("pl", "PL")
        perKmView?.text = String.format(loc, "%.2f zł/км", m.perKm)
        perHourView?.text = String.format(loc, "%.0f zł/час", m.perHour)
        infoView?.text = String.format(loc, "%.2f км · ~%.0f мин", m.km, m.minutes)

        val good = m.perKm >= cfg.goodPerKm && m.perHour >= cfg.goodPerHour
        val ok = m.perKm >= cfg.goodPerKm || m.perHour >= cfg.goodPerHour
        val color = when {
            good -> 0xEE1B8A3A.toInt()
            ok -> 0xEEE08A00.toInt()
            else -> 0xEEC62828.toInt()
        }
        overlay?.background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(color)
        }
    }

    private fun hideOverlay() {
        overlay?.let { runCatching { wm?.removeView(it) } }
        overlay = null
    }
}

// ---------- экран настроек ----------

class MainActivity : ComponentActivity() {
    private var serviceOn by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { Screen() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        serviceOn = isServiceEnabled(this)
    }

    @Composable
    private fun Screen() {
        val ctx = this
        val cfg = remember { Prefs.load(ctx) }
        var speed by remember { mutableStateOf(fmt(cfg.avgSpeedKmh)) }
        var fixed by remember { mutableStateOf(fmt(cfg.fixedMinutes)) }
        var goodKm by remember { mutableStateOf(fmt(cfg.goodPerKm)) }
        var goodHour by remember { mutableStateOf(fmt(cfg.goodPerHour)) }
        var saved by remember { mutableStateOf(false) }

        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Kurier Kalkulator", style = MaterialTheme.typography.headlineSmall)
            Text(
                if (serviceOn) "✅ Сервис включён. Откройте курьерское приложение — на экране заказа появится карточка."
                else "⚠️ Сервис выключен. Включите «Kurier Kalkulator» в специальных возможностях.",
            )
            Button(
                onClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Открыть настройки спец. возможностей") }

            Text("Как считается время", style = MaterialTheme.typography.titleMedium)
            Text("Время заказа = километры ÷ скорость + ожидание и передача клиенту.")
            Field("Средняя скорость, км/ч", speed) { speed = it; saved = false }
            Field("Ожидание + передача, мин", fixed) { fixed = it; saved = false }

            Text("Цвет карточки", style = MaterialTheme.typography.titleMedium)
            Field("Хорошо, если зл/км не ниже", goodKm) { goodKm = it; saved = false }
            Field("Хорошо, если зл/час не ниже", goodHour) { goodHour = it; saved = false }

            Button(
                onClick = {
                    val d = Config()
                    Prefs.save(
                        ctx,
                        Config(
                            avgSpeedKmh = speed.toNum(d.avgSpeedKmh).coerceAtLeast(1.0),
                            fixedMinutes = fixed.toNum(d.fixedMinutes).coerceAtLeast(0.0),
                            goodPerKm = goodKm.toNum(d.goodPerKm),
                            goodPerHour = goodHour.toNum(d.goodPerHour),
                        ),
                    )
                    saved = true
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (saved) "Сохранено ✓" else "Сохранить") }
        }
    }

    @Composable
    private fun Field(label: String, value: String, onChange: (String) -> Unit) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
    }

    private fun String.toNum(default: Double): Double =
        replace(',', '.').toDoubleOrNull() ?: default

    private fun fmt(v: Double): String =
        if (v % 1.0 == 0.0) v.toInt().toString() else v.toString()

    private fun isServiceEnabled(ctx: Context): Boolean {
        val enabled = Settings.Secure.getString(
            ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { it.startsWith("${ctx.packageName}/") }
    }
}
