package io.flutter.darwinvtomy.plugins.drm_wv_fp_player;

// Copyright 2017 The Chromium Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.


import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.os.Build;
import android.util.Base64;
import android.util.Log;
import android.util.LongSparseArray;
import android.util.Pair;
import android.view.Surface;
import android.view.SurfaceView;
import android.view.ViewGroup;

import androidx.annotation.NonNull;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.LoadControl;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.Player.EventListener;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.audio.AudioAttributes;
import com.google.android.exoplayer2.drm.DefaultDrmSessionManager;
import com.google.android.exoplayer2.drm.DrmInitData;
import com.google.android.exoplayer2.drm.DrmSession;
import com.google.android.exoplayer2.drm.ExoMediaCrypto;
import com.google.android.exoplayer2.drm.ExoMediaDrm;
import com.google.android.exoplayer2.drm.FrameworkMediaCrypto;
import com.google.android.exoplayer2.drm.FrameworkMediaDrm;
import com.google.android.exoplayer2.drm.HttpMediaDrmCallback;
import com.google.android.exoplayer2.drm.OfflineLicenseHelper;
import com.google.android.exoplayer2.drm.UnsupportedDrmException;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.dash.DashMediaSource;
import com.google.android.exoplayer2.source.dash.DashUtil;
import com.google.android.exoplayer2.source.dash.DefaultDashChunkSource;
import com.google.android.exoplayer2.source.dash.manifest.DashManifest;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.source.smoothstreaming.DefaultSsChunkSource;
import com.google.android.exoplayer2.source.smoothstreaming.SsMediaSource;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultAllocator;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSourceFactory;
import com.google.android.exoplayer2.upstream.HttpDataSource;
import com.google.android.exoplayer2.util.Util;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry.Registrar;
import io.flutter.view.TextureRegistry;

import static android.content.Context.MODE_PRIVATE;
import static com.google.android.exoplayer2.Player.REPEAT_MODE_ALL;
import static com.google.android.exoplayer2.Player.REPEAT_MODE_OFF;

public class DrmWvFpPlayerPlugin implements MethodCallHandler {
    private static final String TAG = "VideoPlayerPlugin";
    private static ExoMediaDrm.Provider<FrameworkMediaCrypto> mediaDrm;
    private final static String PREF_NAME = "MOVIDONE_EXOPLAYER";
    private static String OFFLINE_KEY_ID = "OFFLINE_KEY_ID";
    private static OfflineLicenseHelper<FrameworkMediaCrypto> mOfflineLicenseHelper;

    private static class VideoPlayer {

        private SimpleExoPlayer exoPlayer;

        private SurfaceView surfaceView;

        private final TextureRegistry.SurfaceTextureEntry textureEntry;

        private QueuingEventSink eventSink = new QueuingEventSink();

        private final EventChannel eventChannel;

        private boolean isInitialized = false;

        VideoPlayer(
                Context context,
                EventChannel eventChannel,
                TextureRegistry.SurfaceTextureEntry textureEntry,
                String dataSource,
                Result result) {
            this.eventChannel = eventChannel;
            this.textureEntry = textureEntry;

            DefaultRenderersFactory renderersFactory = new DefaultRenderersFactory(context);
            exoPlayer = new SimpleExoPlayer.Builder(context, renderersFactory).build();

            Uri uri = Uri.parse(dataSource);
            OFFLINE_KEY_ID = Base64.getUrlEncoder().encodeToString(uri.toString().getBytes(), Base64.DEFAULT)

            DataSource.Factory dataSourceFactory;
            if (isFileOrAsset(uri)) {
                dataSourceFactory = new DefaultDataSourceFactory(context, "ExoPlayer");
            } else {
                dataSourceFactory =
                        new DefaultHttpDataSourceFactory(
                                "ExoPlayer",
                                null,
                                DefaultHttpDataSource.DEFAULT_CONNECT_TIMEOUT_MILLIS,
                                DefaultHttpDataSource.DEFAULT_READ_TIMEOUT_MILLIS,
                                true);
            }

            MediaSource mediaSource = buildMediaSource(uri, null, dataSourceFactory, null, context);
            Log.e(TAG, "VideoPlayer: URI LINK "+uri.toString() );
            exoPlayer.prepare(mediaSource);

            setupVideoPlayer(context, eventChannel, textureEntry, result);
        }

