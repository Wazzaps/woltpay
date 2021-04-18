package io.github.wazzaps.woltpay

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlin.math.ceil
import kotlin.math.roundToInt

// Not my best code, beware :)

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
//        setSupportActionBar(findViewById(R.id.toolbar))

        findViewById<Button>(R.id.clear_contact_map).setOnClickListener {
            getSharedPreferences("contactMap", Context.MODE_PRIVATE).edit().apply {
                clear()
            }.apply()
        }

        findViewById<Button>(R.id.setup_accessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }
}

//fun getViewTree(root: AccessibilityNodeInfo): String {
//    fun getViewDesc(v: AccessibilityNodeInfo): String {
//        return "[${v.className}:${v.viewIdResourceName}:${v.contentDescription}]: ${v.text}"
//    }
//
//    val output = StringBuilder(getViewDesc(root))
//    for (i in 0 until root.childCount) {
//        val v = root.getChild(i)
//        output.append("\n").append(
//                getViewTree(v).prependIndent("  ")
//        )
//    }
//    return output.toString()
//}

private fun createNotificationChannel(context: Context) {
    // Create the NotificationChannel, but only on API 26+ because
    // the NotificationChannel class is new and not in the support library
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val name = context.getString(R.string.channel_name)
        val descriptionText = context.getString(R.string.channel_description)
        val importance = NotificationManager.IMPORTANCE_HIGH
        val channel = NotificationChannel("woltpay", name, importance).apply {
            description = descriptionText
        }
        // Register the channel with the system
        val notificationManager: NotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }
}

fun makeNotification(service: AccessibilityService, amount: String, target: String) {
    Looper.getMainLooper().run {
        val context = service.applicationContext
        createNotificationChannel(context)
        val amountInt = ceil(amount.toFloatOrNull() ?: 0f).roundToInt()
        val payIntent = Intent(context, MyBroadcastReceiver::class.java).apply {
            action = "io.github.wazzaps.woltpay.pay"
            putExtra("amount", amountInt)
            putExtra("target", target)
        }
        val payPendingIntent: PendingIntent =
                PendingIntent.getBroadcast(context, 0, payIntent, PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = NotificationCompat.Builder(context, "woltpay")
                .setSmallIcon(R.mipmap.notification)
                .setContentTitle("Tap to pay ₪$amountInt to $target")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setNotificationSilent()
                .setLights(0, 0, 0)
                .setContentIntent(payPendingIntent)
//                .addAction(R.drawable.ic_launcher_background, "Pay", payPendingIntent)
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
        with(NotificationManagerCompat.from(context)) {
            // notificationId is a unique int for each notification that you must define
            notify(1, builder.build())
        }
    }

}

fun startPaying(context: Context, target: String, amount: Int) {
    val launchIntent = context.packageManager.getLaunchIntentForPackage("com.payboxapp")
    if (launchIntent != null) {
        g_payInfo = PayState(target, amount, PayStage.WAITING_FOR_PAYBOX)
        context.startActivity(launchIntent)
    } else {
        Toast.makeText(context, "Paybox is not installed!", Toast.LENGTH_LONG).show()
    }
}

class MyBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "io.github.wazzaps.woltpay.pay") {
            val amount = intent.getIntExtra("amount", 0)
            val target = intent.getStringExtra("target")

            if (amount <= 0 || target == null) {
                Toast.makeText(context, "Invalid intent received: $amount to $target", Toast.LENGTH_LONG).show()
            }
//            Toast.makeText(context, "Intent received: $amount to $target", Toast.LENGTH_LONG).show();

            startPaying(context, target!!, amount)
        }
    }
}

enum class PayStage {
    WAITING_FOR_PAYBOX,
    WAITING_FOR_SEARCH_BTN,
    WAITING_FOR_SEARCH_EDIT,
    WAITING_FOR_SELECTED,
}

class PayState(
        val target: String,
        val amount: Int,
        var stage: PayStage
)

var g_payInfo: PayState? = null

val AccessibilityNodeInfo.children: Sequence<AccessibilityNodeInfo>
    get() {
        return sequence {
            for (i in 0 until this@children.childCount) {
                yield(this@children.getChild(i))
            }
        }
    }

private fun getPayboxItem(event: AccessibilityEvent, strings: Array<String>, getter: (AccessibilityEvent, String) -> AccessibilityNodeInfo?) : AccessibilityNodeInfo? {
    return strings.mapNotNull {
        getter(event, it)
    }.firstOrNull()
}

class MyAccessibilityService : AccessibilityService() {

    var contactMap: SharedPreferences? = null

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        fun AccessibilityNodeInfo.getChildByClassAndDesc(classname: String, desc: String): AccessibilityNodeInfo? {
            return this.children.firstOrNull {
                it.className.toString() == classname && it.contentDescription == desc
            }
        }

        fun AccessibilityNodeInfo.getChildByClass(classname: String): AccessibilityNodeInfo? {
            return this.children.firstOrNull {
                it.className.toString() == classname
            }
        }

