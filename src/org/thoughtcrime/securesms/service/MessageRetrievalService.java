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
import android.util.Log;

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

import org.whispersystems.libaxolotl.InvalidVersionException;
import org.thoughtcrime.securesms.push.TextSecurePushTrustStore;
import org.whispersystems.textsecure.internal.util.BlacklistingTrustManager;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.InputStream;
import java.io.IOException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.cert.CertificateEncodingException;
import java.util.Collections;
import java.util.ArrayList;
import java.util.Arrays;
import android.os.Handler;
import org.whispersystems.textsecure.internal.websocket.WebSocketProtos;
import com.google.protobuf.InvalidProtocolBufferException;

import go.android.Android;

public class MessageRetrievalService extends Service implements /*Runnable, */InjectableType, RequirementListener {
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
  //private TextSecureMessagePipe pipe;
  private PowerManager.WakeLock wakeLock;
  private PowerManager.WakeLock readWakeLock;
  private Handler handler;
  //private boolean waitingForReconnect;

  private int          activeActivities = 0;
  private List<Intent> pushPending      = new LinkedList<>();

  private AtomicBoolean stop = new AtomicBoolean(false);
  private Android.Pipe pipe;
  //private Thread thread;

  @Override
  public void onCreate() {
    super.onCreate();
	Log.w(TAG, "onCreate!");
    ApplicationContext.getInstance(this).injectDependencies(this);

    networkRequirement         = new NetworkRequirement(this);
    networkRequirementProvider = new NetworkRequirementProvider(this);

	wakeLock = createWakeLock();
	readWakeLock = createWakeLock();
	handler = new Handler();

    networkRequirementProvider.setListener(this);

	doRegisterForeground();
	pipe = initPipe();
    /*thread = new Thread(this, "MessageRetrievalService");
	thread.start();*/
  }

  private PowerManager.WakeLock createWakeLock() {
	  PowerManager powerManager = (PowerManager)getSystemService(Context.POWER_SERVICE);
	  final PowerManager.WakeLock wakeLock     = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MessageRetrieval");
	  wakeLock.setReferenceCounted(false);
	  return wakeLock;
  }

  private Android.WakeLock wrapWakeLock(final PowerManager.WakeLock wakeLock) {
	  return new Android.WakeLock.Stub() {
		  @Override public void Release() {
			  Log.i(TAG, "releasing wakelock");
			  wakeLock.release();
			  registerForeground();
		  }
		  @Override public void Acquire() {
			  Log.i(TAG, "acquiring wakelock");
			  wakeLock.acquire();
			  registerForeground();
		  }
	  };
  }

  private Android.Pipe initPipe() {
	  TextSecurePushTrustStore trustStore = new TextSecurePushTrustStore(this);
	  TrustManager[] trustManagers = BlacklistingTrustManager.createFor(trustStore);
	  List<X509Certificate> issuers = new ArrayList<X509Certificate>();
	  for (TrustManager manager : trustManagers) {
		  issuers.addAll(Arrays.asList(((X509TrustManager)manager).getAcceptedIssuers()));
	  }
	  final Context appCtx = getApplicationContext();
	  Android.Pipe pipe = Android.NewPipe(org.thoughtcrime.securesms.Release.PUSH_URL,
		  wrapWakeLock(wakeLock), wrapWakeLock(readWakeLock),
		  new Android.CredentialsProvider.Stub() {
		  @Override public String User() {
			  return TextSecurePreferences.getLocalNumber(appCtx);
		  }
		  @Override public String Password() {
			  return TextSecurePreferences.getPushServerPassword(appCtx);
		  }
	  }, new Android.Callbacks.Stub() {
		  @Override public byte[] OnMessage(byte[] msg) {
			  return handleMessage(msg);
		  }
		  @Override public byte[] NewKeepAliveMessage() {
			  return WebSocketProtos.WebSocketMessage.newBuilder()
				  .setType(WebSocketProtos.WebSocketMessage.Type.REQUEST)
				  .setRequest(WebSocketProtos.WebSocketRequestMessage.newBuilder()
						  .setId(System.currentTimeMillis())
						  .setPath("/v1/keepalive")
						  .setVerb("GET")
						  .build()).build()
				  .toByteArray();
		  }
		  @Override public void WakeupIn(long nanos) {
			  fireKeepAliveIn(appCtx, nanos/1000000);
		  }
		  @Override public boolean ConnectionRequired() {
			  return isConnectionNecessary();
		  }
	  });
	  for (int i = 0; i < issuers.size(); i++) {
		  X509Certificate cert = issuers.get(i);
		  byte[] encCert;
		  try {
			  encCert = cert.getEncoded();
		  } catch (CertificateEncodingException e) {
			  throw new AssertionError(e);
		  }
		  try {
			  pipe.AddAcceptedCert(encCert);
		  } catch (Exception e) {
			  throw new AssertionError(e);
		  }
	  }
	  pipe.Start();
	  return pipe;
  }

