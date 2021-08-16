package com.dimowner.audiorecorder.app.moverecords

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import android.widget.RemoteViews
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.dimowner.audiorecorder.ARApplication
import com.dimowner.audiorecorder.BackgroundQueue
import com.dimowner.audiorecorder.ColorMap
import com.dimowner.audiorecorder.R
import com.dimowner.audiorecorder.app.main.MainActivity
import com.dimowner.audiorecorder.data.FileRepository
import com.dimowner.audiorecorder.data.database.LocalRepository
import com.dimowner.audiorecorder.util.OnCopyListener
import com.dimowner.audiorecorder.util.copyFileToDir
import timber.log.Timber
import java.io.File
import java.util.ArrayList

/**
 * Created on 14.08.2021.
 * @author Dimowner
 */
class MoveRecordsService : Service() {

	companion object {
		private const val CHANNEL_NAME = "MoveRecords"
		private const val CHANNEL_ID = "com.dimowner.audiorecorder.MoveRecords.Notification"
		const val ACTION_START_MOVE_RECORDS_SERVICE = "ACTION_START_MOVE_RECORDS_SERVICE"
		const val ACTION_STOP_MOVE_RECORDS_SERVICE = "ACTION_STOP_MOVE_RECORDS_SERVICE"
		const val ACTION_CANCEL_MOVE_RECORDS = "ACTION_CANCEL_MOVE_RECORDS"
		const val EXTRAS_KEY_MOVE_RECORDS_INFO = "key_move_records_info"
		private const val NOTIF_ID = 106

		fun startNotification(context: Context, moveRecordId: Int) {
			Timber.v("MOVE RECORD startNotification id = %s", moveRecordId)
			val list = ArrayList<Int>()
			list.add(moveRecordId)
			startNotification(context, list)
		}

		fun startNotification(context: Context, moveRecordList: ArrayList<Int>) {
			val intent = Intent(context, MoveRecordsService::class.java)
			intent.action = ACTION_START_MOVE_RECORDS_SERVICE
			intent.putIntegerArrayListExtra(EXTRAS_KEY_MOVE_RECORDS_INFO, moveRecordList)
			context.startService(intent)
		}
	}

	private lateinit var builder: NotificationCompat.Builder
	private lateinit var notificationManager: NotificationManagerCompat
	private lateinit var remoteViewsSmall: RemoteViews
	private val downloadingRecordName = ""
	private lateinit var copyTasks: BackgroundQueue
	private lateinit var loadingTasks: BackgroundQueue
	private lateinit var colorMap: ColorMap
	private lateinit var fileRepository: FileRepository
	private lateinit var localRepository: LocalRepository
	private var isCancelMove = false

	override fun onBind(intent: Intent): IBinder? {
		return null
	}

	override fun onCreate() {
		super.onCreate()
		colorMap = ARApplication.getInjector().provideColorMap()
		copyTasks = ARApplication.getInjector().provideCopyTasksQueue()
		loadingTasks = ARApplication.getInjector().provideLoadingTasksQueue()
		fileRepository = ARApplication.getInjector().provideFileRepository()
		localRepository = ARApplication.getInjector().provideLocalRepository()
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		if (intent != null) {
			val action = intent.action
			if (action != null && !action.isEmpty()) {
				when (action) {
					ACTION_START_MOVE_RECORDS_SERVICE -> if (intent.hasExtra(EXTRAS_KEY_MOVE_RECORDS_INFO)) {
						startMoveRecords(intent.getIntegerArrayListExtra(EXTRAS_KEY_MOVE_RECORDS_INFO) ?: ArrayList<Int>())
					}
					ACTION_STOP_MOVE_RECORDS_SERVICE -> stopService()
					ACTION_CANCEL_MOVE_RECORDS -> {
						isCancelMove = true
						stopService()
					}
				}
			}
		}
		return super.onStartCommand(intent, flags, startId)
	}

