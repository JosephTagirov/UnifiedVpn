package org.olcbox.app.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.olcbox.app.data.datasource.AndroidLocationsRepository
import org.olcbox.app.data.repository.SubscriptionFetchProxy
import java.util.UUID

class AndroidNotificationStore(context:Context):NotificationDeliveryStore {
    private val prefs=context.getSharedPreferences("device-notifications",Context.MODE_PRIVATE)
    override fun load():NotificationDeliveryState {
        val saved=prefs.getString("state",null)
        if(saved!=null)runCatching { return Json.decodeFromString<NotificationDeliveryState>(saved) }
        return NotificationDeliveryState(UUID.randomUUID().toString().replace("-", "")).also(::save)
    }
    override fun save(value:NotificationDeliveryState) {
        check(prefs.edit().putString("state",Json.encodeToString(NotificationDeliveryState.serializer(),value)).commit())
    }
}

class AndroidMessagePresenter(private val context:Context):DeviceMessagePresenter {
    private val channel="unifiedvpn_owner_messages"
    override fun permitted():Boolean {
        if(!NotificationManagerCompat.from(context).areNotificationsEnabled())return false
        val manager=context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if(Build.VERSION.SDK_INT>=26 && manager.getNotificationChannel(channel)?.importance==NotificationManager.IMPORTANCE_NONE)return false
        return true
    }
    override suspend fun show(message:DeviceMessage):Boolean {
        if(!permitted())return false
        val manager=context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if(Build.VERSION.SDK_INT>=26)manager.createNotificationChannel(NotificationChannel(channel,"Сообщения и подписка",NotificationManager.IMPORTANCE_DEFAULT))
        val icon=runCatching { context.packageManager.getApplicationInfo(context.packageName,PackageManager.GET_META_DATA).metaData?.getInt("org.olcbox.app.NOTIFICATION_SMALL_ICON") }.getOrNull()?.takeIf { it!=0 } ?: android.R.drawable.stat_notify_chat
        val launch=context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return false
        val intent=PendingIntent.getActivity(context,902,launch,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val public=NotificationCompat.Builder(context,channel).setSmallIcon(icon).setContentTitle("UnifiedVPN").setContentText("Новое сообщение").build()
        val notification=NotificationCompat.Builder(context,channel).setSmallIcon(icon).setContentTitle(message.title)
            .setContentText(message.body).setStyle(NotificationCompat.BigTextStyle().bigText(message.body))
            .setContentIntent(intent).setAutoCancel(true).setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public).build()
        return try { manager.notify(message.id,message.id.hashCode(),notification);true }catch(_:SecurityException){false}
    }
}

object AndroidDeviceNotifications {
    @Volatile private var instance:DeviceNotifications?=null
    fun get(context:Context):DeviceNotifications = instance ?: synchronized(this) {
        instance ?: DeviceNotifications(AndroidLocationsRepository.get(context),AndroidNotificationStore(context),AndroidMessagePresenter(context.applicationContext)).also { instance=it }
    }
    fun schedule(context:Context) {
        val scheduler=context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        if(scheduler.allPendingJobs.any { it.id==58263 })return
        scheduler.schedule(JobInfo.Builder(58263,ComponentName(context,DeviceNotificationJob::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(15*60*1000L).setPersisted(true).build())
    }
}

class DeviceNotificationJob:JobService() {
    private var work:Job?=null
    override fun onStartJob(params:JobParameters):Boolean {
        work=CoroutineScope(SupervisorJob()+Dispatchers.IO).launch {
            try { AndroidDeviceNotifications.get(applicationContext).pollOnce() }
            catch(e:CancellationException){throw e} catch(_:Exception){}
            jobFinished(params,false)
        }
        return true
    }
    override fun onStopJob(params:JobParameters):Boolean {work?.cancel();return true}
    override fun onDestroy(){work?.cancel();super.onDestroy()}
}
