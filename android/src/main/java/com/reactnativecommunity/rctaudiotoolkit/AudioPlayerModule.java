package com.reactnativecommunity.rctaudiotoolkit;

import android.content.Context;
import android.content.ContextWrapper;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.LifecycleEventListener;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.bridge.WritableNativeMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class AudioPlayerModule extends ReactContextBaseJavaModule implements
        LifecycleEventListener, AudioManager.OnAudioFocusChangeListener {
    private static final String LOG_TAG = "AudioPlayerModule";

    Map<String, MediaPlayer> playerPool = new HashMap<>();
    Map<String, Callback> playerSeekCallback = new HashMap<>();

    boolean looping = false;
    private ReactApplicationContext context;
    private AudioManager mAudioManager;
    private String lastPlayerId;
    boolean mixWithOthers = false;

    public AudioPlayerModule(ReactApplicationContext reactContext) {
        super(reactContext);
        this.context = reactContext;
        reactContext.addLifecycleEventListener(this);
        this.mAudioManager = (AudioManager) this.context.getSystemService(Context.AUDIO_SERVICE);
        handler=new Handler(Looper.getMainLooper());
        handler.postDelayed(sendEmit(),2000L);
    }
    private final Handler handler;
    private Runnable sendEmit(){

        return new Runnable(){
            @Override
            public void run() {
                Map<String, MediaPlayer> playerPoolCopy = new HashMap<>(playerPool);
                Log.e("sendEmit",String.valueOf(playerPoolCopy.size()));
                for (Map.Entry<String, MediaPlayer> entry : playerPoolCopy.entrySet()) {
                    WritableMap info = getInfo(entry.getValue());
                    WritableMap data = new WritableNativeMap();
                    data.putMap("info", info);
                    emitEvent(entry.getKey(), "interval", data);
                }
                handler.postDelayed(sendEmit(),2000L);
            }
        };
    }

    @Override
    public void onHostResume() {
        // Activity `onResume`
    }

    @Override
    public void onHostPause() {

    }

    @Override
    public void onHostDestroy() {
        // Activity `onDestroy`
        // Need to create a copy here because it is possible for other code to modify playerPool
        // at the same time which will lead to a ConcurrentModificationException being thrown
        Map<String, MediaPlayer> playerPoolCopy = new HashMap<>(this.playerPool);

        for (Map.Entry<String, MediaPlayer> entry : playerPoolCopy.entrySet()) {
            String playerId = entry.getKey();
            entry.getValue().pause();
            entry.getValue().release();
            destroy(playerId);
        }
    }

    @Override
    public void onCatalystInstanceDestroy() {
        super.onCatalystInstanceDestroy();
    }

    @Override
    public String getName() {
        return "RCTAudioPlayer";
    }

    private void emitEvent(String playerId, String event, WritableMap data) {
        WritableMap payload = new WritableNativeMap();
        payload.putString("playerId", playerId);
        payload.putString("event", event);
        payload.putMap("data", data);

        this.context
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                .emit("RCTAudioPlayerEvent:" + playerId, payload);
    }

    private WritableMap errObj(final String code, final String message, final boolean enableLog) {
        WritableMap err = Arguments.createMap();

        String stackTraceString = "";
        try {
            StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
            for (StackTraceElement e : stackTrace) {
                stackTraceString += (e != null ? e.toString() : "null") + "\n";
            }
        } catch (Exception e) {
            stackTraceString = "Exception occurred while parsing stack trace";
        }

        err.putString("err", code);
        err.putString("message", message);

        if (enableLog) {
            err.putString("stackTrace", stackTraceString);
            Log.e(LOG_TAG, message);
            Log.d(LOG_TAG, stackTraceString);
        }

        return err;
    }

    private WritableMap errObj(final String code, final String message) {
        return errObj(code, message, true);
    }

    private Uri uriFromPath(String path) {
        File file = null;
        String fileNameWithoutExt;
        String extPath;

        // Try finding file in app data directory
        extPath = new ContextWrapper(this.context).getFilesDir() + "/" + path;
        file = new File(extPath);
        if (file.exists()) {
            return Uri.fromFile(file);
        }

        // Try finding file on sdcard
        extPath = Environment.getExternalStorageDirectory() + "/" + path;
        file = new File(extPath);
        if (file.exists()) {
            return Uri.fromFile(file);
        }

        // Try finding file by full path
        file = new File(path);
        if (file.exists()) {
            return Uri.fromFile(file);
        }
        
        // Try finding file in Android "raw" resources
        if (path.lastIndexOf('.') != -1) {
            fileNameWithoutExt = path.substring(0, path.lastIndexOf('.'));
        } else {
            fileNameWithoutExt = path;
        }

        int resId = this.context.getResources().getIdentifier(fileNameWithoutExt,
            "raw", this.context.getPackageName());
        if (resId != 0) {
            return Uri.parse("android.resource://" + this.context.getPackageName() + "/" + resId);
        }

        // Otherwise pass whole path string as URI and hope for the best
        return Uri.parse(path);
    }

    @ReactMethod
    public void destroy(String playerId, Callback callback) {
        MediaPlayer player = this.playerPool.get(playerId);

        if (player != null) {
            player.release();
            this.playerPool.remove(playerId);
            //this.playerAutoDestroy.remove(playerId);
            //this.playerContinueInBackground.remove(playerId);
            this.playerSeekCallback.remove(playerId);

            WritableMap data = new WritableNativeMap();
            data.putString("message", "Destroyed player");

            emitEvent(playerId, "info", data);
        }

        if (callback != null) {
            callback.invoke();
        }
    }

    private void destroy(String playerId) {
        this.destroy(playerId, null);
    }

    @ReactMethod
    public void seek(final String playerId, Integer position, Callback callback) {
        MediaPlayer player = this.playerPool.get(playerId);
        if (player == null) {
            callback.invoke(errObj("notfound", "playerId " + playerId + " not found."));
            return;
        }

        if (position >= 0) {
            Callback oldCallback = this.playerSeekCallback.get(playerId);

            if (oldCallback != null) {
                oldCallback.invoke(errObj("seekfail", "new seek operation before old one completed", false));
                this.playerSeekCallback.remove(playerId);
            }

            this.playerSeekCallback.put(playerId, callback);
            player.seekTo(position);
        }
    }

    private WritableMap getInfo(MediaPlayer player) {
        WritableMap info = Arguments.createMap();

        info.putDouble("duration", player.getDuration());
        info.putString("durationReadable", convertDurationMillis(player.getDuration()));
        info.putDouble("position", player.getCurrentPosition());
        info.putString("positionReadable", convertDurationMillis(player.getDuration()-player.getCurrentPosition()));
        info.putDouble("audioSessionId", player.getAudioSessionId());

        return info;
    }

    public String convertDurationMillis(Integer getDurationInMillis){
        int getDurationMillis = getDurationInMillis;

        String convertHours = String.format("%02d", TimeUnit.MILLISECONDS.toHours(getDurationMillis));
        String convertMinutes = String.format("%02d", TimeUnit.MILLISECONDS.toMinutes(getDurationMillis) -
                TimeUnit.HOURS.toMinutes(TimeUnit.MILLISECONDS.toHours(getDurationMillis))); //I needed to add this part.
        String convertSeconds = String.format("%02d", TimeUnit.MILLISECONDS.toSeconds(getDurationMillis) -
                TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(getDurationMillis)));


        return convertHours + ":" + convertMinutes + ":" + convertSeconds;

    }
    private ScheduledThreadPoolExecutor executor=null;
    @ReactMethod
    public void prepare(final String playerId, String path,final ReadableMap options, final Callback callback) {
        final MediaPlayer cekPlayer = this.playerPool.get(playerId);
        if (cekPlayer == null) {
            if (path == null || path.isEmpty()) {
                callback.invoke(errObj("nopath", "Provided path was empty"));
                return;
            }
            this.lastPlayerId = playerId;

            Uri uri = uriFromPath(path);

            //MediaPlayer player = MediaPlayer.create(this.context, uri, null, attributes);
            final MediaPlayer player = new MediaPlayer();
            try {
                Log.d(LOG_TAG, uri.getPath());
                player.setDataSource(this.context, uri);
            } catch (IOException e) {
                callback.invoke(errObj("invalidpath", e.toString()));
                return;
            }
            setListener(player,playerId,callback,options);
            try {
                player.prepareAsync();
            } catch (Exception e) {
                callback.invoke(errObj("prepare", e.toString()));
            }
        }else{
            setListener(cekPlayer,playerId,callback,options);
            callback.invoke(null, getInfo(cekPlayer));
        }
    }
    private void setListener(final MediaPlayer player,
                             final String playerId,final Callback callback,final ReadableMap options){
        player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                WritableMap err = new WritableNativeMap();
                err.putInt("what", what);
                err.putInt("extra", extra);
                WritableMap data = new WritableNativeMap();
                data.putMap("err", err);
                data.putString("message", "Android MediaPlayer error");
                emitEvent(playerId, "error", data);
                destroy(playerId);
                return true; // don't call onCompletion listener afterwards
            }
        });
        player.setOnInfoListener(new MediaPlayer.OnInfoListener() {
            @Override
            public boolean onInfo(MediaPlayer mp, int what, int extra) {
                WritableMap info = new WritableNativeMap();
                info.putInt("what", what);
                info.putInt("extra", extra);
                WritableMap data = new WritableNativeMap();
                data.putMap("info", info);
                data.putString("message", "Android MediaPlayer info");

                emitEvent(playerId, "info", data);

                return false;
            }
        });
        player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mp) {
                WritableMap data = new WritableNativeMap();
                WritableMap info = Arguments.createMap();
                info.putDouble("position", mp.getCurrentPosition());
                info.putString("positionReadable", convertDurationMillis(mp.getDuration()-player.getCurrentPosition()));

                data.putMap("info", info);
                mp.seekTo(0);
                if (looping) {
                    mp.start();
                    data.putString("message", "Media playback looped");
                    emitEvent(playerId, "looped", data);
                } else {
                    data.putString("message", "Playback completed");

                    emitEvent(playerId, "ended", data);
                }
            }
        });
        player.setOnSeekCompleteListener(new MediaPlayer.OnSeekCompleteListener() {
            @Override
            public void onSeekComplete(MediaPlayer mp) {
                Callback callback = playerSeekCallback.get(playerId);
                if (callback != null) {
                    callback.invoke(null, getInfo(mp));
                    playerSeekCallback.remove(playerId);
                }
                WritableMap data = new WritableNativeMap();
                data.putString("message", "Seek operation completed");
                emitEvent(playerId, "seeked", data);
            }
        });
        player.setOnBufferingUpdateListener(new MediaPlayer.OnBufferingUpdateListener() {
            @Override
            public void onBufferingUpdate(MediaPlayer mp, int percent) {
                WritableMap data = new WritableNativeMap();
                data.putString("message", "Status update for media stream buffering");
                data.putInt("percent", percent);
                emitEvent(playerId, "progress", data);
            }
        });
        player.setOnPreparedListener(new MediaPlayer.OnPreparedListener() { // Async preparing, so we need to run the callback after preparing has finished
            @Override
            public void onPrepared(MediaPlayer player) {
                AudioPlayerModule.this.playerPool.put(playerId, player);
                AudioPlayerModule.this.mixWithOthers = false;
                if (options.hasKey("mixWithOthers")) {
                    AudioPlayerModule.this.mixWithOthers = options.getBoolean("mixWithOthers");
                }
                callback.invoke(null, getInfo(player));
            }
        });
    }

    @ReactMethod
    public void set(final String playerId, ReadableMap options, Callback callback) {
        MediaPlayer player = this.playerPool.get(playerId);
        if (player == null) {
            callback.invoke(errObj("notfound", "playerId " + playerId + " not found."));
            return;
        }

        if (options.hasKey("wakeLock")) {
            // TODO: can we disable the wake lock also?
            if (options.getBoolean("wakeLock")) {
                player.setWakeMode(this.context, PowerManager.PARTIAL_WAKE_LOCK);
            }
        }

        if (options.hasKey("volume") && !options.isNull("volume")) {
            double vol = options.getDouble("volume");
            player.setVolume((float) vol, (float) vol);
        }

        if (options.hasKey("looping") && !options.isNull("looping")) {
            this.looping = options.getBoolean("looping");
        }

        // `PlaybackParams` was only added in API 23
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && (options.hasKey("speed") || options.hasKey("pitch"))) {
            PlaybackParams params = new PlaybackParams();

            boolean needToPauseAfterSet = false;
            if (options.hasKey("speed") && !options.isNull("speed")) {
                // If the player wasn't already playing, then setting the speed value to a non-zero value
                // will start it playing and we don't want that so we need to make sure to pause it straight
                // after setting the speed value
                boolean wasAlreadyPlaying = player.isPlaying();
                float speedValue = (float) options.getDouble("speed");
                needToPauseAfterSet = !wasAlreadyPlaying && speedValue != 0.0f;

                params.setSpeed(speedValue);
            }

            if (options.hasKey("pitch") && !options.isNull("pitch")) {
                params.setPitch((float) options.getDouble("pitch"));
            }

            player.setPlaybackParams(params);

            if (needToPauseAfterSet) {
                player.pause();
            }
        }

        callback.invoke();
    }

    @ReactMethod
    public void play(final String playerId, Callback callback) {
        MediaPlayer player = this.playerPool.get(playerId);
        if (player == null) {
            callback.invoke(errObj("notfound", "playerId " + playerId + " not found."));
            return;
        }

        try {
            if (!this.mixWithOthers) {
                this.mAudioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
            }
            player.start();

            callback.invoke(null, getInfo(player));
        } catch (Exception e) {
            callback.invoke(errObj("playback", e.toString()));
        }
    }

    @ReactMethod
    public void pause(final String playerId, Callback callback) {
        MediaPlayer player = this.playerPool.get(playerId);
        if (player == null) {
            callback.invoke(errObj("notfound", "playerId " + playerId + " not found."));
            return;
        }

        try {

            player.pause();

            WritableMap info = getInfo(player);

            WritableMap data = new WritableNativeMap();
            data.putString("message", "Playback paused");
            data.putMap("info", info);

            emitEvent(playerId, "pause", data);

            callback.invoke(null, getInfo(player));

        } catch (Exception e) {
            callback.invoke(errObj("pause", e.toString()));
        }
    }

    @ReactMethod
    public void stop(final String playerId, Callback callback) {
        MediaPlayer player = this.playerPool.get(playerId);
        if (player == null) {
            callback.invoke(errObj("notfound", "playerId " + playerId + " not found."));
            return;
        }

        try {

            Callback oldCallback = this.playerSeekCallback.get(playerId);

            if (oldCallback != null) {
                oldCallback.invoke(errObj("seekfail", "Playback stopped before seek operation could finish"));
                this.playerSeekCallback.remove(playerId);
            }

            this.playerSeekCallback.put(playerId, callback);

            player.seekTo(0);
            player.pause();

        } catch (Exception e) {
            callback.invoke(errObj("stop", e.toString()));
        }
    }

    @ReactMethod
    public void getCurrentTime(final String playerId, Callback callback) {
        MediaPlayer player = this.playerPool.get(playerId);
        if (player == null) {
            callback.invoke(errObj("notfound", "playerId " + playerId + " not found."));
            return;
        }

        try {
            callback.invoke(null, getInfo(player));
        } catch (Exception e) {
            callback.invoke(errObj("getCurrentTime", e.toString()));
        }
    }

    // Audio Focus
    public void onAudioFocusChange(int focusChange)
    {
        switch (focusChange)
        {
            case AudioManager.AUDIOFOCUS_LOSS:
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                //MediaPlayer player = this.playerPool.get(this.lastPlayerId);
                WritableMap data = new WritableNativeMap();
                data.putString("message", "Lost audio focus, playback paused");

                this.emitEvent(this.lastPlayerId, "forcePause", data);
                break;
        }
    }


    // Utils
    public static boolean equals(Object a, Object b) {
        return (a == b) || (a != null && a.equals(b));
    }
}
