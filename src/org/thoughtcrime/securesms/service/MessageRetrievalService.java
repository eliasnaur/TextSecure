package org.thoughtcrime.securesms.service;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
//import android.util.Log;

import org.thoughtcrime.securesms.ApplicationContext;
import org.thoughtcrime.securesms.ConversationListActivity;
import org.thoughtcrime.securesms.dependencies.InjectableType;
//import org.thoughtcrime.securesms.gcm.GcmBroadcastReceiver;
import org.thoughtcrime.securesms.jobs.PushContentReceiveJob;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.whispersystems.jobqueue.Job;
import org.whispersystems.jobqueue.JobParameters;
import org.whispersystems.jobqueue.requirements.NetworkRequirement;
import org.whispersystems.jobqueue.requirements.NetworkRequirementProvider;
import org.whispersystems.jobqueue.requirements.RequirementListener;
import org.whispersystems.libaxolotl.InvalidVersionException;
import org.whispersystems.textsecure.api.TextSecureMessagePipe;
import org.whispersystems.textsecure.api.TextSecureMessageReceiver;
import org.whispersystems.textsecure.api.messages.TextSecureEnvelope;

import android.support.v4.app.NotificationCompat;
import android.support.v4.content.WakefulBroadcastReceiver;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.inject.Inject;

import go.log.Log;

public class MessageRetrievalService extends Service implements Runnable, InjectableType, RequirementListener {
	private final static int FOREGROUND_NOTIFICATION_ID = 1234;

  private static final String TAG = MessageRetrievalService.class.getSimpleName();

  public static final  String ACTION_KEEPALIVE         = "KEEPALIVE";
  public static final  String ACTION_ACTIVITY_STARTED  = "ACTIVITY_STARTED";
  public static final  String ACTION_ACTIVITY_FINISHED = "ACTIVITY_FINISHED";
  public static final  String ACTION_PUSH_RECEIVED     = "PUSH_RECEIVED";

  private static final int   REQUEST_TIMEOUT_MINUTES          = 15;
  private static final int   REQUEST_TIMEOUT_JITTER_MINUTES   = 1;

  private NetworkRequirement         networkRequirement;
  private NetworkRequirementProvider networkRequirementProvider;

  @Inject
  public TextSecureMessageReceiver receiver;
  private TextSecureMessagePipe pipe;
  private PowerManager.WakeLock wakeLock;
  private boolean waitingForReconnect;

  private int          activeActivities = 0;
  private List<Intent> pushPending      = new LinkedList<>();

  private AtomicBoolean stop = new AtomicBoolean(false);
  private Thread thread;

  @Override
  public void onCreate() {
    super.onCreate();
	Log.Log(TAG + ": onCreate");
    ApplicationContext.getInstance(this).injectDependencies(this);

    networkRequirement         = new NetworkRequirement(this);
    networkRequirementProvider = new NetworkRequirementProvider(this);

	PowerManager powerManager = (PowerManager)getSystemService(Context.POWER_SERVICE);
	wakeLock     = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MessageRetrieval");
	wakeLock.setReferenceCounted(false);

    networkRequirementProvider.setListener(this);

	registerForeground();
    thread = new Thread(this, "MessageRetrievalService");
	thread.start();
  }

