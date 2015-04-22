package org.thoughtcrime.securesms.service;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import org.thoughtcrime.securesms.ApplicationContext;
import org.thoughtcrime.securesms.dependencies.InjectableType;
import org.thoughtcrime.securesms.gcm.GcmBroadcastReceiver;
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

public class MessageRetrievalService extends Service implements Runnable, InjectableType, RequirementListener {
	private final static int FOREGROUND_NOTIFICATION_ID = 1234;

  private static final String TAG = MessageRetrievalService.class.getSimpleName();

  public static final  String ACTION_KEEPALIVE         = "KEEPALIVE";
  public static final  String ACTION_ACTIVITY_STARTED  = "ACTIVITY_STARTED";
  public static final  String ACTION_ACTIVITY_FINISHED = "ACTIVITY_FINISHED";
  public static final  String ACTION_PUSH_RECEIVED     = "PUSH_RECEIVED";
  private static final long   REQUEST_TIMEOUT_MINUTES  = 1;

  private NetworkRequirement         networkRequirement;
  private NetworkRequirementProvider networkRequirementProvider;

  @Inject
  public TextSecureMessageReceiver receiver;
  private TextSecureMessagePipe pipe;
  private PowerManager.WakeLock wakeLock;

  private int          activeActivities = 0;
  private List<Intent> pushPending      = new LinkedList<>();

  private AtomicBoolean stop = new AtomicBoolean(false);
  private Thread thread;

  @Override
  public void onCreate() {
    super.onCreate();
	registerForeground();
    ApplicationContext.getInstance(this).injectDependencies(this);

    networkRequirement         = new NetworkRequirement(this);
    networkRequirementProvider = new NetworkRequirementProvider(this);

	PowerManager powerManager = (PowerManager)getSystemService(Context.POWER_SERVICE);
	wakeLock     = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MessageRetrieval");

    networkRequirementProvider.setListener(this);

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

  private TextSecureMessagePipe createPipe() {
	  TextSecureMessagePipe thePipe = receiver.createMessagePipe();
	  return thePipe;
  }

  @Override
  public void run() {
	  wakeLock.acquire();
	  try {
		  doRun();
	  } finally {
    	  Log.w(TAG, "Exiting ws thread...");
		  wakeLock.release();
	  }
  }

  private void doRun() {
    while (!stop.get()) {
      Log.w(TAG, "Waiting for websocket state change....");
      waitForConnectionNecessary();

      Log.w(TAG, "Making websocket connection....");
	  TextSecureMessagePipe thePipe = createPipe();
	  synchronized (this) {
	      pipe = thePipe;
	  }
	  if (thePipe == null)
		  continue;

      try {
        while (isConnectionNecessary() && !stop.get()) {
          try {
            Log.w(TAG, "Reading message...");
            thePipe.read(REQUEST_TIMEOUT_MINUTES, TimeUnit.MINUTES,
                      new TextSecureMessagePipe.MessagePipeCallback() {
                        @Override
                        public void onMessage(TextSecureEnvelope envelope) {
							if (stop.get())
								return;
                          Log.w(TAG, "Retrieved envelope! " + envelope.getSource());

                          PushContentReceiveJob receiveJob = new PushContentReceiveJob(MessageRetrievalService.this);
                          receiveJob.handle(envelope, false);

                          decrementPushReceived();
                        }
						@Override public void sleep() {
							Log.i(TAG, "releasing wakelock");
							wakeLock.release();
						}
						@Override public void wakeup() {
							Log.i(TAG, "acquiring wakelock");
							wakeLock.acquire();
						}
                      });
          } catch (TimeoutException e) {
            Log.w(TAG, "Application level read timeout...");
          } catch (InvalidVersionException e) {
            Log.w(TAG, e);
          }
        }
      } catch (Throwable e) {
        Log.w(TAG, e);
      } finally {
        Log.w(TAG, "Shutting down pipe...");
        shutdown(thePipe);
      }

      Log.w(TAG, "Looping...");
    }
  }

  @Override
  public void onRequirementStatusChanged() {
    synchronized (this) {
      notifyAll();
    }
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  private synchronized void incrementActive() {
    activeActivities++;
    Log.w(TAG, "Active Count: " + activeActivities);
    notifyAll();
  }

  private synchronized void decrementActive() {
    activeActivities--;
    Log.w(TAG, "Active Count: " + activeActivities);
    notifyAll();
  }

  private synchronized void incrementPushReceived(Intent intent) {
    pushPending.add(intent);
    notifyAll();
  }

  private synchronized void decrementPushReceived() {
    if (!pushPending.isEmpty()) {
      Intent intent = pushPending.remove(0);
      GcmBroadcastReceiver.completeWakefulIntent(intent);
      notifyAll();
    }
  }

  private synchronized boolean isConnectionNecessary() {
    Log.w(TAG, String.format("Network requirement: %s, active activities: %s, push pending: %s",
                             networkRequirement.isPresent(), activeActivities, pushPending.size()));

    return TextSecurePreferences.isWebsocketRegistered(this) &&
           (true/*activeActivities > 0*/ || !pushPending.isEmpty())  &&
           networkRequirement.isPresent();
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
      Log.w(TAG, t);
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

  private void registerForeground() {
	  Notification notification = new NotificationCompat.Builder(this)
		  .setSmallIcon(org.thoughtcrime.securesms.R.drawable.icon)
		  .setPriority(Notification.PRIORITY_LOW)
		  .setOngoing(true)
		  .setWhen(0)
		  .setContentTitle(getString(org.thoughtcrime.securesms.R.string.foreground_websocket_title))
		  .setContentText(getString(org.thoughtcrime.securesms.R.string.foreground_websocket_text))
		  .getNotification();
	  startForeground(FOREGROUND_NOTIFICATION_ID, notification);
  }

  public static void startKeepAliveAlarm(Context ctx) {
	  AlarmManager alarmMgr = (AlarmManager)ctx.getSystemService(Context.ALARM_SERVICE);
	  PendingIntent intent = PendingIntent.getBroadcast(ctx, 0, new Intent(ctx, KeepAliveReceiver.class), 0);
	  long duration = REQUEST_TIMEOUT_MINUTES*60*1000;
	  alarmMgr.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP, 0, duration, intent);
  }

  private void keepAlive(Intent intent) {
	  try {
		ApplicationContext.getInstance(this).getJobManager().add(new Job(JobParameters.newBuilder()
					.withWakeLock(true)
					.create()) {
			@Override public void onRun() throws Exception {
				synchronized (MessageRetrievalService.this) {
					if (pipe != null) {
						pipe.sendKeepAlive();
						Log.i(TAG, "Sent keep alive");
					}
				}
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
	  stop.set(true);
	  /*try {
		  thread.join();
	  } catch (InterruptedException e) {
		  Log.w(TAG, "Error joining thread: " + e.getMessage());
	  }*/
  }
}