        VideoPlayer(
                Context context,
                EventChannel eventChannel,
                TextureRegistry.SurfaceTextureEntry textureEntry,
                MediaContent mediaContent,
                Result result) {
            this.eventChannel = eventChannel;
            this.textureEntry = textureEntry;
            DefaultDrmSessionManager<ExoMediaCrypto> drmSessionManager = null;
            //Add Custom DRM Management
            HttpDataSource.Factory httpDataSourceFactory = new DefaultHttpDataSourceFactory(Util.getUserAgent(context, "ExoPlayerDemo"));

            if (mediaContent.drm_scheme!=null) {
                String drmLicenseUrl = mediaContent.drm_license_url;//WIDEVINE EXAMPLE
                Uri uri = Uri.parse(mediaContent.uri);//WIDEVINE EXAMPLE
                String[] keyRequestPropertiesArray =
                        null;
                boolean multiSession = false;
                String errorStringId = "An unknown DRM error occurred";
                if (Util.SDK_INT < 18) {
                    errorStringId = "Protected content not supported on API levels below 18";
                } else {
                    try {
                        UUID drmSchemeUuid = Util.getDrmUuid("widevine");

                        if (drmSchemeUuid == null) {
                            errorStringId = "This device does not support the required DRM scheme";
                        } else {
                            drmSessionManager = getDrmSessionManager(httpDataSourceFactory, drmSchemeUuid, uri, drmLicenseUrl, keyRequestPropertiesArray, multiSession, context);
                        }
                    } catch (UnsupportedDrmException e) {
                        errorStringId = e.reason == UnsupportedDrmException.REASON_UNSUPPORTED_SCHEME
                                ? "This device does not support the required DRM scheme" : "An unknown DRM error occurred";
                    }
                }
                if (drmSessionManager == null) {
                    Log.e(TAG, "VideoPlayer: DRM ERROR "+errorStringId );
                    return;
                }
            }

            LoadControl loadControl = new DefaultLoadControl.Builder()
                    .setAllocator(new DefaultAllocator(true, 16))
                    .setBufferDurationsMs(Config.MIN_BUFFER_DURATION,
                            Config.MAX_BUFFER_DURATION,
                            Config.MIN_PLAYBACK_START_BUFFER,
                            Config.MIN_PLAYBACK_RESUME_BUFFER)
                    .setTargetBufferBytes(-1)
                    .setPrioritizeTimeOverSizeThresholds(true).createDefaultLoadControl();

            DefaultRenderersFactory renderersFactory =
                    new DefaultRenderersFactory(context);
            exoPlayer =
                    new SimpleExoPlayer.Builder(context, renderersFactory).setLoadControl(loadControl).build();
           // exoPlayer = ExoPlayerFactory.newSimpleInstance(context, trackSelector);
            Uri uri = Uri.parse(mediaContent.uri);

            DataSource.Factory dataSourceFactory;
            if (isFileOrAsset(uri)) {
                dataSourceFactory = new DefaultDataSourceFactory(context, "ExoPlayer");
            } else {
                dataSourceFactory =
                        new DefaultHttpDataSourceFactory(
                                "ExoPlayer",
                                null,
                                DefaultHttpDataSource.DEFAULT_CONNECT_TIMEOUT_MILLIS,
                                DefaultHttpDataSource.DEFAULT_READ_TIMEOUT_MILLIS,
                                true);
            }
            MediaSource mediaSource = buildMediaSource(uri, mediaContent.extension, dataSourceFactory, drmSessionManager, context);
            exoPlayer.prepare(mediaSource);

            setupVideoPlayer(context, eventChannel, textureEntry, result);
        }

        private static boolean isFileOrAsset(Uri uri) {
            if (uri == null || uri.getScheme() == null) {
                return false;
            }
            String scheme = uri.getScheme();
            return scheme.equals("file") || scheme.equals("asset");
        }