  public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent == null) return START_STICKY;

    if      (ACTION_ACTIVITY_STARTED.equals(intent.getAction()))  incrementActive();
    else if (ACTION_ACTIVITY_FINISHED.equals(intent.getAction())) decrementActive();
    else if (ACTION_PUSH_RECEIVED.equals(intent.getAction()))     incrementPushReceived(intent);
    else if (ACTION_KEEPALIVE.equals(intent.getAction()))         keepAlive(intent);

    return START_STICKY;
  }

  @Override
  public void run() {
	  acquireWakeLock();
	  try {
		  doRun();
	  } finally {
    	  Log.Log(TAG + " exiting ws thread...");
		  releaseWakeLock();
	  }
  }

  private synchronized void releaseWakeLock() {
	  Log.Log(TAG + " releasing wakelock");
	  wakeLock.release();
	  registerForeground();
  }

  private synchronized void acquireWakeLock() {
	  Log.Log(TAG +  " acquiring wakelock");
	  wakeLock.acquire();
	  registerForeground();
  }

  private synchronized void releaseAndWait() {
	  releaseWakeLock();
	  try {
		  Log.Log(TAG + " Waiting for websocket state change....");
		  waitForConnectionNecessary();
	  } finally {
		  acquireWakeLock();
	  }
  }

  private void doRun() {
	int attempt = 0;
    while (!stop.get()) {
		if (!isConnectionNecessary()) {
			releaseAndWait();
		}
	  if (stop.get())
		  continue;

      Log.Log(TAG + " Making websocket connection....");
	  TextSecureMessagePipe thePipe = null;
	  try {
		  thePipe = receiver.createMessagePipe((REQUEST_TIMEOUT_MINUTES + REQUEST_TIMEOUT_JITTER_MINUTES)*60);
	  } catch (IOException e) {
		  Log.Log(TAG + " websocket connect error: " + e.getMessage());
	  }
	  synchronized (this) {
		  pipe = thePipe;
	  }

	  if (thePipe == null) {
		  long maxMillis = TimeUnit.MINUTES.toMillis(REQUEST_TIMEOUT_MINUTES);
		  long waitSeconds = 1;
		  long waitMillis = TimeUnit.SECONDS.toMillis(waitSeconds);
		  for (int i = 0; i < attempt; i++) {
			  if (waitMillis > maxMillis) {
				  waitMillis = maxMillis;
				  break;
			  }
			  waitSeconds = waitSeconds*2;
			  waitMillis = TimeUnit.SECONDS.toMillis(waitSeconds);
		  }
		  attempt++;
      	  Log.Log(TAG + " Setting alarm for reconnect in " + waitMillis + " attempt " + attempt);
		  synchronized (this) {
			  waitingForReconnect = true;
		  }
		  fireKeepAliveIn(MessageRetrievalService.this, waitMillis);
		  releaseAndWait();
		  continue;
	  }
	  attempt = 0;

      try {
        while (isConnectionNecessary() && !stop.get()) {
          try {
	  		scheduleKeepAlive(MessageRetrievalService.this);
            Log.Log(TAG + " Reading message...");
            thePipe.read(Integer.MAX_VALUE, TimeUnit.MILLISECONDS,
                      new TextSecureMessagePipe.MessagePipeCallback() {
                        @Override
                        public void onMessage(TextSecureEnvelope envelope) {
                          Log.Log(TAG + " Retrieved envelope! " + envelope.getSource());

                          PushContentReceiveJob receiveJob = new PushContentReceiveJob(MessageRetrievalService.this);
                          receiveJob.handle(envelope, false);

                          decrementPushReceived();
                        }
						@Override public void sleep() {
							releaseWakeLock();
						}
						@Override public void wakeup() {
							acquireWakeLock();
						}
                      });
          } catch (TimeoutException e) {
            Log.Log(TAG + " Application level read timeout...");
          } catch (InvalidVersionException e) {
            Log.Log(TAG + " InvalidVersionException: " + e.getMessage());
          }
        }
      } catch (Throwable e) {
        Log.Log(TAG + ": top level exception from websocket looper: " + e.getMessage());
      } finally {
        Log.Log(TAG + " Shutting down pipe...");
        shutdown(thePipe);
      }

      Log.Log(TAG + " Looping...");
    }
  }

  @Override
  public synchronized void onRequirementStatusChanged() {
	  waitingForReconnect = false;
	  notifyAll();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  private synchronized void incrementActive() {
	waitingForReconnect = false;
    activeActivities++;
    Log.Log(TAG + " Active Count: " + activeActivities);
    notifyAll();
  }

  private synchronized void decrementActive() {
    activeActivities--;
    Log.Log(TAG + " Active Count: " + activeActivities);
    notifyAll();
  }

  private synchronized void incrementPushReceived(Intent intent) {
    pushPending.add(intent);
    notifyAll();
  }

  private synchronized void decrementPushReceived() {
    if (!pushPending.isEmpty()) {
      Intent intent = pushPending.remove(0);
      //GcmBroadcastReceiver.completeWakefulIntent(intent);
      notifyAll();
    }
  }

  private synchronized boolean isConnectionNecessary() {
    Log.Log(TAG + " " + String.format("Network requirement: %s, active activities: %s, push pending: %s, waiting for reconnect: %s",
                             networkRequirement.isPresent(), activeActivities, pushPending.size(), waitingForReconnect));

    return TextSecurePreferences.isWebsocketRegistered(this) &&
           (true/*activeActivities > 0*/ || !pushPending.isEmpty())  &&
           networkRequirement.isPresent() && !waitingForReconnect;
  }

  private synchronized void waitForConnectionNecessary() {
    try {
      while (!isConnectionNecessary()) wait();
    } catch (InterruptedException e) {
      throw new AssertionError(e);
    }
  }

  private void shutdown(TextSecureMessagePipe pipe) {
    try {
      pipe.shutdown();
    } catch (Throwable t) {
	  Log.Log(TAG + ": pipe shutdown error: " + t.getMessage());
    }
  }

  public static void registerActivityStarted(Context activity) {
    Intent intent = new Intent(activity, MessageRetrievalService.class);
    intent.setAction(MessageRetrievalService.ACTION_ACTIVITY_STARTED);
    activity.startService(intent);
  }

  public static void registerActivityStopped(Context activity) {
    Intent intent = new Intent(activity, MessageRetrievalService.class);
    intent.setAction(MessageRetrievalService.ACTION_ACTIVITY_FINISHED);
    activity.startService(intent);
  }

  private synchronized void registerForeground() {
	  Intent launch = new Intent(this, ConversationListActivity.class);
	  PendingIntent intent = PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT);
	  Notification notification = new NotificationCompat.Builder(this)
		  .setSmallIcon(org.thoughtcrime.securesms.R.drawable.icon)
		  .setPriority(Notification.PRIORITY_MIN)
		  .setOngoing(true)
		  .setWhen(0)
		  .setContentIntent(intent)
		  .setContentTitle(getString(org.thoughtcrime.securesms.R.string.foreground_websocket_title))
		  .setContentText(wakeLock.isHeld() ?
				  getString(org.thoughtcrime.securesms.R.string.foreground_websocket_text)
				  : getString(org.thoughtcrime.securesms.R.string.foreground_websocket_text_idle))
		  .getNotification();
	  startForeground(FOREGROUND_NOTIFICATION_ID, notification);
  }

  private static void fireKeepAliveIn(Context ctx, long millis) {
	  if (!TextSecurePreferences.isWebsocketRegistered(ctx))
		  return;
	  Log.Log(TAG + ": setting keep alive timer in " + millis + " millis (" + (millis/(1000*60)) + " minutes)");
	  ctx = ctx.getApplicationContext();
	  AlarmManager alarmMgr = (AlarmManager)ctx.getSystemService(Context.ALARM_SERVICE);
	  Intent bInt = new Intent(ctx, KeepAliveReceiver.class);
	  PendingIntent intent = PendingIntent.getBroadcast(ctx, 0, bInt, PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_UPDATE_CURRENT);
	  alarmMgr.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + millis, intent);
  }

  private static void scheduleKeepAlive(Context ctx) {
	  double rand = 0.9 + Math.random()*.1;
	  int millis = (int)(REQUEST_TIMEOUT_MINUTES*60*1000*rand);
	  fireKeepAliveIn(ctx, millis);
  }

  private void keepAlive(Intent intent) {
	  try {
	    Log.Log(TAG + ": keep alive prod");
		ApplicationContext.getInstance(this).getJobManager().add(new Job(JobParameters.newBuilder()
					.withWakeLock(true)
					.create()) {
			@Override public void onRun() throws Exception {
				TextSecureMessagePipe thePipe;
				synchronized (MessageRetrievalService.this) {
					waitingForReconnect = false;
					thePipe = pipe;
					MessageRetrievalService.this.notifyAll();
				}
				if (thePipe != null) {
					thePipe.sendKeepAlive();
				}
				scheduleKeepAlive(MessageRetrievalService.this);
			}
			@Override public void onCanceled() {}
			@Override public void onAdded() {}
			@Override public boolean onShouldRetry(Exception e) {
				return false;
			}
		});
	  } finally {
		  WakefulBroadcastReceiver.completeWakefulIntent(intent);
	  }
  }

  @Override public void onDestroy() {
	  super.onDestroy();
	  Log.Log(TAG + ": onDestroy");
	  stop.set(true);
	  synchronized (this) {
		  notifyAll();
	  }
	  /*try {
		  thread.join();
	  } catch (InterruptedException e) {
		  Log.w(TAG, "Error joining thread: " + e.getMessage());
	  }*/
  }
}
