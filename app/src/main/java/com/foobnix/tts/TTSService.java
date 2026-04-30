package com.foobnix.tts;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.PendingIntent.CanceledException;
import android.app.Service;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.AudioManager.OnAudioFocusChangeListener;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeech.OnUtteranceCompletedListener;
import android.speech.tts.UtteranceProgressListener;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.view.KeyEvent;

import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;
import androidx.media.session.MediaButtonReceiver;

import com.foobnix.LibreraApp;
import com.foobnix.android.utils.Apps;
import com.foobnix.android.utils.LOG;
import com.foobnix.android.utils.TxtUtils;
import com.foobnix.android.utils.Vibro;
import com.foobnix.model.AppBook;
import com.foobnix.model.AppProfile;
import com.foobnix.model.AppSP;
import com.foobnix.model.AppState;
import com.foobnix.pdf.info.R;
import com.foobnix.pdf.info.model.BookCSS;
import com.foobnix.pdf.info.wrapper.DocumentController;
import com.foobnix.sys.ImageExtractor;
import com.foobnix.sys.TempHolder;

import org.ebookdroid.common.settings.books.SharedBooks;
import org.ebookdroid.core.codec.CodecDocument;
import org.ebookdroid.core.codec.CodecPage;
import org.greenrobot.eventbus.EventBus;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

