package com.subscriptiontracker.app

import android.app.AlarmManager
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.AndroidViewModel
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

// 1. THEME
val PurpleBackground = Color(0xFF2B1B54)
val PurpleDialog = Color(0xFF3A2766)
val BackgroundGradient = listOf(
    Color(0xFF4B2C91),
    Color(0xFF2B1B54),
    Color(0xFF14102E)
)
val BlueCard = Color(0xFF2F5FD8)
val PaidCard = Color(0xFF16705A)
val BlueSummaryCard = Color(0xFF1E3FA8)
val BlueAccent = Color(0xFF4D7CFF)

private val AppColors = darkColorScheme(
    primary = Color(0xFFC7D4FF),
    onPrimary = Color(0xFF1B1040),
    secondary = Color(0xFFB9C8FF),
    tertiary = Color(0xFF8FF0B5),
    background = PurpleBackground,
    onBackground = Color(0xFFF3EEFF),
    surface = PurpleBackground,
    onSurface = Color(0xFFF3EEFF),
    surfaceVariant = BlueCard,
    onSurfaceVariant = Color(0xFFD5CCF0),
    outline = Color(0xFF9C8FD0),
    error = Color(0xFFFF8A9B)
)

// 2. DATA MODEL
enum class PaymentType(val label: String) {
    SUBSCRIPTION("Subscription"),
    BILL("Bill"),
    RECURRING("Recurring payment")
}

data class Subscription(
    val id: Int,
    val name: String,
    val amount: Double,
    val billingDay: Int,
    val isMonthly: Boolean = true,
    val type: PaymentType = PaymentType.SUBSCRIPTION,
    val billingMonth: Int = 1,
    val lastPaidKey: Int = 0,
    val reminderEnabled: Boolean = true,
    val reminderHour: Int = 0,
    val reminderMinute: Int = 0
)

// 3. STORAGE
private const val PREFS_NAME = "subscription_memory"
private const val PREFS_KEY = "saved_json"

fun saveSubscriptions(context: Context, list: List<Subscription>) {
    val arr = JSONArray()
    list.forEach {
        arr.put(
            JSONObject().apply {
                put("id", it.id)
                put("name", it.name)
                put("amount", it.amount)
                put("billingDay", it.billingDay)
                put("isMonthly", it.isMonthly)
                put("type", it.type.name)
                put("billingMonth", it.billingMonth)
                put("lastPaidKey", it.lastPaidKey)
                put("reminderEnabled", it.reminderEnabled)
                put("reminderHour", it.reminderHour)
                put("reminderMinute", it.reminderMinute)
            }
        )
    }
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(PREFS_KEY, arr.toString())
        .apply()
}

fun loadSubscriptions(context: Context): List<Subscription> {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    if (!prefs.contains(PREFS_KEY)) {
        return listOf(
            Subscription(1, "Spotify Premium", 119.0, 25),
            Subscription(2, "Xbox Game Pass", 349.0, 2)
        )
    }

    return try {
        val arr = JSONArray(prefs.getString(PREFS_KEY, "[]") ?: "[]")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Subscription(
                id = o.getInt("id"),
                name = o.getString("name"),
                amount = o.getDouble("amount"),
                billingDay = o.getInt("billingDay"),
                isMonthly = o.getBoolean("isMonthly"),
                type = try {
                    PaymentType.valueOf(o.optString("type", PaymentType.SUBSCRIPTION.name))
                } catch (e: Exception) {
                    PaymentType.SUBSCRIPTION
                },
                billingMonth = o.optInt("billingMonth", 1),
                lastPaidKey = o.optInt("lastPaidKey", 0),
                reminderEnabled = o.optBoolean("reminderEnabled", true),
                reminderHour = o.optInt("reminderHour", 0),
                reminderMinute = o.optInt("reminderMinute", 0)
            )
        }
    } catch (e: Exception) {
        emptyList()
    }
}

// 4. DATE AND PAYMENT LOGIC
private val MONTHS_SHORT = listOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
)
private val MONTHS_FULL = listOf(
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December"
)

fun startOfToday(): Calendar = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}

fun makeDate(year: Int, month0: Int, day: Int): Calendar = Calendar.getInstance().apply {
    clear()
    set(year, month0, 1, 0, 0, 0)
    set(Calendar.DAY_OF_MONTH, minOf(day, getActualMaximum(Calendar.DAY_OF_MONTH)))
}

fun Calendar.key(): Int =
    get(Calendar.YEAR) * 10000 + (get(Calendar.MONTH) + 1) * 100 + get(Calendar.DAY_OF_MONTH)

