/*
This file is part of ZAX.

	ZAX is free software: you can redistribute it and/or modify
	it under the terms of the GNU General Public License as published by
	the Free Software Foundation, either version 3 of the License, or
	(at your option) any later version.

	ZAX is distributed in the hope that it will be useful,
	but WITHOUT ANY WARRANTY; without even the implied warranty of
	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
	GNU General Public License for more details.

	You should have received a copy of the GNU General Public License
	along with ZAX.  If not, see <http://www.gnu.org/licenses/>.
*/

package com.inovex.zabbixmobile.push;

import java.util.concurrent.ArrayBlockingQueue;

import org.json.JSONException;
import org.json.JSONObject;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;
import android.support.v4.app.NotificationCompat;
import android.util.Log;
import android.widget.Toast;

import com.inovex.zabbixmobile.R;
import com.inovex.zabbixmobile.activities.ProblemsActivity;
import com.inovex.zabbixmobile.model.ZaxPreferences;
import com.pubnub.api.Callback;
import com.pubnub.api.Pubnub;
import com.pubnub.api.PubnubError;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.util.Calendar;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * Push service maintaining the connection to Pubnub and showing notifications
 * when Pubnub sends data.
 *
 */
public class PushService extends Service {
	public static final String RINGTONE = "RINGTONE";
	public static final String PUBNUB_SUBSCRIBE_KEY = "PUBNUB_SUBSCRIBE_KEY";
	public static final String OLD_NOTIFICATION_ICONS = "OLD_NOTIFICATION_ICONS";

	String PUSHCHANNEL = "zabbixmobile";
	protected static final int NUM_STACKED_NOTIFICATIONS = 5;
	public static final String ACTION_ZABBIX_NOTIFICATION = "com.inovex.zabbixmobile.push.PushService.ACTION_ZABBIX_NOTIFICATION";
	public static final String ACTION_ZABBIX_NOTIFICATION_DELETE = "com.inovex.zabbixmobile.push.PushService.ACTION_ZABBIX_NOTIFICATION_DELETE";
	private static final String TAG = PushService.class.getSimpleName();
	private static int lastRequestCode = 0;
	private static AlarmManager am;
	Pubnub pubnub;
	PushListener mPushListener;
	private BroadcastReceiver mNotificationBroadcastReceiver;
	private BroadcastReceiver mNotificationDeleteBroadcastReceiver;
	private Handler handler;

	int numNotifications = 0;
	boolean initialConnect = true;
	ArrayBlockingQueue<CharSequence> previousMessages = new ArrayBlockingQueue<CharSequence>(
			NUM_STACKED_NOTIFICATIONS);
	protected boolean oldNotificationIcons;
	private String subscribeKey;
	private String ringtone;