        private MediaSource buildMediaSource(
                Uri uri, String extension, DataSource.Factory mediaDataSourceFactory, DefaultDrmSessionManager<ExoMediaCrypto> drmSessionManager, Context context) {
            @C.ContentType int contenttype = Util.inferContentType(uri, extension);
            Log.e(TAG, "buildMediaSource: CONTENT TYPE "+contenttype  );
            int type = Util.inferContentType(Objects.requireNonNull(uri.getLastPathSegment()));
            Log.e(TAG, "buildMediaSource: THe  CONTENT TYPE "+type  );
            switch (contenttype) {
                case C.TYPE_SS:
                    SsMediaSource.Factory sSMediaSourceFactory = new SsMediaSource.Factory(
                            new DefaultSsChunkSource.Factory(mediaDataSourceFactory),
                            new DefaultDataSourceFactory(context, null, mediaDataSourceFactory));
                    if(drmSessionManager != null)
                        sSMediaSourceFactory.setDrmSessionManager(drmSessionManager);
                    return sSMediaSourceFactory.createMediaSource(uri);
                case C.TYPE_DASH:
                    DashMediaSource.Factory dAshMediaSourceFactory = new DashMediaSource.Factory(
                            new DefaultDashChunkSource.Factory(mediaDataSourceFactory),
                            new DefaultDataSourceFactory(context, null, mediaDataSourceFactory));
                    if(drmSessionManager != null)
                        dAshMediaSourceFactory.setDrmSessionManager(drmSessionManager);
                    return dAshMediaSourceFactory.createMediaSource(uri);
                case C.TYPE_HLS:
                    HlsMediaSource.Factory hLsMediaSourceFactory = new HlsMediaSource.Factory(mediaDataSourceFactory);
                    if(drmSessionManager != null)
                        hLsMediaSourceFactory.setDrmSessionManager(drmSessionManager);
                    return hLsMediaSourceFactory.createMediaSource(uri);
                case C.TYPE_OTHER:
                    return new ProgressiveMediaSource.Factory(mediaDataSourceFactory)
                            .createMediaSource(uri);
                default: {
                    throw new IllegalStateException("Unsupported type: " + type);
                }
            }
        }