fun nextDue(sub: Subscription, from: Calendar): Calendar {
    val y = from.get(Calendar.YEAR)
    val m = from.get(Calendar.MONTH)
    return if (sub.isMonthly) {
        val thisMonth = makeDate(y, m, sub.billingDay)
        if (thisMonth.key() >= from.key()) thisMonth else makeDate(y, m + 1, sub.billingDay)
    } else {
        val thisYear = makeDate(y, sub.billingMonth - 1, sub.billingDay)
        if (thisYear.key() >= from.key()) thisYear else makeDate(y + 1, sub.billingMonth - 1, sub.billingDay)
    }
}

data class DueInfo(val date: Calendar, val isPaid: Boolean, val daysLeft: Int)

fun dueInfo(sub: Subscription): DueInfo {
    val today = startOfToday()
    val upcoming = nextDue(sub, today)
    val paid = sub.lastPaidKey == upcoming.key()
    val shown = if (paid) {
        val dayAfter = (upcoming.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1) }
        nextDue(sub, dayAfter)
    } else {
        upcoming
    }
    val days = ((shown.timeInMillis - today.timeInMillis) / 86_400_000.0).roundToInt()
    return DueInfo(shown, paid, days)
}

fun currentCycleKey(sub: Subscription): Int = nextDue(sub, startOfToday()).key()

fun isDoneForMonth(sub: Subscription): Boolean =
    autoPaidThisMonth(sub) || isMarkedPaid(sub)

fun isMarkedPaid(sub: Subscription): Boolean {
    if (sub.lastPaidKey == 0) return false
    val thisMonth = startOfToday().key() / 100
    return sub.lastPaidKey == currentCycleKey(sub) || sub.lastPaidKey / 100 == thisMonth
}

fun autoPaidThisMonth(sub: Subscription): Boolean {
    if (sub.type == PaymentType.BILL) return false
    val today = startOfToday()
    val year = today.get(Calendar.YEAR)
    val month = today.get(Calendar.MONTH)
    if (!sub.isMonthly && sub.billingMonth - 1 != month) return false
    return makeDate(year, month, sub.billingDay).key() < today.key()
}

data class MonthSummary(val pendingAmount: Double, val pendingCount: Int, val paidAmount: Double)

fun monthSummary(list: List<Subscription>): MonthSummary {
    val today = startOfToday()
    val thisMonth = today.key() / 100
    var pending = 0.0
    var count = 0
    var paid = 0.0
    list.forEach { sub ->
        val upcoming = nextDue(sub, today)
        val isPaid = sub.lastPaidKey == upcoming.key()
        if (!isPaid && upcoming.key() / 100 == thisMonth) {
            pending += sub.amount
            count++
        }
        val markedThisMonth = sub.lastPaidKey != 0 && sub.lastPaidKey / 100 == thisMonth
        if (markedThisMonth || autoPaidThisMonth(sub)) {
            paid += sub.amount
        }
    }
    return MonthSummary(pending, count, paid)
}

fun formatDate(c: Calendar): String =
    "${c.get(Calendar.DAY_OF_MONTH)} ${MONTHS_SHORT[c.get(Calendar.MONTH)]}"

fun formatMoney(value: Double): String = "₹" + String.format(Locale.US, "%.2f", value)

fun renewalText(days: Int): String = when (days) {
    0 -> "today"
    1 -> "tomorrow"
    else -> "in $days days"
}

// 10. REMINDERS
fun formatTime(hour: Int, minute: Int): String {
    val hour12 = if (hour % 12 == 0) 12 else hour % 12
    val amPm = if (hour < 12) "AM" else "PM"
    return String.format(Locale.US, "%d:%02d %s", hour12, minute, amPm)
}