	class PushListener extends AsyncTask<String, Void, Boolean> {
		@Override
		protected Boolean doInBackground(String... params) {
			try {
				pubnub.subscribe(params[0], new Callback() {

					@Override
					public void successCallback(String channel, Object input) {
						Log.i("PushService", "execute");
						try {
							if (input instanceof JSONObject) {
								JSONObject jsonObj = (JSONObject) input;
								String status = null, message = null;
								Long triggerid = null;

								try {
									status = jsonObj.getString("status");
								} catch (JSONException e) {
									e.printStackTrace();
								}
								try {
									message = jsonObj.getString("message");
								} catch (JSONException e) {
									e.printStackTrace();
								}
								try {
									triggerid = jsonObj.getLong("triggerid");
								} catch (JSONException e) {
									e.printStackTrace();
								}

								int notIcon;
								if (status != null && status.equals("OK")) {
									notIcon = R.drawable.ok;
								} else if (status != null
										&& status.equals("PROBLEM")) {
									notIcon = R.drawable.problem;
								} else {
									notIcon = R.drawable.icon;
								}
								String notMessage;
								if (message != null && message.length() > 0) {
									notMessage = message;
								} else {
									notMessage = jsonObj.toString();
								}

								NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(
										PushService.this);
								notificationBuilder.setTicker(notMessage);
								notificationBuilder.setWhen(System
										.currentTimeMillis());

								if (oldNotificationIcons) {
									notificationBuilder
											.setLargeIcon(BitmapFactory
													.decodeResource(
															getResources(),
															R.drawable.icon));
									notificationBuilder.setSmallIcon(notIcon);
								} else {
									notificationBuilder
											.setLargeIcon(BitmapFactory
													.decodeResource(
															getResources(),
															notIcon));
									notificationBuilder
											.setSmallIcon(R.drawable.icon);
								}

								// we do not start MainActivity directly, but
								// send a
								// broadcast which will be received by a
								// NotificationBroadcastReceiver which resets
								// the
								// notification status and starts MainActivity.
								Intent notificationIntent = new Intent();
								notificationIntent
										.setAction(ACTION_ZABBIX_NOTIFICATION);
								PendingIntent pendingIntent = PendingIntent
										.getBroadcast(
												PushService.this,
												uniqueRequestCode(),
												notificationIntent,
												PendingIntent.FLAG_CANCEL_CURRENT);
								notificationBuilder
										.setContentTitle(getResources()
												.getString(
														R.string.notification_title));
								notificationBuilder.setContentText(message);
								notificationBuilder
										.setContentIntent(pendingIntent);
								notificationBuilder
										.setNumber(++numNotifications);

								notificationBuilder.setAutoCancel(true);
								notificationBuilder.setOnlyAlertOnce(false);

								if (previousMessages.size() == NUM_STACKED_NOTIFICATIONS)
									previousMessages.poll();
								previousMessages.offer(message);
								// if there are several notifications, we stack
								// them in the
								// extended view
								if (numNotifications > 1) {
									NotificationCompat.InboxStyle inboxStyle = new NotificationCompat.InboxStyle();
									// Sets a title for the Inbox style big view
									inboxStyle
											.setBigContentTitle(getResources()
													.getString(
															R.string.notification_title));
									// Moves events into the big view
									for (CharSequence prevMessage : previousMessages) {
										inboxStyle.addLine(prevMessage);
									}
									if (numNotifications > NUM_STACKED_NOTIFICATIONS) {
										inboxStyle
												.setSummaryText((numNotifications - NUM_STACKED_NOTIFICATIONS)
														+ " more");
									}
									// Moves the big view style object into the
									// notification
									// object.
									notificationBuilder.setStyle(inboxStyle);
								}

								if (ringtone != null) {
									notificationBuilder.setSound(Uri
											.parse(ringtone));
								}

								Notification notification = notificationBuilder
										.build();

								Intent notificationDeleteIntent = new Intent();
								notificationDeleteIntent
										.setAction(ACTION_ZABBIX_NOTIFICATION_DELETE);
								notification.deleteIntent = PendingIntent
										.getBroadcast(PushService.this, 0,
												notificationDeleteIntent, 0);

								NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
								// We use the same ID because we want to stack
								// the notifications and we don't really care
								// about the trigger ID anyway (clicking the
								// notification just starts the main activity).
//								mNotificationManager.notify(0, notification);

								// Logging incoming notifications for Debuggin
								// timestamp status message triggerid network
								try {
									File folder = new File(Environment.getExternalStorageDirectory() + "/zax");
									boolean var = false;
									if (!folder.exists()) {
										var = folder.mkdir();
									}
									final String filename = folder.toString() + "/" + "push_logs.csv";
									File csv = new File(filename);
									if(!csv.exists() || !csv.isFile()){
										csv.createNewFile();
									}
									FileWriter fw = new FileWriter(csv,true);
									String date = Calendar.getInstance().getTime().toString();
									fw.append(date);
									fw.append('\t');
									fw.append(Long.toString(triggerid));
									fw.append('\t');
									fw.append(status);
									fw.append('\t');
									fw.append(message);
									fw.append('\t');

									ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
									NetworkInfo activeNetwork = cm.getActiveNetworkInfo();

									String net = null;
									switch (activeNetwork.getType()) {
										case ConnectivityManager.TYPE_WIFI:
											String ssid = ((WifiManager) getSystemService(WIFI_SERVICE)).getConnectionInfo().getSSID();
											net = "wifi - ssid: " + ssid;
											break;
										case ConnectivityManager.TYPE_MOBILE:
											net = "mobile";
											break;
										default:
											net = "other network type";
									}
									fw.append(net);
									fw.append("\t\n");
									fw.flush();
									fw.close();
									Log.d("PushService", "writing to logfile " + date +" " + status + " " + message + " " + net);
								}catch (Exception e){
									e.printStackTrace();
								}
							}
						} catch (Exception e) {
							e.printStackTrace();
						}
					}

					@Override
					public void connectCallback(String channel, Object message) {
						Log.i(TAG,
								"connect to " + channel + ": "
										+ message.toString());
						if (initialConnect) {
							handler.post(new Runnable() {

								@Override
								public void run() {
									Toast.makeText(
											PushService.this,
											PushService.this
													.getResources()
													.getString(
															R.string.push_connection_success),
											Toast.LENGTH_SHORT).show();
									initialConnect = false;
								}

							});
						}
					}

					@Override
					public void disconnectCallback(String channel,
							Object message) {
						Log.i(TAG,
								"disconnect to " + channel + ": "
										+ message.toString());
					}

					@Override
					public void errorCallback(String channel, PubnubError error) {
						Log.i(TAG,
								"error (" + channel + "): "
										+ error.getErrorString());
						if (initialConnect) {
							handler.post(new Runnable() {

								@Override
								public void run() {
									Toast.makeText(
											PushService.this,
											PushService.this
													.getResources()
													.getString(
															R.string.push_connection_error),
											Toast.LENGTH_SHORT).show();
									initialConnect = false;
								}

							});

						}
					}

					@Override
					public void reconnectCallback(String channel, Object message) {
						Log.i(TAG,
								"reconnect to " + channel + ": "
										+ message.toString());
					}

				});
				Log.i(TAG, "subscribe");
			} catch (Exception e) {
				e.printStackTrace();
			}

			return Boolean.TRUE; // Return your real result here
		}
	}