@TargetApi(Build.VERSION_CODES.O) public class TTSService extends Service {
    public static final String EXTRA_PATH = "EXTRA_PATH";
    public static final String EXTRA_ANCHOR = "EXTRA_ANCHOR";
    public static final String EXTRA_INT = "INT";
    private static final String TAG = "TTSService";
    public static String ACTION_PLAY_CURRENT_PAGE = "ACTION_PLAY_CURRENT_PAGE";
    private final BroadcastReceiver blueToothReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            LOG.d("blueToothReceiver", intent);
            stopMediaSessionAndReleaseWakeLock();
            TTSNotification.showLast();
        }
    };
    int width;
    int height;
    AudioManager mAudioManager;
    MediaSessionCompat mMediaSessionCompat;
    boolean isActivated;
    public static boolean isPlaying;
    Object audioFocusRequest;
    volatile boolean isStartForeground = false;
    // Skip one stale onDone after manual next/prev.
    private boolean suppressAutoAdvanceOnce = false;
    CodecDocument cache;
    String path;
    int wh;
    int emptyPageCount = 0;
    final OnAudioFocusChangeListener listener = new OnAudioFocusChangeListener() {
        @Override public void onAudioFocusChange(int focusChange) {
            LOG.d("onAudioFocusChange", focusChange);
            if (AppState.get().isEnableAccessibility) {
                return;
            }

            if (!AppState.get().stopReadingOnCall) {
                return;
            }
            if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                LOG.d("Ingore Duck");
                return;
            }

            if (focusChange < 0) {
                isPlaying = TTSEngine.get()
                                     .isPlaying();
                LOG.d("onAudioFocusChange", "Is playing", isPlaying);
                stopMediaSessionAndReleaseWakeLock();
                TTSNotification.showLast();
            } else {
                if (isPlaying) {
                    playPage("", AppSP.get().lastBookPage, null);
                }
            }
        }
    };
    private WakeLock wakeLock;
    private static final long WAKE_LOCK_TIMEOUT = 2 * 60 * 1000L; // 2 min timeout per page

    {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(
                                                                                                   new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                                                                                                                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                                                                                                                .setLegacyStreamType(AudioManager.STREAM_MUSIC)
                                                                                                                                .build())
                                                                                           .setAcceptsDelayedFocusGain(
                                                                                                   true)
                                                                                           .setWillPauseWhenDucked(
                                                                                                   false)
                                                                                           .setOnAudioFocusChangeListener(
                                                                                                   listener)
                                                                                           .build();
        }
    }

    public TTSService() {
        LOG.d(TAG, "Create constructor");
    }

    public static void playLastBook() {
        playBookPage(AppSP.get().lastBookPage, AppSP.get().lastBookPath, "", AppSP.get().lastBookWidth,
                AppSP.get().lastBookHeight, AppSP.get().lastFontSize, AppSP.get().lastBookTitle);
    }

    public static void updateTimer() {
        TempHolder.get().timerFinishTime = System.currentTimeMillis() + AppState.get().ttsTimer * 60 * 1000;
        LOG.d("Update-timer", TempHolder.get().timerFinishTime, AppState.get().ttsTimer);
    }

    public static void openSettingsIntent(Context a) {
        TTSEngine.get()
                 .stop();
        TTSEngine.get()
                 .stopDestroy();

        Intent intent = new Intent();
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.setAction("com.android.settings.TTS_SETTINGS");
        a.startActivity(intent);
    }

    public static boolean isTTSGranted(Context context) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {

            if (ActivityCompat.shouldShowRequestPermissionRationale((Activity) context,
                    Manifest.permission.POST_NOTIFICATIONS)) {

                try {
                    Intent intent = new Intent();
                    intent.setAction(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                    intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName());
                    context.startActivity(intent);
                } catch (Exception e) {
                    LOG.e(e);
                }
            } else {
                ActivityCompat.requestPermissions((Activity) context,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, 11);
            }
            return false;
        }
        if (TTSEngine.get()
                     .isInit() && TTSEngine.get()
                                           .getCurrentLang()
                                           .equals("---")) {
            openSettingsIntent(context);
            return false;
        }
        return true;
    }

    public static void playPause(Context context, DocumentController controller) {

        if (!isTTSGranted(context)) {
            return;
        }

        if (TTSEngine.get()
                     .isPlaying()) {
            PendingIntent next = PendingIntent.getService(context, 0,
                    new Intent(TTSNotification.TTS_PAUSE, null, context, TTSService.class),
                    PendingIntent.FLAG_IMMUTABLE);
            try {
                next.send();
            } catch (CanceledException e) {
                LOG.d(e);
            }
        } else {
            if (controller != null) {
                TTSService.playBookPage(controller.getCurentPageFirst1() - 1, controller.getCurrentBook()
                                                                                        .getPath(), "",
                        controller.getBookWidth(), controller.getBookHeight(), BookCSS.get().fontSizeSp,
                        controller.getTitle());
            }
        }
    }

    @TargetApi(26)
    public static void playBookPage(int page, String path, String anchor, int width, int height, int fontSize,
                                    String title) {
        LOG.d(TAG, "playBookPage1", page, path, width, height);

        TTSEngine.get()
                 .stop();

        AppSP.get().lastBookWidth = width;
        AppSP.get().lastBookHeight = height;
        AppSP.get().lastFontSize = fontSize;
        AppSP.get().lastBookTitle = title;
        AppSP.get().lastBookPage = page;

        Intent intent = playBookIntent(page, path, anchor);
        //UserDefinedFileAttributeView
//        PendingIntent play = PendingIntent.getService(LibreraApp.context, 0, intent, PendingIntent.FLAG_IMMUTABLE);
//        try {
//            play.send();
//        } catch (CanceledException e) {
//            LOG.e(e);
//        }

        if (Build.VERSION.SDK_INT >= 26) {
            LibreraApp.context.startForegroundService(intent);
        } else {
            LibreraApp.context.startService(intent);
        }
    }

    private static Intent playBookIntent(int page, String path, String anchor) {
        Intent intent = new Intent(LibreraApp.context, TTSService.class);
        intent.setAction(TTSService.ACTION_PLAY_CURRENT_PAGE);
        intent.putExtra(EXTRA_INT, page);
        intent.putExtra(EXTRA_PATH, path);
        intent.putExtra(EXTRA_ANCHOR, anchor);
        return intent;
    }

    @Override public void onCreate() {
        LOG.d(TAG, "onCreate:TTS playBookPage1");
        //startMyForeground();
        //

        //try without wakeLock
        PowerManager myPowerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = myPowerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Librera:TTSServiceLock");
        wakeLock.setReferenceCounted(false);

        AppProfile.init(getApplicationContext());

        mAudioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        //mAudioManager.setRingerMode(AudioManager.RINGER_MODE_VIBRATE);

        Intent mediaButtonIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
        PendingIntent pendingIntent1 =
                PendingIntent.getBroadcast(getApplicationContext(), 0, mediaButtonIntent, PendingIntent.FLAG_IMMUTABLE);

        mMediaSessionCompat = new MediaSessionCompat(getApplicationContext(), "Tag", null, pendingIntent1);
        TTSNotification.sessionToken = mMediaSessionCompat.getSessionToken();
        mMediaSessionCompat.setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mMediaSessionCompat.setCallback(new MediaSessionCompat.Callback() {
            @Override public boolean onMediaButtonEvent(Intent intent) {
                if (intent == null || intent.getExtras() == null) {
                    return super.onMediaButtonEvent(intent);
                }
                KeyEvent event = (KeyEvent) intent.getExtras().get(Intent.EXTRA_KEY_EVENT);
                if (event == null) {
                    return super.onMediaButtonEvent(intent);
                }

                isPlaying = TTSService.isPlaying;

                LOG.d(TAG, "onMediaButtonEvent", "isActivated", isActivated, "isPlaying", isPlaying, "event", event);

                final List<Integer> list =
                        Arrays.asList(KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                                KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE);

                if (KeyEvent.ACTION_DOWN == event.getAction()) {
                    final int keyCode = event.getKeyCode();
                    if (list.contains(keyCode)) {
                        LOG.d(TAG, "onMediaButtonEvent", "isPlaying", isPlaying, "isFastBookmarkByTTS",
                                AppState.get().isFastBookmarkByTTS);

                        if (AppState.get().isFastBookmarkByTTS) {
                            if (isPlaying) {
                                TTSEngine.get()
                                         .fastTTSBookmakr(getBaseContext(), AppSP.get().lastBookPath,
                                                 AppSP.get().lastBookPage + 1, AppSP.get().lastBookPageCount);
                            } else {
                                playPage("", AppSP.get().lastBookPage, null);
                            }
                        } else {
                            if (isPlaying) {
                                stopMediaSessionAndReleaseWakeLock();
                            } else {
                                playPage("", AppSP.get().lastBookPage, null);
                            }
                        }
                    } else if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT ||
                               keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD ||
                               keyCode == KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD) {
                        onSkipToNext();
                    } else if (keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS ||
                               keyCode == KeyEvent.KEYCODE_MEDIA_REWIND ||
                               keyCode == KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD) {
                        onSkipToPrevious();
                    }
                }

                EventBus.getDefault()
                        .post(new TtsStatus());
                TTSNotification.showLast();
                return true;
            }

            // Handles notification and headset media controls.
            @Override public void onPlay() {
                LOG.d(TAG, "MediaSession onPlay");
                isPlaying = true;
                if (handleMp3Play()) {
                    return;
                }
                playPage("", AppSP.get().lastBookPage, null);
                updatePlaybackState();
                TTSNotification.showLast();
                EventBus.getDefault().post(new TtsStatus());
            }

            @Override public void onPause() {
                LOG.d(TAG, "MediaSession onPause");
                isPlaying = false;
                if (handleMp3Pause()) {
                    return;
                }
                stopMediaSessionAndReleaseWakeLock();
                TTSNotification.showLast();
                EventBus.getDefault().post(new TtsStatus());
            }

            @Override public void onStop() {
                LOG.d(TAG, "MediaSession onStop");
                isPlaying = false;
                stopMediaSessionAndReleaseWakeLock();
                TTSNotification.showLast();
                EventBus.getDefault().post(new TtsStatus());
            }

            @Override public void onSkipToNext() {
                LOG.d(TAG, "MediaSession onSkipToNext");
                if (TTSEngine.get().isMp3()) {
                    TTSEngine.get().mp3Next();
                    updatePlaybackState();
                    return;
                }
                suppressAutoAdvanceOnce = true;
                AppSP.get().lastBookParagraph = 0;
                playPage("", AppSP.get().lastBookPage + 1, null);
            }

            @Override public void onSkipToPrevious() {
                LOG.d(TAG, "MediaSession onSkipToPrevious");
                if (TTSEngine.get().isMp3()) {
                    TTSEngine.get().mp3Prev();
                    updatePlaybackState();
                    return;
                }
                suppressAutoAdvanceOnce = true;
                AppSP.get().lastBookParagraph = 0;
                playPage("", AppSP.get().lastBookPage - 1, null);
            }
        });

        // Route media button broadcasts to this MediaSession.
        Intent mbIntent = new Intent(this, MediaButtonReceiver.class);
        mbIntent.setAction(Intent.ACTION_MEDIA_BUTTON);
        PendingIntent mbPendingIntent = PendingIntent.getBroadcast(this, 0, mbIntent, PendingIntent.FLAG_IMMUTABLE);
        mMediaSessionCompat.setMediaButtonReceiver(mbPendingIntent);

        // Keep session visible to system controls before first playback.
        mMediaSessionCompat.setActive(true);
        updatePlaybackState();

        //setSessionToken(mMediaSessionCompat.getSessionToken());

        TTSEngine.get()
                 .getTTS();

        if (Build.VERSION.SDK_INT >= 24) {
            MediaPlayer mp = new MediaPlayer();
            try {
                final AssetFileDescriptor afd = getAssets().openFd("silence.mp3");
                mp.setDataSource(afd);
                mp.prepareAsync();
                mp.start();
                mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                    @Override public void onCompletion(MediaPlayer mp) {
                        try {
                            afd.close();
                        } catch (IOException e) {
                            LOG.e(e);
                        }
                    }
                });

                LOG.d("silence");
            } catch (IOException e) {
                LOG.d("silence error");
                LOG.e(e);
            }
        }

        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECT_REQUESTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        registerReceiver(blueToothReceiver, filter);
    }

    private void updatePlaybackState() {
        if (mMediaSessionCompat == null) return;
        
        long actions = PlaybackStateCompat.ACTION_PLAY_PAUSE |
                       PlaybackStateCompat.ACTION_PLAY |
                       PlaybackStateCompat.ACTION_PAUSE |
                       PlaybackStateCompat.ACTION_STOP |
                       PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
                       PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS;

        PlaybackStateCompat.Builder stateBuilder = new PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(isPlaying ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED,
                          PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1.0f);

        mMediaSessionCompat.setPlaybackState(stateBuilder.build());
    }

    private boolean handleMp3Toggle() {
        if (!TTSEngine.get().isMp3()) {
            return false;
        }
        TTSEngine.get().isMp3PlayPause();
        isPlaying = TTSEngine.get().isPlaying();
        updatePlaybackState();
        TTSNotification.showLast();
        return true;
    }

    private boolean handleMp3Play() {
        if (!TTSEngine.get().isMp3()) {
            return false;
        }
        TTSEngine.get().loadMP3(BookCSS.get().mp3BookPathGet());
        TTSEngine.get().playMp3();
        isPlaying = true;
        updatePlaybackState();
        TTSNotification.showLast();
        return true;
    }

    private boolean handleMp3Pause() {
        if (!TTSEngine.get().isMp3()) {
            return false;
        }
        TTSEngine.get().pauseMp3();
        isPlaying = false;
        updatePlaybackState();
        TTSNotification.showLast();
        return true;
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    public boolean startMyForeground() {
        if (!isStartForeground) {
            startServiceWithNotification();
            isStartForeground = true;
        }
        return isStartForeground;
    }

    private void startServiceWithNotification() {
        PendingIntent stopDestroy = PendingIntent.getService(this, 0,
                new Intent(TTSNotification.TTS_STOP_DESTROY, null, this, TTSService.class),
                PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new NotificationCompat.Builder(this, TTSNotification.DEFAULT) //
                                                                                                  .setSmallIcon(
                                                                                                          R.drawable.glyphicons_smileys_100_headphones) //
                                                                                                  .setContentTitle(
                                                                                                          Apps.getApplicationName(
                                                                                                                  this)) //
                                                                                                  .setContentText(
                                                                                                          getString(
                                                                                                                  R.string.please_wait))
                                                                                                  .addAction(
                                                                                                          R.drawable.glyphicons_599_menu_close,
                                                                                                          getString(
                                                                                                                  R.string.stop),
                                                                                                          stopDestroy)//
                                                                                                  .setPriority(
                                                                                                          NotificationCompat.PRIORITY_DEFAULT)//
                                                                                                  .build();

//        if (Build.VERSION.SDK_INT >= 29) {
//            startForeground(TTSNotification.NOT_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
//        } else {
//            startForeground(TTSNotification.NOT_ID, notification);
//
//        }
        ServiceCompat.startForeground(this, TTSNotification.NOT_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
    }

    public static boolean isServiceRunning(Class<?> serviceClass, Context context) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.getName()
                            .equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1) @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        startMyForeground();

        LOG.d(TAG, "onStartCommand", intent);
        if (intent == null) {
            return START_STICKY;
        }

        updateTimer();
        // Only media button intents should be re-dispatched to MediaSession.
        if (Intent.ACTION_MEDIA_BUTTON.equals(intent.getAction())) {
            MediaButtonReceiver.handleIntent(mMediaSessionCompat, intent);
        }

        LOG.d(TAG, "onStartCommand", intent.getAction());
        if (intent.getExtras() != null) {
            LOG.d(TAG, "onStartCommand", intent.getAction(), intent.getExtras());
            for (String key : intent.getExtras()
                                    .keySet()) {
                LOG.d(TAG, key, "=>", intent.getExtras()
                                            .get(key));
            }
        }

        if (TTSNotification.TTS_STOP_DESTROY.equals(intent.getAction())) {
            TTSEngine.get()
                     .mp3Destroy();
            BookCSS.get()
                   .mp3BookPath(null);
            AppState.get().mp3seek = 0;
            stopMediaSessionAndReleaseWakeLock();

            TTSEngine.get()
                     .stopDestroy();

            EventBus.getDefault()
                    .post(new TtsStatus());

            TTSNotification.hideNotification();
            stopForeground(true);
            stopSelf();

            return START_STICKY;
        }

        if (TTSNotification.TTS_PLAY_PAUSE.equals(intent.getAction())) {

            if (handleMp3Toggle()) {
                return START_STICKY;
            }

            if (TTSEngine.get()
                         .isPlaying()) {
                stopMediaSessionAndReleaseWakeLock();
            } else {
                playPage("", AppSP.get().lastBookPage, null);
            }
            TTSNotification.showLast();
        }
        if (TTSNotification.TTS_PAUSE.equals(intent.getAction())) {

            if (handleMp3Pause()) {
                return START_STICKY;
            }

            stopMediaSessionAndReleaseWakeLock();
            TTSNotification.showLast();
        }

        if (TTSNotification.TTS_PLAY.equals(intent.getAction())) {

            if (handleMp3Play()) {
                return START_STICKY;
            }

            playPage("", AppSP.get().lastBookPage, null);
            TTSNotification.showLast();
        }
        if (TTSNotification.TTS_NEXT.equals(intent.getAction())) {

            if (TTSEngine.get()
                         .isMp3()) {
                TTSEngine.get()
                         .mp3Next();
                updatePlaybackState();
                return START_STICKY;
            }

            AppSP.get().lastBookParagraph = 0;
            playPage("", AppSP.get().lastBookPage + 1, null);
        }
        if (TTSNotification.TTS_PREV.equals(intent.getAction())) {

            if (TTSEngine.get()
                         .isMp3()) {
                TTSEngine.get()
                         .mp3Prev();
                updatePlaybackState();
                return START_STICKY;
            }

            AppSP.get().lastBookParagraph = 0;
            //stopMediaSessionAndReleaseWakeLock();
            playPage("", AppSP.get().lastBookPage - 1, null);
        }

        if (ACTION_PLAY_CURRENT_PAGE.equals(intent.getAction())) {
            if (handleMp3Play()) {
                TTSNotification.show(AppSP.get().lastBookPath, -1, -1);
                return START_STICKY;
            }

            int pageNumber = intent.getIntExtra(EXTRA_INT, -1);
            AppSP.get().lastBookPath = intent.getStringExtra(EXTRA_PATH);
            String anchor = intent.getStringExtra(EXTRA_ANCHOR);

            if (pageNumber != -1) {
                playPage("", pageNumber, anchor);
            }
        }

        EventBus.getDefault()
                .post(new TtsStatus());

        return START_STICKY;
    }

    private void stopMediaSessionAndReleaseWakeLock() {
        isPlaying = false;
        TTSEngine.get()
                 .stop();
        updatePlaybackState();
        releaseWakeLock();
        EventBus.getDefault()
                .post(new TtsStatus());
    }

    public CodecDocument getDC() {
        try {

            if (AppSP.get().lastBookPath != null && AppSP.get().lastBookPath.equals(
                    path) && cache != null && wh == AppSP.get().lastBookWidth + AppSP.get().lastBookHeight) {
                LOG.d(TAG, "CodecDocument from cache", AppSP.get().lastBookPath);
                return cache;
            }
            if (cache != null) {
                cache.recycle();
                cache = null;
            }
            path = AppSP.get().lastBookPath;
            LOG.d(TAG, "CodecDocument", "loadingCancelled", TempHolder.get().loadingCancelled);
            cache = ImageExtractor.singleCodecContext(AppSP.get().lastBookPath, "");
            if (cache == null) {
                TTSNotification.hideNotification();
                return null;
            }
            cache.getPageCount(AppSP.get().lastBookWidth, AppSP.get().lastBookHeight, BookCSS.get().fontSizeSp);
            wh = AppSP.get().lastBookWidth + AppSP.get().lastBookHeight;
            LOG.d(TAG, "CodecDocument new", AppSP.get().lastBookPath, AppSP.get().lastBookWidth,
                    AppSP.get().lastBookHeight);
            return cache;
        } catch (Exception e) {
            LOG.e(e);
            return null;
        }
    }

    @TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH_MR1)
    private void playPage(String preText, int pageNumber, String anchor) {
        isPlaying = true;
        //releaseWakeLock();
        acquireWakeLock();
        mMediaSessionCompat.setActive(true);
        updatePlaybackState();
        if (!AppState.get().allowOtherMusic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                mAudioManager.requestAudioFocus((AudioFocusRequest) audioFocusRequest);
            } else {
                mAudioManager.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
            }
        }

        LOG.d("playPage", preText, pageNumber, anchor);
        if (pageNumber != -1) {
            isActivated = true;
            EventBus.getDefault()
                    .post(new MessagePageNumber(pageNumber));
            AppSP.get().lastBookPage = pageNumber;
            CodecDocument dc = getDC();
            if (dc == null) {
                LOG.d(TAG, "CodecDocument", "is NULL");
                TTSNotification.hideNotification();
                stopMediaSessionAndReleaseWakeLock();
                return;
            }

            AppSP.get().lastBookPageCount = dc.getPageCount();
            LOG.d(TAG, "CodecDocument PageCount", pageNumber, AppSP.get().lastBookPageCount);
            if (pageNumber >= AppSP.get().lastBookPageCount) {
                Vibro.vibrateFinish();
                LOG.d(TAG, "CodecDocument Book is Finished");
                EventBus.getDefault()
                        .post(new TtsStatus());

                stopMediaSessionAndReleaseWakeLock();
                stopSelf();
                return;
            }

            CodecPage page = dc.getPage(pageNumber);
            if(page==null){
                EventBus.getDefault()
                        .post(new TtsStatus());

                stopMediaSessionAndReleaseWakeLock();
                stopSelf();
                return;
            }
            String pageHTML = page.getPageHTML();
            page.recycle();
            pageHTML = TxtUtils.replaceHTMLforTTS(pageHTML);

            if (TxtUtils.isNotEmpty(anchor)) {
                int indexOf = pageHTML.indexOf(anchor);
                if (indexOf > 0) {
                    pageHTML = pageHTML.substring(indexOf);
                    LOG.d("find anchor new text", pageHTML);
                }
            }

            LOG.d(TAG, pageHTML);

            if (TxtUtils.isEmpty(pageHTML)) {
                LOG.d("empty page play next one", emptyPageCount);
                emptyPageCount++;
                if (emptyPageCount < 3) {
                    playPage("", AppSP.get().lastBookPage + 1, null);
                }
                return;
            }
            emptyPageCount = 0;

            String[] parts = TxtUtils.getParts(pageHTML);
            String firstPart =
                    pageNumber + 1 >= AppSP.get().lastBookPageCount || AppState.get().ttsTunnOnLastWord ? pageHTML :
                            parts[0];
            final String secondPart =
                    pageNumber + 1 >= AppSP.get().lastBookPageCount || AppState.get().ttsTunnOnLastWord ? "" : parts[1];

            if (TxtUtils.isNotEmpty(preText)) {
                char last = preText.charAt(preText.length() - 1);
                if (last == '-') {
                    preText = TxtUtils.replaceLast(preText, "-", "");
                    firstPart = preText + firstPart;
                } else {
                    firstPart = preText + " " + firstPart;
                }
            }
            final String preText1 = preText;

            if (Build.VERSION.SDK_INT >= 15) {
                TTSEngine.get()
                         .getTTS()
                         .setOnUtteranceProgressListener(new UtteranceProgressListener() {
                             @Override public void onStart(String utteranceId) {
                                 LOG.d(TAG, "onUtteranceCompleted onStart", utteranceId);
                             }

                             @Override public void onError(String utteranceId) {
                                 LOG.d(TAG, "onUtteranceCompleted onError", utteranceId);
                                 if (!utteranceId.equals(TTSEngine.UTTERANCE_ID_DONE)) {
                                     return;
                                 }
                                 stopMediaSessionAndReleaseWakeLock();
                                 EventBus.getDefault()
                                         .post(new TtsStatus());
                             }

                             @Override public void onDone(String utteranceId) {

                                 LOG.d(TAG, "onUtteranceCompleted", utteranceId);
                                 updatePlaybackState();
                                 if (utteranceId.startsWith(TTSEngine.STOP_SIGNAL)) {
                                     stopMediaSessionAndReleaseWakeLock();

                                     return;
                                 }
                                 if (utteranceId.startsWith(TTSEngine.FINISHED_SIGNAL)) {
                                     if (TxtUtils.isNotEmpty(preText1)) {
                                         AppSP.get().lastBookParagraph =
                                                 Integer.parseInt(utteranceId.replace(TTSEngine.FINISHED_SIGNAL, ""));
                                     } else {
                                         AppSP.get().lastBookParagraph = Integer.parseInt(
                                                 utteranceId.replace(TTSEngine.FINISHED_SIGNAL, "")) + 1;
                                     }
                                     return;
                                 }

                                 if (!utteranceId.equals(TTSEngine.UTTERANCE_ID_DONE)) {
                                     LOG.d(TAG, "onUtteranceCompleted skip", utteranceId);
                                     return;
                                 }
                                if (suppressAutoAdvanceOnce) {
                                    suppressAutoAdvanceOnce = false;
                                    LOG.d(TAG, "onDone: suppress auto-advance after manual skip");
                                    return;
                                }

                                 if (System.currentTimeMillis() > TempHolder.get().timerFinishTime) {
                                     LOG.d(TAG, "Update-timer-Stop1");
                                     stopMediaSessionAndReleaseWakeLock();
                                     stopSelf();
                                     return;
                                 }

                                 AppSP.get().lastBookParagraph = 0;
                                 playPage(secondPart, AppSP.get().lastBookPage + 1, null);
                             }
                         });
            } else {
                TTSEngine.get()
                         .getTTS()
                         .setOnUtteranceCompletedListener(new OnUtteranceCompletedListener() {
                             @Override public void onUtteranceCompleted(String utteranceId) {
                                 if (utteranceId.startsWith(TTSEngine.STOP_SIGNAL)) {
                                     stopMediaSessionAndReleaseWakeLock();

                                     return;
                                 }
                                 if (utteranceId.startsWith(TTSEngine.FINISHED_SIGNAL)) {
                                     if (TxtUtils.isNotEmpty(preText1)) {
                                         AppSP.get().lastBookParagraph =
                                                 Integer.parseInt(utteranceId.replace(TTSEngine.FINISHED_SIGNAL, ""));
                                     } else {
                                         AppSP.get().lastBookParagraph = Integer.parseInt(
                                                 utteranceId.replace(TTSEngine.FINISHED_SIGNAL, "")) + 1;
                                     }
                                     return;
                                 }

                                 if (!utteranceId.equals(TTSEngine.UTTERANCE_ID_DONE)) {
                                     LOG.d(TAG, "onUtteranceCompleted skip", "");
                                     return;
                                 }
                                if (suppressAutoAdvanceOnce) {
                                    suppressAutoAdvanceOnce = false;
                                    LOG.d(TAG, "onDone: suppress auto-advance after manual skip");
                                    return;
                                }

                                 LOG.d(TAG, "onUtteranceCompleted", utteranceId);
                                 if (System.currentTimeMillis() > TempHolder.get().timerFinishTime) {
                                     LOG.d(TAG, "Update-timer-Stop2");
                                     stopMediaSessionAndReleaseWakeLock();
                                     stopSelf();
                                     return;
                                 }
                                 AppSP.get().lastBookParagraph = 0;
                                 playPage(secondPart, AppSP.get().lastBookPage + 1, null);
                             }
                         });
            }

            TTSEngine.get()
                     .speek(firstPart);

            TTSNotification.show(AppSP.get().lastBookPath, pageNumber + 1, dc.getPageCount());
            LOG.d("TtsStatus send");
            EventBus.getDefault()
                    .post(new TtsStatus());

            TTSNotification.showLast();

            new Thread(() -> {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                }
                AppBook load = SharedBooks.load(AppSP.get().lastBookPath);
                load.currentPageChanged(pageNumber + 1, AppSP.get().lastBookPageCount);

                SharedBooks.saveAsync(load);
                AppProfile.save(this);
            }, "@T TTS Save").start();
        }
    }

    @Override public void onDestroy() {
        super.onDestroy();

        isStartForeground = false;
        try {
            unregisterReceiver(blueToothReceiver);
        } catch (Exception e) {
            LOG.e(e);
        }

        stopMediaSessionAndReleaseWakeLock();
        TTSEngine.get()
                 .shutdown();

        TTSNotification.hideNotification();

        isActivated = false;

        //mAudioManager.abandonAudioFocus(listener);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mAudioManager.abandonAudioFocusRequest((AudioFocusRequest) audioFocusRequest);
        } else {
            mAudioManager.abandonAudioFocus(listener);
        }

        //mMediaSessionCompat.setCallback(null);
        mMediaSessionCompat.setActive(false);
        mMediaSessionCompat.release();
        TTSNotification.sessionToken = null;

        if (cache != null) {
            cache.recycle();
        }
        path = null;
        LOG.d(TAG, "onDestroy:TTS playBookPage1");
    }

    Object lock = new Object();

    private void acquireWakeLock() {
        try {

            if (wakeLock != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    wakeLock.acquire(WAKE_LOCK_TIMEOUT);
                } else {
                    wakeLock.acquire();
                }
                LOG.d(TAG, "WakeLock acquired");
            }

        } catch (Exception e) {
            LOG.e(e);
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null) {
                wakeLock.release();
                LOG.d(TAG, "WakeLock released");
            }
        } catch (Exception e) {
            LOG.e(e);
        }
    }
}
