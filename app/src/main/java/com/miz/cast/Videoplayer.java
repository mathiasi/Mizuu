package com.miz.cast;

import android.net.Uri;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.view.Menu;
import android.view.MenuItem;

import com.afollestad.easyvideoplayer.EasyVideoCallback;
import com.afollestad.easyvideoplayer.EasyVideoPlayer;
import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaMetadata;
import com.google.android.gms.cast.framework.CastButtonFactory;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.SessionManagerListener;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;
import com.google.android.gms.common.images.WebImage;
import com.miz.mizuu.R;

public class Videoplayer extends AppCompatActivity implements EasyVideoCallback {

    private String URL;
    private EasyVideoPlayer player;
    private CastContext mCastContext;
    private MenuItem mediaRouteMenuItem;
    private CastSession mCastSession;
    private SessionManagerListener<CastSession> mSessionManagerListener;
    private PlaybackState mPlaybackState = PlaybackState.IDLE;
    private PlaybackLocation mLocation = PlaybackLocation.LOCAL;

    public enum PlaybackState {
        PLAYING, PAUSED, BUFFERING, IDLE
    }

    public enum PlaybackLocation {
        LOCAL,
        REMOTE
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.videoplayer_layout);
        Toolbar myToolbar = (Toolbar) findViewById(R.id.my_toolbar);
        setSupportActionBar(myToolbar);

        setupCastListener();
        mCastContext = CastContext.getSharedInstance(this);
        mCastContext.registerLifecycleCallbacksBeforeIceCreamSandwich(this, savedInstanceState);
        mCastSession = mCastContext.getSessionManager().getCurrentCastSession();

        mCastContext.getSessionManager().addSessionManagerListener(mSessionManagerListener, CastSession.class);

        // Grabs a reference to the player view
        player = (EasyVideoPlayer) findViewById(R.id.player);
        if(player == null){
            throw new RuntimeException("Player not found");
        }

        // Sets the callback to this Activity, since it inherits EasyVideoCallback
        player.setCallback(this);

        String videoUrl = getIntent().getStringExtra("videoUrl");
        if(videoUrl != null){
            URL = videoUrl;
        }

        // Sets the source to the HTTP URL held in the URL variable.
        // To play files, you can use Uri.fromFile(new File("..."))
        player.setSource(Uri.parse(URL));

        // From here, the player view will show a progress indicator until the player is prepared.
        // Once it's prepared, the progress indicator goes away and the controls become enabled for the user to begin playback.

    }

    @Override
    public void onPause() {
        super.onPause();
        // Make sure the player stops playing if the user presses the home button.
        player.pause();
    }

    // Methods for the implemented EasyVideoCallback

    @Override
    public void onPreparing(EasyVideoPlayer player) {
        mPlaybackState = PlaybackState.BUFFERING;
    }

    @Override
    public void onPrepared(EasyVideoPlayer player) {
        mPlaybackState = PlaybackState.PAUSED;
    }

    @Override
    public void onBuffering(int percent) {
        mPlaybackState = PlaybackState.BUFFERING;
    }

    @Override
    public void onError(EasyVideoPlayer player, Exception e) {
        mPlaybackState = PlaybackState.IDLE;
    }

    @Override
    public void onCompletion(EasyVideoPlayer player) {
        mPlaybackState = PlaybackState.IDLE;
    }

    @Override
    public void onRetry(EasyVideoPlayer player, Uri source) {
        // TODO handle if used
    }

    @Override
    public void onSubmit(EasyVideoPlayer player, Uri source) {
        // TODO handle if used
    }

    @Override
    public void onStarted(EasyVideoPlayer player) {
        mPlaybackState = PlaybackState.PLAYING;
    }

    @Override
    public void onPaused(EasyVideoPlayer player) {
        mPlaybackState = PlaybackState.PAUSED;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.video_player,menu);
        mediaRouteMenuItem = CastButtonFactory.setUpMediaRouteButton(getApplicationContext(), menu, R.id.media_route_menu_item);
        return true;
    }

    private void setupCastListener() {
        mSessionManagerListener = new SessionManagerListener<CastSession>() {

            @Override
            public void onSessionEnded(CastSession session, int error) {
                onApplicationDisconnected();
            }

            @Override
            public void onSessionResumed(CastSession session, boolean wasSuspended) {
                onApplicationConnected(session);
            }

            @Override
            public void onSessionResumeFailed(CastSession session, int error) {
                onApplicationDisconnected();
            }

            @Override
            public void onSessionStarted(CastSession session, String sessionId) {
                onApplicationConnected(session);
            }

            @Override
            public void onSessionStartFailed(CastSession session, int error) {
                onApplicationDisconnected();
            }

            @Override
            public void onSessionStarting(CastSession session) {}

            @Override
            public void onSessionEnding(CastSession session) {}

            @Override
            public void onSessionResuming(CastSession session, String sessionId) {}

            @Override
            public void onSessionSuspended(CastSession session, int reason) {}

            private void onApplicationConnected(CastSession castSession) {
                mCastSession = castSession;
                //if (null != mSelectedMedia) {
                if (null != URL) {

                    if (mPlaybackState == PlaybackState.PLAYING) {
                        //mVideoView.pause();
                        player.pause();
                        //loadRemoteMedia(mSeekbar.getProgress(), true);
                        loadRemoteMedia(player.getCurrentPosition(), true);
                        finish();
                        return;
                    } else {
                        mPlaybackState = PlaybackState.IDLE;
                        updatePlaybackLocation(PlaybackLocation.REMOTE);
                    }
                }
                //updatePlayButton(mPlaybackState);
                invalidateOptionsMenu();
            }

            private void onApplicationDisconnected() {
                updatePlaybackLocation(PlaybackLocation.LOCAL);
                mPlaybackState = PlaybackState.IDLE;
                mLocation = PlaybackLocation.LOCAL;
                //updatePlayButton(mPlaybackState);
                invalidateOptionsMenu();
            }
        };
    }

    private void updatePlaybackLocation(PlaybackLocation location) {

    }

    private void loadRemoteMedia(int position, boolean autoPlay) {
        if (mCastSession == null) {
            return;
        }
        RemoteMediaClient remoteMediaClient = mCastSession.getRemoteMediaClient();
        if (remoteMediaClient == null) {
            return;
        }
        remoteMediaClient.load(buildMediaInfo(), autoPlay, position);
    }

    private MediaInfo buildMediaInfo() {
        MediaMetadata movieMetadata = new MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE);

        movieMetadata.putString(MediaMetadata.KEY_SUBTITLE, "Subtitle");
        movieMetadata.putString(MediaMetadata.KEY_TITLE, "Title");

        //return new MediaInfo.Builder(mSelectedMedia.getUrl())
        return new MediaInfo.Builder(URL)
                .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
                .setContentType("videos/*")
                .setMetadata(movieMetadata)
                //.setStreamDuration(mSelectedMedia.getDuration() * 1000)
                .setStreamDuration(player.getDuration())
                .build();
    }
}