	private fun startMoveRecords(list: ArrayList<Int>) {
		var copied = 0
		var copiedPercent = 0
		var failed = 0
		val totalCount = list.size
		val oneRecordProgress = (100F / totalCount)
		if (list.isEmpty()) {
			stopService()
		} else {
			isCancelMove = false
			startNotification()
			for (recId in list) {
				loadingTasks.postRunnable {
					val record = localRepository.getRecord(recId)
					if (record != null) {
						val destinationFile: File = fileRepository.provideRecordFile(record.nameWithExtension)
						val sourceFilePath = record.path
						record.path = destinationFile.absolutePath
						copyFile(File(sourceFilePath), destinationFile,
							object : OnCopyListener {
								var prevTime = 0L
								override fun isCancel(): Boolean {
									return isCancelMove
								}

								override fun onCopyProgress(percent: Int) {
									var percent = percent
									val curTime = System.currentTimeMillis()
									if (percent >= 95) {
										percent = 100
									}
									if (percent == 100 || curTime > prevTime + 60) {
										val curRecProgress = (oneRecordProgress/100)*percent
										updateNotification(copiedPercent + curRecProgress.toInt())
										prevTime = curTime
									}
								}

								override fun onCanceled() {
									Toast.makeText(
										applicationContext,
										R.string.downloading_cancel,
										Toast.LENGTH_LONG
									).show()
									stopService()
								}

								override fun onCopyFinish(message: String) {
									copied++
									copiedPercent += oneRecordProgress.toInt()
									localRepository.updateRecord(record)
									fileRepository.deleteRecordFile(sourceFilePath)
									if (copied + failed == list.size) {
										Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
										stopService()
									}
								}

								override fun onError(message: String) {
									failed++
									copiedPercent += oneRecordProgress.toInt()
									if (copied + failed == list.size) {
										Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
										stopService()
									}
								}
							})
					}
				}
			}
		}
	}

	private fun copyFile(sourceFile: File, destinationFile: File, listener: OnCopyListener) {
		copyTasks.postRunnable {
			copyFileToDir(applicationContext, sourceFile, destinationFile, listener)
		}
	}

	private fun startNotification() {
		notificationManager = NotificationManagerCompat.from(this)
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			createNotificationChannel(CHANNEL_ID, CHANNEL_NAME)
		}
		remoteViewsSmall = RemoteViews(packageName, R.layout.layout_progress_notification)
		remoteViewsSmall.setOnClickPendingIntent(
			R.id.btn_close, getPendingSelfIntent(
				applicationContext, ACTION_CANCEL_MOVE_RECORDS
			)
		)
		remoteViewsSmall.setTextViewText(
			R.id.txt_name,
			resources.getString(R.string.downloading, downloadingRecordName)
		)
		remoteViewsSmall.setInt(
			R.id.container, "setBackgroundColor", this.resources.getColor(
				colorMap.primaryColorRes
			)
		)

		// Create notification default intent.
		val intent = Intent(applicationContext, MainActivity::class.java)
		intent.flags = Intent.FLAG_ACTIVITY_PREVIOUS_IS_TOP
		val pendingIntent = PendingIntent.getActivity(applicationContext, 0, intent, 0)

		// Create notification builder.
		builder = NotificationCompat.Builder(this, CHANNEL_ID)
		builder.setWhen(System.currentTimeMillis())
		builder.setContentTitle(resources.getString(R.string.app_name))
		builder.setSmallIcon(R.drawable.ic_save_alt)
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
			builder.priority = NotificationManagerCompat.IMPORTANCE_DEFAULT
		} else {
			builder.priority = NotificationCompat.PRIORITY_DEFAULT
		}
		// Make head-up notification.
		builder.setContentIntent(pendingIntent)
		builder.setCustomContentView(remoteViewsSmall)
		builder.setOngoing(true)
		builder.setOnlyAlertOnce(true)
		builder.setDefaults(0)
		builder.setSound(null)
		startForeground(NOTIF_ID, builder.build())
	}

	fun stopService() {
		stopForeground(true)
		stopSelf()
	}

	private fun getPendingSelfIntent(context: Context?, action: String?): PendingIntent {
		val intent = Intent(context, StopMoveRecordsReceiver::class.java)
		intent.action = action
		return PendingIntent.getBroadcast(context, 10, intent, 0)
	}

	@RequiresApi(Build.VERSION_CODES.O)
	private fun createNotificationChannel(channelId: String, channelName: String) {
		val channel = notificationManager.getNotificationChannel(channelId)
		if (channel == null) {
			val chan =
				NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_DEFAULT)
			chan.lightColor = Color.BLUE
			chan.lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
			chan.setSound(null, null)
			chan.enableLights(false)
			chan.enableVibration(false)
			notificationManager.createNotificationChannel(chan)
		} else {
			Timber.v("Channel already exists: %s", CHANNEL_ID)
		}
	}

	private fun updateNotification(percent: Int) {
		remoteViewsSmall.setProgressBar(R.id.progress, 100, percent, false)
		notificationManager.notify(NOTIF_ID, builder.build())
	}

	class StopMoveRecordsReceiver : BroadcastReceiver() {
		override fun onReceive(context: Context, intent: Intent) {
			val stopIntent = Intent(context, MoveRecordsService::class.java)
			stopIntent.action = intent.action
			context.startService(stopIntent)
		}
	}
}