  public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent == null) return START_STICKY;

    if      (ACTION_ACTIVITY_STARTED.equals(intent.getAction()))  incrementActive();
    else if (ACTION_ACTIVITY_FINISHED.equals(intent.getAction())) decrementActive();
    else if (ACTION_PUSH_RECEIVED.equals(intent.getAction()))     incrementPushReceived(intent);
    else if (ACTION_KEEPALIVE.equals(intent.getAction()))         keepAlive(intent);

    return START_STICKY;
  }

  private byte[] handleMessage(byte[] payload) {
	  WebSocketProtos.WebSocketMessage message;
	  try {
		  message = WebSocketProtos.WebSocketMessage.parseFrom(payload);
	  } catch (InvalidProtocolBufferException e) {
		  Log.e(TAG, "failed to decode message: " + e.getMessage());
		  return null;
	  }
	  if (message.getType().getNumber() != WebSocketProtos.WebSocketMessage.Type.REQUEST_VALUE) {
		  return null;
	  }
      WebSocketProtos.WebSocketRequestMessage request = message.getRequest();
      WebSocketProtos.WebSocketResponseMessage response = createWebSocketResponse(request);

	  try {
		  if (isTextSecureEnvelope(request)) {
			  TextSecureEnvelope envelope = new TextSecureEnvelope(request.getBody().toByteArray(),
					  TextSecurePreferences.getSignalingKey(getApplicationContext()));
			  PushContentReceiveJob receiveJob = new PushContentReceiveJob(this);
			  receiveJob.handle(envelope, false);

			  decrementPushReceived();
		  }
	  } catch (IOException | InvalidVersionException e) {
		  Log.e(TAG, "failed to decode message envelope: " + e.getMessage());
	  }
	  WebSocketProtos.WebSocketMessage respMsg = WebSocketProtos.WebSocketMessage.newBuilder()
		  .setType(WebSocketProtos.WebSocketMessage.Type.RESPONSE)
		  .setResponse(response)
		  .build();

	  return respMsg.toByteArray();
  }

  private static boolean isTextSecureEnvelope(WebSocketProtos.WebSocketRequestMessage message) {
    return "PUT".equals(message.getVerb()) && "/api/v1/message".equals(message.getPath());
  }

  private static WebSocketProtos.WebSocketResponseMessage createWebSocketResponse(WebSocketProtos.WebSocketRequestMessage request) {
    if (isTextSecureEnvelope(request)) {
      return WebSocketProtos.WebSocketResponseMessage.newBuilder()
                                     .setId(request.getId())
                                     .setStatus(200)
                                     .setMessage("OK")
                                     .build();
    } else {
      return WebSocketProtos.WebSocketResponseMessage.newBuilder()
                                     .setId(request.getId())
                                     .setStatus(400)
                                     .setMessage("Unknown")
                                     .build();
    }
  }

  /*@Override
  public void run() {
	  acquireWakeLock();
	  try {
		  doRun();
	  } finally {
    	  Log.w(TAG, "Exiting ws thread...");
		  releaseWakeLock();
	  }
  }

  private synchronized void releaseWakeLock() {
	  Log.i(TAG, "releasing wakelock");
	  wakeLock.release();
	  registerForeground();
  }

  private synchronized void acquireWakeLock() {
	  Log.i(TAG, "acquiring wakelock");
	  wakeLock.acquire();
	  registerForeground();
  }

  private synchronized void releaseAndWait() {
	  releaseWakeLock();
	  try {
		  Log.w(TAG, "Waiting for websocket state change....");
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

      Log.w(TAG, "Making websocket connection....");
	  TextSecureMessagePipe thePipe = null;
	  try {
		  thePipe = receiver.createMessagePipe((REQUEST_TIMEOUT_MINUTES + REQUEST_TIMEOUT_JITTER_MINUTES)*60);
	  } catch (IOException e) {
		  Log.w(TAG, "failed to connect: " + e.getMessage());
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
      	  Log.w(TAG, "Setting alarm for reconnect in " + waitMillis + " attempt " + attempt);
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
            Log.w(TAG, "Reading message...");
            thePipe.read(Integer.MAX_VALUE, TimeUnit.MILLISECONDS,
                      new TextSecureMessagePipe.MessagePipeCallback() {
                        @Override
                        public void onMessage(TextSecureEnvelope envelope) {
                          Log.w(TAG, "Retrieved envelope! " + envelope.getSource());

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
  }*/

  @Override
  public synchronized void onRequirementStatusChanged() {
	  //waitingForReconnect = false;
	  wakePipe();
	  //notifyAll();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }

  private synchronized void incrementActive() {
	//waitingForReconnect = false;
    activeActivities++;
    Log.w(TAG, "Active Count: " + activeActivities);
	wakePipe();
    //notifyAll();
  }

  private synchronized void decrementActive() {
    activeActivities--;
    Log.w(TAG, "Active Count: " + activeActivities);
	wakePipe();
    //notifyAll();
  }

  private synchronized void incrementPushReceived(Intent intent) {
    pushPending.add(intent);
	wakePipe();
    //notifyAll();
  }

  private synchronized void decrementPushReceived() {
    if (!pushPending.isEmpty()) {
      Intent intent = pushPending.remove(0);
      //GcmBroadcastReceiver.completeWakefulIntent(intent);
	  wakePipe();
      //notifyAll();
    }
  }

  private synchronized boolean isConnectionNecessary() {
    Log.w(TAG, String.format("Network requirement: %s, active activities: %s, push pending: %s",
                             networkRequirement.isPresent(), activeActivities, pushPending.size()/*, waitingForReconnect*/));

    return TextSecurePreferences.isWebsocketRegistered(this) &&
           (true/*activeActivities > 0*/ || !pushPending.isEmpty())  &&
           networkRequirement.isPresent()/* && !waitingForReconnect*/;
  }

  private synchronized void waitForConnectionNecessary() {
    try {
      while (!isConnectionNecessary()) wait();
    } catch (InterruptedException e) {
      throw new AssertionError(e);
    }
  }

  /*private void shutdown(TextSecureMessagePipe pipe) {
    try {
      pipe.shutdown();
    } catch (Throwable t) {
      Log.w(TAG, t);
    }
  }*/

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
	  handler.post(new Runnable() {
		  @Override public void run() {
			  doRegisterForeground();
		  }
	  });
  }

  private void doRegisterForeground() {
	  Intent launch = new Intent(this, ConversationListActivity.class);
	  PendingIntent intent = PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT);
	  Notification notification = new NotificationCompat.Builder(this)
		  .setSmallIcon(org.thoughtcrime.securesms.R.drawable.icon)
		  .setPriority(Notification.PRIORITY_MIN)
		  .setOngoing(true)
		  .setWhen(0)
		  .setContentIntent(intent)
		  .setContentTitle(getString(org.thoughtcrime.securesms.R.string.foreground_websocket_title))
		  .setContentText(wakeLock.isHeld() || readWakeLock.isHeld() ?
				  getString(org.thoughtcrime.securesms.R.string.foreground_websocket_text)
				  : getString(org.thoughtcrime.securesms.R.string.foreground_websocket_text_idle))
		  .getNotification();
	  startForeground(FOREGROUND_NOTIFICATION_ID, notification);
  }

  private static void fireKeepAliveIn(Context ctx, long millis) {
	  if (!TextSecurePreferences.isWebsocketRegistered(ctx))
		  return;
	  Log.i(TAG, "setting keep alive timer in " + millis);
	  ctx = ctx.getApplicationContext();
	  AlarmManager alarmMgr = (AlarmManager)ctx.getSystemService(Context.ALARM_SERVICE);
	  Intent bInt = new Intent(ctx, KeepAliveReceiver.class);
	  PendingIntent intent = PendingIntent.getBroadcast(ctx, 0, bInt, PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_UPDATE_CURRENT);
	  alarmMgr.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + millis, intent);
  }

/*  private static void scheduleKeepAlive(Context ctx) {
	  double rand = 0.9 + Math.random()*.1;
	  int millis = (int)(REQUEST_TIMEOUT_MINUTES*60*1000*rand);
	  fireKeepAliveIn(ctx, millis);
  }*/

  private void wakePipe() {
	  if (pipe != null) {
		  pipe.Wakeup();
	  }
  }

  private void keepAlive(Intent intent) {
	  try {
/*	  	Log.i(TAG, "Keep alive prod");
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
		});*/
	  	  wakePipe();
	  } finally {
		  WakefulBroadcastReceiver.completeWakefulIntent(intent);
	  }
  }

  @Override public void onDestroy() {
	  super.onDestroy();
	  Log.w(TAG, "onDestroy!");
	  if (pipe != null) {
		  pipe.Shutdown();
		  pipe = null;
	  }
	  /*stop.set(true);
	  synchronized (this) {
		  notifyAll();
	  }*/
	  /*try {
		  thread.join();
	  } catch (InterruptedException e) {
		  Log.w(TAG, "Error joining thread: " + e.getMessage());
	  }*/
  }
}