fun nextReminderTime(sub: Subscription): Calendar? {
    if (!sub.reminderEnabled) return null
    val now = Calendar.getInstance()
    var date = dueInfo(sub).date
    for (attempt in 0 until 3) {
        val at = (date.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, sub.reminderHour)
            set(Calendar.MINUTE, sub.reminderMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (at.after(now)) return at
        val dayAfter = (date.clone() as Calendar).apply { add(Calendar.DAY_OF_MONTH, 1) }
        date = nextDue(sub, dayAfter)
    }
    return null
}

object ReminderScheduler {
    private const val CHANNEL_ID = "payment_reminders"

    private fun pending(context: Context, id: Int, name: String = "", amount: Double = 0.0): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra("id", id)
            putExtra("name", name)
            putExtra("amount", amount)
        }
        return PendingIntent.getBroadcast(
            context, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun cancel(context: Context, id: Int) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(pending(context, id))
    }

    fun scheduleAll(context: Context, list: List<Subscription>) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        list.forEach { sub ->
            val at = nextReminderTime(sub)
            if (at == null) {
                alarmManager.cancel(pending(context, sub.id))
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    at.timeInMillis,
                    pending(context, sub.id, sub.name, sub.amount)
                )
            }
        }
    }

    fun showNotification(context: Context, id: Int, name: String, amount: Double) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Payment reminders", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val openApp = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Payment due: $name")
            .setContentText(formatMoney(amount) + " is due today")
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        manager.notify(id, notification)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("id", 0)
        val name = intent.getStringExtra("name") ?: "Payment"
        val amount = intent.getDoubleExtra("amount", 0.0)
        ReminderScheduler.showNotification(context, id, name, amount)
        ReminderScheduler.scheduleAll(context, loadSubscriptions(context))
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            ReminderScheduler.scheduleAll(context, loadSubscriptions(context))
        }
    }
}

// 5. VIEWMODEL
class SubscriptionViewModel(app: Application) : AndroidViewModel(app) {

    var subscriptions by mutableStateOf(loadSubscriptions(app))
        private set

    init {
        ReminderScheduler.scheduleAll(app, subscriptions)
    }

    val monthlyTotal: Double
        get() = subscriptions.filter { it.isMonthly }.sumOf { it.amount }

    val yearlyTotal: Double
        get() = subscriptions.filter { !it.isMonthly }.sumOf { it.amount }

    fun add(
        name: String, amount: Double, day: Int,
        isMonthly: Boolean, type: PaymentType, month: Int,
        remindEnabled: Boolean, remindHour: Int, remindMinute: Int
    ) {
        val newId = (subscriptions.maxOfOrNull { it.id } ?: 0) + 1
        subscriptions = subscriptions + Subscription(
            id = newId,
            name = name.trim(),
            amount = amount,
            billingDay = day,
            isMonthly = isMonthly,
            type = type,
            billingMonth = month,
            reminderEnabled = remindEnabled,
            reminderHour = remindHour,
            reminderMinute = remindMinute
        )
        persist()
    }

    fun update(
        id: Int, name: String, amount: Double, day: Int,
        isMonthly: Boolean, type: PaymentType, month: Int,
        remindEnabled: Boolean, remindHour: Int, remindMinute: Int
    ) {
        subscriptions = subscriptions.map {
            if (it.id == id) it.copy(
                name = name.trim(),
                amount = amount,
                billingDay = day,
                isMonthly = isMonthly,
                type = type,
                billingMonth = month,
                reminderEnabled = remindEnabled,
                reminderHour = remindHour,
                reminderMinute = remindMinute
            ) else it
        }
        persist()
    }

    fun togglePaid(id: Int) {
        subscriptions = subscriptions.map {
            if (it.id == id) {
                it.copy(lastPaidKey = if (isMarkedPaid(it)) 0 else currentCycleKey(it))
            } else it
        }
        persist()
    }

    fun delete(id: Int) {
        ReminderScheduler.cancel(getApplication<Application>(), id)
        subscriptions = subscriptions.filter { it.id != id }
        persist()
    }

    private fun persist() {
        saveSubscriptions(getApplication<Application>(), subscriptions)
        ReminderScheduler.scheduleAll(getApplication<Application>(), subscriptions)
    }
}

// 6. MAIN ACTIVITY
class MainActivity : ComponentActivity() {
    private val vm: SubscriptionViewModel by viewModels()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch("android.permission.POST_NOTIFICATIONS")
        }
        setContent {
            MaterialTheme(colorScheme = AppColors) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(BackgroundGradient))
                ) {
                    Surface(modifier = Modifier.fillMaxSize(), color = Color.Transparent) {
                        DashboardScreen(vm)
                    }
                }
            }
        }
    }
}