	/**
	 * This broadcast receiver reacts on a click on a notification by performing
	 * the following tasks:
	 *
	 * 1. Reset the notification numbers and previous messages.
	 *
	 * 2. Start the main activity.
	 *
	 */
	public class NotificationBroadcastReceiver extends BroadcastReceiver {

		public NotificationBroadcastReceiver() {
			super();
		}

		@Override
		public void onReceive(Context context, Intent intent) {
			numNotifications = 0;
			previousMessages.clear();
			Intent notificationIntent = new Intent(context,
					ProblemsActivity.class);
			notificationIntent.putExtra(
					ProblemsActivity.ARG_START_FROM_NOTIFICATION, true);
			notificationIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK
					| Intent.FLAG_ACTIVITY_NEW_TASK);
			context.startActivity(notificationIntent);
		}

	}

	/**
	 * This broadcast receiver reacts on dismissal of a notification.
	 *
	 * It resets the notification numbers and previous messages.
	 *
	 */
	public class NotificationDeleteReceiver extends BroadcastReceiver {

		public NotificationDeleteReceiver() {
			super();
		}

		@Override
		public void onReceive(Context context, Intent intent) {
			numNotifications = 0;
			previousMessages.clear();
		}

	}

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	@Override
	public void onCreate() {
		super.onCreate();

		Log.i(TAG, "onCreate");
		this.handler = new Handler();
		if (mPushListener == null)
			mPushListener = new PushListener();

		// Register the notification broadcast receiver.
		IntentFilter filter = new IntentFilter();
		filter.addAction(ACTION_ZABBIX_NOTIFICATION);
		mNotificationBroadcastReceiver = new NotificationBroadcastReceiver();
		try {
			registerReceiver(mNotificationBroadcastReceiver, filter);
		} catch (Exception e) {
		}
		filter = new IntentFilter();
		filter.addAction(ACTION_ZABBIX_NOTIFICATION_DELETE);
		mNotificationDeleteBroadcastReceiver = new NotificationDeleteReceiver();
		try {
			registerReceiver(mNotificationDeleteBroadcastReceiver, filter);
		} catch (Exception e) {
		}
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		super.onStartCommand(intent, flags, startId);
		Log.d(TAG, "onStartCommand");

		subscribeKey = intent.getStringExtra(PUBNUB_SUBSCRIBE_KEY);
		if (subscribeKey == null)
			subscribeKey = "";
		ringtone = intent.getStringExtra(RINGTONE);
		oldNotificationIcons = intent.getBooleanExtra(OLD_NOTIFICATION_ICONS,
				false);

		pubnub = new Pubnub("", // PUBLISH_KEY
				subscribeKey, // SUBSCRIBE_KEY
				"", // SECRET_KEY
				"", // CIPHER_KEY
				false // SSL_ON?
		);

		if (mPushListener.getStatus() != AsyncTask.Status.RUNNING
				&& mPushListener.getStatus() != AsyncTask.Status.FINISHED) {
			mPushListener.execute(PUSHCHANNEL);
			Log.i("PushListener", "start");
		}

		return START_REDELIVER_INTENT;
	}

	@Override
	public void onDestroy() {
		Log.d(TAG, "onDestroy");
		mPushListener.cancel(true);
		pubnub.unsubscribe(PUSHCHANNEL);
		unregisterReceiver(mNotificationBroadcastReceiver);
	}

	private int uniqueRequestCode() {
		return lastRequestCode++;
	}

	/**
	 * This starts or stops the push service depending on the user's settings.
	 *
	 * @param context
	 */
	public static void startOrStopPushService(Context context) {
		// start the push receiver, if it is enabled
		ZaxPreferences preferences = ZaxPreferences.getInstance(context);
		boolean push = preferences.isPushEnabled();
		Intent intent = new Intent(context, PushService.class);

		intent.putExtra(PUBNUB_SUBSCRIBE_KEY, preferences.getPushSubscribeKey());
		intent.putExtra(RINGTONE, preferences.getPushRingtone());
		intent.putExtra(OLD_NOTIFICATION_ICONS,
				preferences.isOldNotificationIcons());

		// alarm manager
		if (am == null)
			am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
		PendingIntent pendingIntent = PendingIntent.getService(context, 0,
				intent, PendingIntent.FLAG_CANCEL_CURRENT);

		if (push) {
			Log.d(TAG, "starting service");
			setRepeatingAlarm(pendingIntent);
			context.startService(intent);
		} else {
			Log.d(TAG, "stopping service");
			stopRepeatingAlarm(pendingIntent);
			context.stopService(intent);
		}

	}

	public static void killPushService(Context context) {
		Log.d(TAG, "stopping push service");
		Intent intent = new Intent(context, PushService.class);
		context.stopService(intent);
	}

	private static void setRepeatingAlarm(PendingIntent pendingIntent) {
		Log.d("PushServiceAlarm", "setRepeatingAlarm");

		// cancel old alarm
		am.cancel(pendingIntent);

		// wake up every 60 minutes to ensure service stays alive
		int alarmFrequency = 60 * 60 * 1000;
		// start service after one minute to avoid wasting precious CPU time
		// after device boot
		am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP,
				SystemClock.elapsedRealtime() + 1 * 60 * 1000, alarmFrequency,
				pendingIntent);
	}

	private static void stopRepeatingAlarm(PendingIntent pendingIntent) {
		Log.d("PushServiceAlarm", "stopRepeatingAlarm");
		am.cancel(pendingIntent);
	}

}