        private void setupVideoPlayer(
                Context context,
                EventChannel eventChannel,
                TextureRegistry.SurfaceTextureEntry textureEntry,
                Result result) {

            eventChannel.setStreamHandler(
                    new EventChannel.StreamHandler() {
                        @Override
                        public void onListen(Object o, EventChannel.EventSink sink) {
                            eventSink.setDelegate(sink);
                        }

                        @Override
                        public void onCancel(Object o) {
                            eventSink.setDelegate(null);
                        }
                    });

            surfaceView = new SurfaceView(context);
            surfaceView.setLayoutParams(new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            exoPlayer.setVideoSurfaceView(surfaceView);
            exoPlayer.setVideoSurfaceHolder(surfaceView.getHolder());
            exoPlayer.setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
            exoPlayer.setPlayWhenReady(true);
            exoPlayer.getPlaybackState();
            setAudioAttributes(exoPlayer);

            exoPlayer.addListener(
                    new EventListener() {

                        @Override
                        public void onPlayerStateChanged(final boolean playWhenReady, final int playbackState) {
                            if (playbackState == Player.STATE_BUFFERING) {
                                sendBufferingUpdate();
                            } else if (playbackState == Player.STATE_READY) {
                                if (!isInitialized) {
                                    isInitialized = true;
                                    sendInitialized();
                                }
                            } else if (playbackState == Player.STATE_ENDED) {
                                Map<String, Object> event = new HashMap<>();
                                event.put("event", "completed");
                                eventSink.success(event);
                            }
                        }

                        @Override
                        public void onPlayerError(final ExoPlaybackException error) {
                            if (eventSink != null) {
                                eventSink.error("VideoError", "Video player had error " + error, null);
                            }
                        }
                    });

            Map<String, Object> reply = new HashMap<>();
            reply.put("textureId", textureEntry.id());
            result.success(reply);
        }

        private void sendBufferingUpdate() {
            Map<String, Object> event = new HashMap<>();
            event.put("event", "bufferingUpdate");
            List<? extends Number> range = Arrays.asList(0, exoPlayer.getBufferedPosition());
            // iOS supports a list of buffered ranges, so here is a list with a single range.
            event.put("values", Collections.singletonList(range));
            eventSink.success(event);
        }

        private static void setAudioAttributes(SimpleExoPlayer exoPlayer) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                exoPlayer.setAudioAttributes(
                        new AudioAttributes.Builder().setContentType(C.CONTENT_TYPE_MOVIE).build());
            } else {
                AudioAttributes audioAttributes = new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.CONTENT_TYPE_MUSIC).build();
                exoPlayer.setAudioAttributes(audioAttributes);
            }
        }

        void play() {
            exoPlayer.setPlayWhenReady(true);
        }

        void pause() {
            exoPlayer.setPlayWhenReady(false);
        }

        void setLooping(boolean value) {
            exoPlayer.setRepeatMode(value ? REPEAT_MODE_ALL : REPEAT_MODE_OFF);
        }

        void setVolume(double value) {
            float bracketedValue = (float) Math.max(0.0, Math.min(1.0, value));
            exoPlayer.setVolume(bracketedValue);
        }

        void seekTo(int location) {
            exoPlayer.seekTo(location);
        }

        long getPosition() {
            return exoPlayer.getCurrentPosition();
        }

        private void sendInitialized() {
            if (isInitialized) {
                Map<String, Object> event = new HashMap<>();
                event.put("event", "initialized");
                event.put("duration", exoPlayer.getDuration());

                if (exoPlayer.getVideoFormat() != null) {
                    Format videoFormat = exoPlayer.getVideoFormat();
                    int width = videoFormat.width;
                    int height = videoFormat.height;
                    int rotationDegrees = videoFormat.rotationDegrees;
                    // Switch the width/height if video was taken in portrait mode
                    if (rotationDegrees == 90 || rotationDegrees == 270) {
                        width = exoPlayer.getVideoFormat().height;
                        height = exoPlayer.getVideoFormat().width;
                    }
                    event.put("width", width);
                    event.put("height", height);
                }
                eventSink.success(event);
            }
        }

        void dispose() {
            if (isInitialized) {
                exoPlayer.stop();
            }
            textureEntry.release();
            eventChannel.setStreamHandler(null);
            if (surfaceView != null) {
                surfaceView.invalidate();
            }
            if (exoPlayer != null) {
                exoPlayer.release();
            }
        }
    }

    public static void registerWith(Registrar registrar) {
        final DrmWvFpPlayerPlugin plugin = new DrmWvFpPlayerPlugin(registrar);
        final MethodChannel channel =
                new MethodChannel(registrar.messenger(), "flutter.io/videoPlayer");
        channel.setMethodCallHandler(plugin);
        registrar.addViewDestroyListener(
                view -> {
                    plugin.onDestroy();
                    return false; // We are not interested in assuming ownership of the NativeView.
                });
    }

    private DrmWvFpPlayerPlugin(Registrar registrar) {
        this.registrar = registrar;
        this.videoPlayers = new LongSparseArray<>();
    }

    private final LongSparseArray<VideoPlayer> videoPlayers;

    private final Registrar registrar;

    private void disposeAllPlayers() {
        for (int i = 0; i < videoPlayers.size(); i++) {
            videoPlayers.valueAt(i).dispose();
        }
        videoPlayers.clear();
    }

    private void onDestroy() {
        // The whole FlutterView is being destroyed. Here we release resources acquired for all instances
        // of VideoPlayer. Once https://github.com/flutter/flutter/issues/19358 is resolved this may
        // be replaced with just asserting that videoPlayers.isEmpty().
        // https://github.com/flutter/flutter/issues/20989 tracks this.
        disposeAllPlayers();
    }

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull Result result) {
        TextureRegistry textures = registrar.textures();
        if (textures == null) {
            result.error("no_activity", "video_player plugin requires a foreground activity", null);
            return;
        }
        switch (call.method) {
            case "init":
                disposeAllPlayers();
                break;
            case "create": {
                TextureRegistry.SurfaceTextureEntry handle = textures.createSurfaceTexture();
                EventChannel eventChannel =
                        new EventChannel(
                                registrar.messenger(), "flutter.io/videoPlayer/videoEvents" + handle.id());

                VideoPlayer player;
                if (call.argument("asset") != null) {
                    String assetLookupKey;
                    if (call.argument("package") != null) {
                        assetLookupKey =
                                registrar.lookupKeyForAsset(call.argument("asset"), call.argument("package"));
                    } else {
                        assetLookupKey = registrar.lookupKeyForAsset(call.argument("asset"));
                    }
                    player =
                            new VideoPlayer(
                                    registrar.context(),
                                    eventChannel,
                                    handle,
                                    "asset:///" + assetLookupKey,
                                    result);
                    videoPlayers.put(handle.id(), player);
                } else {
                    if (call.argument("sourcetype") != null) {

                        MediaContent mediaContent = new MediaContent(call.argument("name"),
                                call.argument("uri"),
                                call.argument("extension"),
                                call.argument("drm_scheme"),
                                call.argument("drm_license_url"),
                                call.argument("ad_tag_uri"),
                                null,
                                call.argument("spherical_stereo_mode"));
                        player =
                                new VideoPlayer(
                                        registrar.context(), eventChannel, handle, mediaContent, result);
                        Log.e("DATA_RETRIVAL", "_____________SOURCETYPE EXOMEDIA____________");
                    } else {
                        player =
                                new VideoPlayer(
                                        registrar.context(), eventChannel, handle,
                                        new MediaContent(null,
                                                call.argument("uri"),
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null), result);
                    }
                    videoPlayers.put(handle.id(), player);
                    Log.e("DATA_RETRIVAL", "onMethodCall: " + call.argument("name"));
                    Log.e("DATA_RETRIVAL", "onMethodCall: " + call.argument("drm_scheme"));
                    Log.e("DATA_RETRIVAL", "onMethodCall: " + call.argument("uri"));
                    Log.e("DATA_RETRIVAL", "onMethodCall: " + call.argument("sourcetype"));
                    Log.e("DATA_RETRIVAL", "onMethodCall: " + call.argument("extension"));
                    Log.e("DATA_RETRIVAL", "onMethodCall: " + call.argument("drm_license_url"));
                }
                break;
            }
            default: {
                long textureId = ((Number) Objects.requireNonNull(call.argument("textureId"))).longValue();
                VideoPlayer player = videoPlayers.get(textureId);
                if (player == null) {
                    result.error(
                            "Unknown textureId",
                            "No video player associated with texture id " + textureId,
                            null);
                    return;
                }
                onMethodCall(call, result, textureId, player);
                break;
            }
        }
    }

    private void onMethodCall(MethodCall call, Result result, long textureId, VideoPlayer player) {
        switch (call.method) {
            case "setLooping":
                player.setLooping(call.argument("looping"));
                result.success(null);
                break;
            case "setVolume":
                player.setVolume(call.argument("volume"));
                result.success(null);
                break;
            case "play":
                player.play();
                result.success(null);
                break;
            case "pause":
                player.pause();
                result.success(null);
                break;
            case "seekTo":
                int location = ((Number) Objects.requireNonNull(call.argument("location"))).intValue();
                player.seekTo(location);
                result.success(null);
                break;
            case "position":
                result.success(player.getPosition());
                player.sendBufferingUpdate();
                break;
            case "dispose":
                player.dispose();
                videoPlayers.remove(textureId);
                result.success(null);
                break;
            default:
                result.notImplemented();
                break;
        }
    }

    private static DefaultDrmSessionManager<ExoMediaCrypto> getDrmSessionManager(HttpDataSource.Factory httpDataSourceFactory, UUID uuid, Uri mpdUri, String licenseUrl, String[] keyRequestPropertiesArray, boolean multiSession, Context context) throws UnsupportedDrmException
    {
        // Drm manager
        HttpMediaDrmCallback drmCallback = new HttpMediaDrmCallback(licenseUrl, httpDataSourceFactory);
        if (keyRequestPropertiesArray != null) {
            for (int i = 0; i < keyRequestPropertiesArray.length - 1; i += 2) {
                drmCallback.setKeyRequestProperty(keyRequestPropertiesArray[i],
                        keyRequestPropertiesArray[i + 1]);
            }
        }
        releaseMediaDrm(uuid);
        mediaDrm = uuid1 -> {
            FrameworkMediaDrm fMediaDrm = null;
            try {
                fMediaDrm = FrameworkMediaDrm.newInstance(uuid1);
            } catch (UnsupportedDrmException e) {
                Log.e(TAG, "Unsupported DRM Exception", e);
            }
            assert fMediaDrm != null;
            return fMediaDrm;
        };

        DefaultDrmSessionManager<ExoMediaCrypto> drmSessionManager = new DefaultDrmSessionManager.Builder().setUuidAndExoMediaDrmProvider(uuid, mediaDrm).setMultiSession(multiSession).build(drmCallback);
        // existing key set id
        byte[] offlineKeySetId = getStoredKeySetId(context);
        if (offlineKeySetId == null || !isLicenseValid(offlineKeySetId)){
            new Thread() {


                @Override
                public void run() {
                    {
                        try {
                            DefaultHttpDataSourceFactory httpDataSourceFactory = new DefaultHttpDataSourceFactory(
                                    "ExoPlayer");
                            mOfflineLicenseHelper = OfflineLicenseHelper
                                    .newWidevineInstance(licenseUrl, httpDataSourceFactory);
                            DataSource dataSource = httpDataSourceFactory.createDataSource();
                            DashManifest dashManifest = DashUtil.loadManifest(dataSource,
                                    mpdUri);
                            DrmInitData drmInitData = DashUtil.loadDrmInitData(dataSource, dashManifest.getPeriod(0));
                            assert drmInitData != null;
                            byte[] offlineLicenseKeySetId = mOfflineLicenseHelper.downloadLicense(drmInitData);
                            storeKeySetId(offlineLicenseKeySetId,context);
                            // read license for logging purpose
                            isLicenseValid(offlineLicenseKeySetId);

                            Log.d(TAG,"Licence Download Successful: "+ Arrays.toString(offlineLicenseKeySetId));
                        } catch (Exception e) {
                            Log.e(TAG, "license download failed", e);
                        }
                    }
                }
            }.start();
        }else
        {
            Log.d(TAG, "[LICENSE] Restore offline license");

            // Restores an offline license
            drmSessionManager.setMode(DefaultDrmSessionManager.MODE_PLAYBACK, offlineKeySetId);
        }

        return drmSessionManager;
    }

    private static void storeKeySetId(byte[] keySetId,Context context)
    {
        Log.d(TAG, "[LICENSE] Storing key set id value ... " + Arrays.toString(keySetId));

        if (keySetId != null)
        {
            SharedPreferences sharedPreferences = context.getSharedPreferences(PREF_NAME, MODE_PRIVATE);
            SharedPreferences.Editor editor = sharedPreferences.edit();

            String keySetIdB64 = Base64.encodeToString(keySetId, Base64.DEFAULT);

            // encode in b64 to be able to save byte array
            editor.putString(OFFLINE_KEY_ID, keySetIdB64);
            editor.apply();

            Log.d(TAG, "[LICENSE] Stored keySetId in B64 value :" + keySetIdB64);
        }
    }

    private static byte[] getStoredKeySetId(Context context)
    {
        SharedPreferences sharedPreferences = context.getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        String keySetIdB64 = sharedPreferences.getString(OFFLINE_KEY_ID, null);

        if (keySetIdB64 != null)
        {
            byte[] keysetId =  Base64.decode(keySetIdB64, Base64.DEFAULT);
            Log.d(TAG, "[LICENSE] Stored keySetId in B64 value :" + keySetIdB64);

            return keysetId;
        }

        return null;
    }

    /**
     * Check license validity
     * @param keySetId byte[]
     * @return boolean
     * */
    private static boolean isLicenseValid(byte[] keySetId)
    {
        if (mOfflineLicenseHelper != null && keySetId != null)
        {
            try
            {
                // get license duration
                Pair<Long, Long> licenseDurationRemainingSec = mOfflineLicenseHelper.getLicenseDurationRemainingSec(keySetId);
                long licenseDuration = licenseDurationRemainingSec.first;

                Log.d(TAG, "[LICENSE] Time remaining " + licenseDuration + " sec");
                return licenseDuration > 0;
            }
            catch (DrmSession.DrmSessionException e)
            {
                e.printStackTrace();
                return false;
            }
        }

        return false;
    }

    private static void releaseMediaDrm(UUID uuid) {
        if (mediaDrm != null) {
            mediaDrm.acquireExoMediaDrm(uuid).release();;
            mediaDrm = null;
        }
    }
}