// 7. DASHBOARD SCREEN
@Composable
fun DashboardScreen(vm: SubscriptionViewModel) {
    var showAddDialog by remember { mutableStateOf(false) }
    var editingSub by remember { mutableStateOf<Subscription?>(null) }

    val byDueDate = compareBy<Subscription> { dueInfo(it).daysLeft }.thenBy { it.name.lowercase() }
    val monthlyList = vm.subscriptions.filter { it.isMonthly }.sortedWith(byDueDate)
    val yearlyList = vm.subscriptions.filter { !it.isMonthly }.sortedWith(byDueDate)

    Scaffold(
        containerColor = Color.Transparent,
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = BlueAccent,
                contentColor = Color.White
            ) {
                Text("+", fontSize = 28.sp)
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .padding(16.dp)
                .fillMaxSize()
        ) {
            Text(text = "Monthly Spend", fontSize = 20.sp)
            Text(
                text = "${formatMoney(vm.monthlyTotal)} / Month",
                fontSize = 32.sp,
                color = MaterialTheme.colorScheme.primary
            )
            if (vm.yearlyTotal > 0.0) {
                Text(
                    text = "+ ${formatMoney(vm.yearlyTotal)} / year in yearly bills",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            val summary = monthSummary(vm.subscriptions)
            val totalThisMonth = summary.pendingAmount + summary.paidAmount
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = BlueSummaryCard,
                    contentColor = MaterialTheme.colorScheme.onSurface
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Pending this month",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = formatMoney(summary.pendingAmount),
                        fontSize = 24.sp,
                        color = if (summary.pendingCount > 0) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.tertiary
                    )
                    Text(
                        text = when {
                            summary.pendingCount > 0 -> "${summary.pendingCount} payment(s) left to pay"
                            totalThisMonth > 0.0 -> "All paid for this month"
                            else -> "Nothing due for the rest of this month"
                        },
                        fontSize = 12.sp
                    )
                    if (totalThisMonth > 0.0) {
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = (summary.paidAmount / totalThisMonth).toFloat(),
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.tertiary,
                            trackColor = Color(0x33FFFFFF)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Paid ${formatMoney(summary.paidAmount)} of ${formatMoney(totalThisMonth)}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (vm.subscriptions.isEmpty()) {
                Text(
                    text = "Nothing added yet. Tap + to add a subscription, bill or recurring payment.",
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 88.dp)
            ) {
                if (monthlyList.isNotEmpty()) {
                    item(key = "monthly_header") { SectionHeader("Monthly bills") }
                    items(monthlyList, key = { it.id }) { sub ->
                        SubscriptionCard(
                            sub = sub,
                            onClick = { editingSub = sub },
                            onTogglePaid = { vm.togglePaid(sub.id) }
                        )
                    }
                }
                if (yearlyList.isNotEmpty()) {
                    item(key = "yearly_header") { SectionHeader("Yearly bills") }
                    items(yearlyList, key = { it.id }) { sub ->
                        SubscriptionCard(
                            sub = sub,
                            onClick = { editingSub = sub },
                            onTogglePaid = { vm.togglePaid(sub.id) }
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        SubscriptionDialog(
            title = "Add Payment",
            initial = null,
            confirmLabel = "Save",
            onDismiss = { showAddDialog = false },
            onSave = { name, amount, day, monthly, type, month, remind, hour, minute ->
                vm.add(name, amount, day, monthly, type, month, remind, hour, minute)
                showAddDialog = false
            }
        )
    }

    editingSub?.let { sub ->
        SubscriptionDialog(
            title = "Edit Payment",
            initial = sub,
            confirmLabel = "Update",
            onDismiss = { editingSub = null },
            onSave = { name, amount, day, monthly, type, month, remind, hour, minute ->
                vm.update(sub.id, name, amount, day, monthly, type, month, remind, hour, minute)
                editingSub = null
            },
            onDelete = {
                vm.delete(sub.id)
                editingSub = null
            }
        )
    }
}

@Composable
fun SectionHeader(text: String) {
    Text(
        text = text,
        fontSize = 14.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
    )
}

// 8. SUBSCRIPTION CARD
@Composable
fun SubscriptionCard(sub: Subscription, onClick: () -> Unit, onTogglePaid: () -> Unit) {
    val info = dueInfo(sub)
    val done = isDoneForMonth(sub)
    val marked = isMarkedPaid(sub)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onClick() },
        colors = CardDefaults.cardColors(
            containerColor = if (done) PaidCard else BlueCard,
            contentColor = MaterialTheme.colorScheme.onSurface
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = sub.name, fontSize = 18.sp)
                    Text(
                        text = if (done) "${sub.type.label}  •  Paid ✓" else sub.type.label,
                        fontSize = 12.sp,
                        color = if (done) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = (if (done) "Next due: " else "Due: ") +
                                "${formatDate(info.date)} (${renewalText(info.daysLeft)})",
                        fontSize = 14.sp
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(text = formatMoney(sub.amount), fontSize = 18.sp)
                    Text(
                        text = if (sub.isMonthly) "per month" else "per year",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (!sub.isMonthly) {
                        Text(
                            text = "≈ ${formatMoney(sub.amount / 12.0)} / month",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (marked || !done) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onTogglePaid) {
                        Text(if (marked) "Undo" else "Mark as paid")
                    }
                }
            } else {
                Text(
                    text = "Paid automatically this month",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

// 9. ADD / EDIT DIALOG
@Composable
fun SubscriptionDialog(
    title: String,
    initial: Subscription?,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onSave: (
        name: String, amount: Double, day: Int,
        isMonthly: Boolean, type: PaymentType, month: Int,
        remindEnabled: Boolean, remindHour: Int, remindMinute: Int
    ) -> Unit,
    onDelete: (() -> Unit)? = null
) {

    var name by remember(initial) { mutableStateOf(initial?.name ?: "") }
    var amount by remember(initial) {
        mutableStateOf(initial?.let { String.format(Locale.US, "%.2f", it.amount) } ?: "")
    }
    var day by remember(initial) { mutableStateOf(initial?.billingDay?.toString() ?: "") }
    var isMonthly by remember(initial) { mutableStateOf(initial?.isMonthly ?: true) }
    var type by remember(initial) { mutableStateOf(initial?.type ?: PaymentType.SUBSCRIPTION) }
    var month by remember(initial) {
        mutableStateOf(initial?.billingMonth ?: (startOfToday().get(Calendar.MONTH) + 1))
    }
    var remindEnabled by remember(initial) { mutableStateOf(initial?.reminderEnabled ?: true) }
    var remindHour by remember(initial) { mutableStateOf(initial?.reminderHour ?: 0) }
    var remindMinute by remember(initial) { mutableStateOf(initial?.reminderMinute ?: 0) }
    val context = LocalContext.current
    var monthMenuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val amountValue = amount.toDoubleOrNull()
    val dayValue = day.toIntOrNull()
    val isValid = name.isNotBlank() &&
            amountValue != null && amountValue > 0.0 &&
            dayValue != null && dayValue in 1..31

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PurpleDialog,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Service Name") },
                    singleLine = true,
                    isError = name.isBlank() && initial != null
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text("Type", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PaymentType.values().forEach { option ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { type = option }
                    ) {
                        RadioButton(selected = type == option, onClick = { type = option })
                        Text(option.label)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text(if (isMonthly) "Amount per month (₹)" else "Amount per year (₹)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = amount.isNotEmpty() && (amountValue == null || amountValue <= 0.0)
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isMonthly = !isMonthly }
                ) {
                    Checkbox(checked = !isMonthly, onCheckedChange = { isMonthly = !it })
                    Text("This is a yearly bill")
                }
                if (!isMonthly) {
                    Box {
                        OutlinedButton(onClick = { monthMenuOpen = true }) {
                            Text("Month: ${MONTHS_FULL[month - 1]}")
                        }
                        DropdownMenu(
                            expanded = monthMenuOpen,
                            onDismissRequest = { monthMenuOpen = false }
                        ) {
                            MONTHS_FULL.forEachIndexed { index, monthName ->
                                DropdownMenuItem(
                                    text = { Text(monthName) },
                                    onClick = {
                                        month = index + 1
                                        monthMenuOpen = false
                                    }
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
                OutlinedTextField(
                    value = day,
                    onValueChange = { day = it.filter(Char::isDigit).take(2) },
                    label = { Text(if (isMonthly) "Day of month (1-31)" else "Day (1-31)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = day.isNotEmpty() && (dayValue == null || dayValue !in 1..31)
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { remindEnabled = !remindEnabled }
                ) {
                    Checkbox(checked = remindEnabled, onCheckedChange = { remindEnabled = it })
                    Text("Remind me on the due date")
                }
                if (remindEnabled) {
                    OutlinedButton(onClick = {
                        TimePickerDialog(
                            context,
                            { _, hour, minute ->
                                remindHour = hour
                                remindMinute = minute
                            },
                            remindHour,
                            remindMinute,
                            false
                        ).show()
                    }) {
                        Text("Reminder time: ${formatTime(remindHour, remindMinute)}")
                    }
                    Text(
                        text = "If you don't pick a time, it reminds at 12:00 AM",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = isValid,
                onClick = {
                    onSave(
                        name, amountValue!!, dayValue!!, isMonthly, type, month,
                        remindEnabled, remindHour, remindMinute
                    )
                }
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = { confirmDelete = true }) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )

    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = PurpleDialog,
            title = { Text("Delete payment?") },
            text = { Text("\"${initial?.name ?: ""}\" will be permanently removed.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Keep") }
            }
        )
    }
}
