package com.akira.island.service

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.akira.island.R
import com.akira.island.core.AlarmInfo
import com.akira.island.core.IslandBus
import com.akira.island.core.Notice
import com.akira.island.core.NoticeStyle
import com.akira.island.core.Tints
import com.akira.island.core.tr
import com.akira.island.data.SettingsRepo
import com.akira.island.island.Ios

/**
 * Ближайший будильник. Его знает сама система (AlarmManager.getNextAlarmClock) — уведомления «Часов»
 * для этого не нужны. Поставили новый будильник — остров показывает плашку со временем подъёма;
 * следующий по расписанию (прошлый прозвенел или отложен) — молча.
 * Будильником системы пользуются и календари, напоминалки, трекеры сна — их «будильник» человек не ставил,
 * поэтому берём только поставленные приложением-будильником («Часы», Alarmy и т. п.).
 */
class AlarmMonitor(private val context: Context) {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) = refresh(announce = true)
    }

    fun start() {
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED), ContextCompat.RECEIVER_EXPORTED,
        )
        refresh(announce = false)
    }

    fun stop() {
        runCatching { context.unregisterReceiver(receiver) }
        IslandBus.nextAlarm.value = null
    }

    private fun isScheduleEvent(a: AlarmManager.AlarmClockInfo): Boolean {
        val pi = a.showIntent ?: return false
        return android.os.Build.VERSION.SDK_INT >= 31 && pi.isBroadcast && pi.creatorPackage == "com.sec.android.app.clockpackage"
    }

    private fun refresh(announce: Boolean) {
        val info = runCatching { alarms?.nextAlarmClock }.getOrNull()
        runCatching {
            val si = info?.showIntent
            val api31 = android.os.Build.VERSION.SDK_INT >= 31
            android.util.Log.d("AlarmDiag", "refresh announce=" + announce + " info=" + (info != null) +
                " trigger=" + info?.triggerTime + " date=" + info?.triggerTime?.let { java.util.Date(it).toString() } +
                " creatorPkg=" + si?.creatorPackage + " creatorUid=" + si?.creatorUid +
                " isActivity=" + (if (api31) si?.isActivity else null) + " isBroadcast=" + (if (api31) si?.isBroadcast else null) +
                " pi=" + si + " filterPass=" + si?.creatorPackage?.let { ClockApps.isAlarmApp(context, it) })
        }.onFailure { android.util.Log.d("AlarmDiag", "diag threw " + it) }
        val next = info?.takeIf { a -> a.showIntent?.creatorPackage?.let { ClockApps.isAlarmApp(context, it) } == true && !isScheduleEvent(a) }
            ?.let { AlarmInfo(it.triggerTime, it.showIntent?.creatorPackage, it.showIntent) }
        val prev = IslandBus.nextAlarm.value
        IslandBus.nextAlarm.value = next
        if (!announce || next == null || prev?.triggerAt == next.triggerAt) return
        val now = System.currentTimeMillis()
        if (next.triggerAt <= now) return
        // Прошлый будильник уже прозвенел или его отложили — это не новый, а очередной по расписанию
        if (prev != null && prev.triggerAt <= now + 60_000) return
        val s = SettingsRepo.current
        if (!s.enabled || !s.evAlarmSet) return
        IslandBus.post(
            Notice(
                title = tr("Будильник", "Alarm"),
                icon = Ios.load(context, R.drawable.ios_alarm),
                tint = Tints.Orange,
                style = NoticeStyle.ALARM,
                alarm = next,
                extraMs = 2_000,
            ),
        )
    }
}