        if (event?.eventType == TYPE_WINDOW_CONTENT_CHANGED) {
            try {
                when (event.packageName) {
                    "com.wolt.android" -> {
                        val text = event.source.findAccessibilityNodeInfosByText(getString(R.string.your_share)).firstOrNull() ?: return

                        val amount = {
                            val p = text.parent
                            p.getChild(p.childCount - 1).text.toString().replace("\u200F", "")  // remove RTL mark
                        }()

                        fun getHostname(): String? {
                            return text.parent.parent.children
                                    .firstOrNull {
                                        it.className.toString() == "android.view.ViewGroup" && it.childCount == 3 && it.getChild(1).text.toString() == getString(R.string.host_tag)
                                    }?.getChild(0)?.text?.toString()
                        }

                        val hostname = getHostname() ?: return

                        Log.w("woltp", "Pay $amount to $hostname")
                        makeNotification(this, amount, hostname)
                    }
                    "com.payboxapp" -> {
                        val payInfo = g_payInfo ?: return
                        when (payInfo.stage) {
                            PayStage.WAITING_FOR_PAYBOX -> {
                                val button = getPayboxItem(event, arrayOf("Pay", "תשלום"))
                                {e, t -> e.source?.findAccessibilityNodeInfosByText(t)?.firstOrNull { it.text == t } } ?: return
                                button.performAction(AccessibilityNodeInfo.ACTION_CLICK)

                                payInfo.stage = PayStage.WAITING_FOR_SEARCH_BTN
                                g_payInfo = payInfo
                            }
                            PayStage.WAITING_FOR_SEARCH_BTN -> {
                                val frame = event.source ?: return
                                if (frame.className != "android.widget.FrameLayout") {
                                    return
                                }
                                val searchField = getPayboxItem(event, arrayOf("Search button", "כפתור חיפוש"))
                                {e, t -> e.source?.getChildByClassAndDesc("android.widget.TextView", t)} ?: return
                                searchField.performAction(AccessibilityNodeInfo.ACTION_CLICK)

                                payInfo.stage = PayStage.WAITING_FOR_SEARCH_EDIT
                                g_payInfo = payInfo
                            }
                            PayStage.WAITING_FOR_SEARCH_EDIT -> {
                                val frame = event.source ?: return
                                if (frame.className != "android.widget.FrameLayout") {
                                    return
                                }

                                val searchFieldContainer = getPayboxItem(event, arrayOf("Search button", "כפתור חיפוש"))
                                {e, t -> e.source?.getChildByClassAndDesc("android.widget.FrameLayout", t)} ?: return
                                val searchField = searchFieldContainer.getChildByClass("android.widget.EditText") ?: return

                                val mappedContactName = this.contactMap?.getString(payInfo.target, null) ?: payInfo.target

                                searchField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, mappedContactName)
                                })

                                payInfo.stage = PayStage.WAITING_FOR_SELECTED
                                g_payInfo = payInfo
                            }
                            PayStage.WAITING_FOR_SELECTED -> {
                                val frame = event.source ?: return
                                val giftText = getPayboxItem(event, arrayOf("Is this a gift?", "לעטוף לך למתנה?"))
                                {e, t -> e.source?.findAccessibilityNodeInfosByText(t)?.firstOrNull { it.text == t }} ?: return

                                val payLabel = giftText.parent.getChild(0)?.text ?: return
                                var newTarget: String? = null
                                for (prefix in arrayOf("Pay ", "תשלום ל")) {
                                    if (payLabel.startsWith(prefix)) {
                                        newTarget = payLabel.removePrefix(prefix).toString()
                                    }
                                }
                                if (newTarget == null) {
                                    return
                                }

                                this.contactMap?.edit()?.apply {
                                    putString(payInfo.target, newTarget)
                                }?.apply()

                                val keyboard = getPayboxItem(event, arrayOf("Numeric Keyboard", "מקלדת ספרות"))
                                {e, t -> e.source?.getChildByClassAndDesc("android.widget.LinearLayout", t)} ?: return

                                val keys = (0..9).map { digit ->
                                    keyboard.findAccessibilityNodeInfosByText(digit.toString()).firstOrNull() ?: return
                                }

                                for (digit in payInfo.amount.toString()) {
                                    keys[digit.toString().toInt()].performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                }

                                g_payInfo = null
                            }
                        }
                    }
                    else -> {
                        Log.w("woltp", "event: ${event.packageName}")
                    }
                }

            } catch (e: Exception) {
                Log.e("woltp", e.toString())
            }
        }
    }

    override fun onServiceConnected() {
        this.serviceInfo.flags = this.serviceInfo.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS

        contactMap = applicationContext.getSharedPreferences("contactMap", Context.MODE_PRIVATE)

        Looper.getMainLooper().run {
            Toast.makeText(applicationContext, "WoltPay Ready", Toast.LENGTH_SHORT).show()
        }
    }

}
