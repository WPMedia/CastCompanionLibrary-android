package com.google.sample.castcompanionlibrary.cast.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.support.v4.app.DialogFragment;
import android.support.v4.app.Fragment;
import android.support.v7.app.MediaRouteButton;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaMetadata;
import com.google.android.gms.cast.MediaStatus;
import com.google.android.gms.cast.MediaTrack;
import com.google.sample.castcompanionlibrary.R;
import com.google.sample.castcompanionlibrary.cast.VideoCastManager;
import com.google.sample.castcompanionlibrary.cast.exceptions.CastException;
import com.google.sample.castcompanionlibrary.cast.exceptions.NoConnectionException;
import com.google.sample.castcompanionlibrary.cast.exceptions.TransientNetworkDisconnectionException;
import com.google.sample.castcompanionlibrary.utils.FetchBitmapTask;
import com.google.sample.castcompanionlibrary.utils.LogUtils;
import com.google.sample.castcompanionlibrary.utils.Utils;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import static com.google.sample.castcompanionlibrary.utils.LogUtils.LOGD;
import static com.google.sample.castcompanionlibrary.utils.LogUtils.LOGE;

/**
 * Created by mehtam2 on 8/22/14.
 * <p/>
 * This fragment is forked from VideoCastControllerFragment and VideoCastControllerActivity, with update in specs we have decided to add our fragment to the Main Activity.
 * All logic of establishing communication with CC is moved to our MainActivity.
 */
public class WapoVideoCastControllerFragment extends Fragment implements OnVideoCastControllerListener,
        IMediaAuthListener, IVideoCastController {
    private static final String EXTRAS = "extras";
    private static final String TAG = LogUtils.makeLogTag(WapoVideoCastControllerFragment.class);
    private MediaInfo mSelectedMedia;
    private VideoCastManager mCastManager;
    private IMediaAuthService mMediaAuthService;
    private Thread mAuthThread;
    private Timer mMediaAuthTimer;
    private Handler mHandler;
    protected boolean mAuthSuccess = true;
    private FetchBitmapTask mImageAsyncTask;
    private Timer mSeekbarTimer;
    private int mPlaybackState;
    private OverallState mOverallState = OverallState.UNKNOWN;
    private UrlAndBitmap mUrlAndBitmap;
    private static boolean sDialogCanceled = false;
    private boolean mIsFresh = true;
    private View mPageView;
    private ImageView mPlayPause;
    private TextView mLiveText;
    private TextView mStart;
    private TextView mEnd;
    private SeekBar mSeekbar;
    private TextView mLine1;
    private TextView mLine2;
    private ProgressBar mLoading;
    private View mControllers;
    private Drawable mPauseDrawable;
    private Drawable mPlayDrawable;
    private Drawable mStopDrawable;
    private int mStreamType;
    private ImageView mCastThumbNail;
    private ImageView mClosedCaptionIcon;
    private ImageView blurImage;
    private android.support.v7.app.MediaRouteButton mediaRouteFeatureButton;

    private enum OverallState {
        AUTHORIZING, PLAYBACK, UNKNOWN;
    }

    // ------- Overriding of Fragment interface ----------------- //
    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        sDialogCanceled = false;
        mHandler = new Handler();
        try {
            mCastManager = VideoCastManager.getInstance(activity);
        } catch (CastException e) {
            LOGE(TAG, e.getMessage());
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle bundle = getArguments();
        if (null == bundle) {
            return;
        }
        // Retain this fragment across configuration changes.
        setRetainInstance(true);

    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        super.onCreateView(inflater, container, savedInstanceState);
        return inflater.inflate(R.layout.cast_fragment, container, false);
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        loadAndSetupViews();
        setHasOptionsMenu(true);
        Bundle bundle = getArguments();
        Bundle extras = bundle.getBundle(EXTRAS);
        Bundle mediaWrapper = extras.getBundle(VideoCastManager.EXTRA_MEDIA);

        if (extras.getBoolean(VideoCastManager.EXTRA_HAS_AUTH)) {
            mOverallState = OverallState.AUTHORIZING;
            mMediaAuthService = mCastManager.getMediaAuthService();
            handleMediaAuthTask(mMediaAuthService);
            showImage(Utils.getImageUri(mMediaAuthService.getMediaInfo(), 0));
        } else if (null != mediaWrapper) {
            mOverallState = OverallState.PLAYBACK;
            boolean shouldStartPlayback = extras.getBoolean(VideoCastManager.EXTRA_SHOULD_START);
            String customDataStr = extras.getString(VideoCastManager.EXTRA_CUSTOM_DATA);
            JSONObject customData = null;
            if (!TextUtils.isEmpty(customDataStr)) {
                try {
                    customData = new JSONObject(customDataStr);
                } catch (JSONException e) {
                    LOGE(TAG, "Failed to unmarshalize custom data string: customData="
                            + customDataStr, e);
                }
            }
            MediaInfo info = Utils.toMediaInfo(mediaWrapper);
            int startPoint = extras.getInt(VideoCastManager.EXTRA_START_POINT, 0);
            onReady(info, shouldStartPlayback, startPoint, customData);
        }
    }

    /*
     *  Initialize Views
     */
    private void loadAndSetupViews() {
        mediaRouteFeatureButton = (android.support.v7.app.MediaRouteButton)getView().findViewById(R.id.castConnectedButton);
        mPauseDrawable = getResources().getDrawable(R.drawable.ic_av_pause_dark);
        mPlayDrawable = getResources().getDrawable(R.drawable.ic_av_play_dark);
        mStopDrawable = getResources().getDrawable(R.drawable.ic_av_stop_dark);
        mPageView = getView().findViewById(R.id.pageView);
        mPlayPause = (ImageView) getView().findViewById(R.id.imageView1);
        mLiveText = (TextView) getView().findViewById(R.id.liveText);
        mStart = (TextView) getView().findViewById(R.id.startText);
        mEnd = (TextView) getView().findViewById(R.id.endText);
        mSeekbar = (SeekBar) getView().findViewById(R.id.seekBar1);
        mLine1 = (TextView) getView().findViewById(R.id.textView1);
        mLine2 = (TextView) getView().findViewById(R.id.textView2);
        mLoading = (ProgressBar) getView().findViewById(R.id.progressBar1);
        mControllers = getView().findViewById(R.id.controllers);
        mCastThumbNail = (ImageView) getView().findViewById(R.id.castThumbNail);
        mClosedCaptionIcon = (ImageView) getView().findViewById(R.id.cc);
        blurImage = (ImageView) getView().findViewById(R.id.blurImg);
        updateClosedCaption(CC_DISABLED);
        mPlayPause.setOnClickListener(new View.OnClickListener() {

            @Override
            public void onClick(View v) {
                try {
                    onPlayPauseClicked(v);
                } catch (TransientNetworkDisconnectionException e) {
                    LOGE(TAG, "Failed to toggle playback due to temporary network issue", e);
                    Utils.showErrorDialog(getActivity(),
                            R.string.failed_no_connection_trans);
                } catch (NoConnectionException e) {
                    LOGE(TAG, "Failed to toggle playback due to network issues", e);
                    Utils.showErrorDialog(getActivity(),
                            R.string.failed_no_connection);
                } catch (Exception e) {
                    LOGE(TAG, "Failed to toggle playback due to other issues", e);
                    Utils.showErrorDialog(getActivity(),
                            R.string.failed_perform_action);
                }
            }
        });

        mSeekbar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                try {
                    WapoVideoCastControllerFragment.this.onStopTrackingTouch(seekBar);
                } catch (Exception e) {
                    LOGE(TAG, "Failed to complete seek", e);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                try {
                    WapoVideoCastControllerFragment.this.onStartTrackingTouch(seekBar);
                } catch (Exception e) {
                    LOGE(TAG, "Failed to start seek", e);
                }
            }

            @Override
            public void onProgressChanged(SeekBar seekBar, int progress,
                                          boolean fromUser) {
                mStart.setText(Utils.formatMillis(progress));
                try {
                    WapoVideoCastControllerFragment.this.onProgressChanged(seekBar, progress, fromUser);
                } catch (Exception e) {
                    LOGE(TAG, "Failed to set teh progress result", e);
                }
            }
        });
    }

    /*
     * Starts a background thread for starting the Auth Service
     */
    private void handleMediaAuthTask(final IMediaAuthService authService) {
        showLoading(true);
        setLine2(null != authService.getPendingMessage()
                ? authService.getPendingMessage() : "");
        mAuthThread = new Thread(new Runnable() {

            @Override
            public void run() {
                if (null != authService) {
                    try {
                        authService.setOnResult(WapoVideoCastControllerFragment.this);
                        authService.start();
                    } catch (Exception e) {
                        LOGE(TAG, "mAuthService.start() encountered exception", e);
                        mAuthSuccess = false;
                    }
                }
            }
        });
        mAuthThread.start();

        // start a timeout timer; we don't want authorization process to take too long
        mMediaAuthTimer = new Timer();
        mMediaAuthTimer.schedule(new MediaAuthServiceTimerTask(mAuthThread),
                authService.getTimeout());
    }

    /*
     * A TimerTask that will be called when the timeout timer expires
     */
    class MediaAuthServiceTimerTask extends TimerTask {

        private final Thread mThread;

        public MediaAuthServiceTimerTask(Thread thread) {
            this.mThread = thread;
        }

        @Override
        public void run() {
            if (null != mThread) {
                LOGD(TAG, "Timer is expired, going to interrupt the thread");
                mThread.interrupt();
                mHandler.post(new Runnable() {

                    @Override
                    public void run() {
                        showLoading(false);
                        showErrorDialog(getString(R.string.failed_authorization_timeout));
                        mAuthSuccess = false;
                        if (null != mMediaAuthService
                                && mMediaAuthService.getStatus() == MediaAuthStatus.PENDING) {
                            mMediaAuthService.abort(MediaAuthStatus.ABORT_TIMEOUT);
                        }
                    }
                });

            }
        }
    }

    private class UpdateSeekbarTask extends TimerTask {

        @Override
        public void run() {
            mHandler.post(new Runnable() {

                @Override
                public void run() {
                    int currentPos = 0;
                    if (mPlaybackState == MediaStatus.PLAYER_STATE_BUFFERING) {
                        return;
                    }
                    if (!mCastManager.isConnected()) {
                        return;
                    }
                    try {
                        int duration = (int) mCastManager.getMediaDuration();
                        if (duration > 0) {
                            try {
                                currentPos = (int) mCastManager.getCurrentMediaPosition();
                                updateSeekbar(currentPos, duration);
                            } catch (Exception e) {
                                LOGE(TAG, "Failed to get current media position", e);
                            }
                        }
                    } catch (TransientNetworkDisconnectionException e) {
                        LOGE(TAG, "Failed to update the progress bar due to network issues", e);
                    } catch (NoConnectionException e) {
                        LOGE(TAG, "Failed to update the progress bar due to network issues", e);
                    }

                }
            });
        }
    }

    public void onReady(MediaInfo mediaInfo, boolean shouldStartPlayback, int startPoint,
                        JSONObject customData) {
        mSelectedMedia = mediaInfo;
        updateClosedCaptionState();
        try {
            setStreamType(mSelectedMedia.getStreamType());
            if (shouldStartPlayback) {
                // need to start remote playback
                mPlaybackState = MediaStatus.PLAYER_STATE_BUFFERING;
                setPlaybackStatus(mPlaybackState);
                mCastManager.loadMedia(mSelectedMedia, true, startPoint, customData);
            } else {
                // we don't change the status of remote playback
                if (mCastManager.isRemoteMoviePlaying()) {
                    mPlaybackState = MediaStatus.PLAYER_STATE_PLAYING;
                } else {
                    mPlaybackState = MediaStatus.PLAYER_STATE_PAUSED;
                }
                setPlaybackStatus(mPlaybackState);
            }
        } catch (Exception e) {
            LOGE(TAG, "Failed to get playback and media information", e);
        }
        updateMetadata();
        restartTrickplayTimer();
    }

    private void updateClosedCaptionState() {
        int state = IVideoCastController.CC_HIDDEN;
        if (mCastManager != null && mCastManager.isFeatureEnabled(VideoCastManager.FEATURE_CAPTIONS_PREFERENCE)
                && mSelectedMedia != null
                && mCastManager.getTracksPreferenceManager().isCaptionEnabled()) {
            List<MediaTrack> tracks = mSelectedMedia.getMediaTracks();
            state = tracks == null || tracks.isEmpty() ? IVideoCastController.CC_DISABLED
                    : IVideoCastController.CC_ENABLED;
        }
        updateClosedCaption(state);
    }

    private void stopTrickplayTimer() {
        LOGD(TAG, "Stopped TrickPlay Timer");
        if (null != mSeekbarTimer) {
            mSeekbarTimer.cancel();
        }
    }

    private void restartTrickplayTimer() {
        stopTrickplayTimer();
        mSeekbarTimer = new Timer();
        mSeekbarTimer.scheduleAtFixedRate(new UpdateSeekbarTask(), 100, 1000);
        LOGD(TAG, "Restarted TrickPlay Timer");
    }

    private void updateOverallState() {
        IMediaAuthService authService;
        switch (mOverallState) {
            case AUTHORIZING:
                authService = mCastManager.getMediaAuthService();
                if (null != authService) {
                    setLine2(null != authService.getPendingMessage()
                            ? authService.getPendingMessage() : "");
                    showLoading(true);
                }
                break;
            case PLAYBACK:
                // nothing yet, may be needed in future
                break;
            default:
                break;
        }
    }

    private void updateMetadata() {
        Uri imageUrl = null;
        if (null == mSelectedMedia) {
            if (null != mMediaAuthService) {
                imageUrl = Utils.getImageUri(mMediaAuthService.getMediaInfo(), 0);
            }
        } else {
            imageUrl = Utils.getImageUri(mSelectedMedia, 0);
        }
        showImage(imageUrl);
        if (null == mSelectedMedia) {
            return;
        }
        MediaMetadata mm = mSelectedMedia.getMetadata();
        setLine1(null != mm.getString(MediaMetadata.KEY_TITLE)
                ? mm.getString(MediaMetadata.KEY_TITLE) : "");
        boolean isLive = mSelectedMedia.getStreamType() == MediaInfo.STREAM_TYPE_LIVE;
        adjustControllersForLiveStream(isLive);
    }

    private void updatePlayerStatus() {
        int mediaStatus = mCastManager.getPlaybackStatus();
        LOGD(TAG, "updatePlayerStatus(), state: " + mediaStatus);
        if (null == mSelectedMedia) {
            return;
        }
        setStreamType(mSelectedMedia.getStreamType());
        if (mediaStatus == MediaStatus.PLAYER_STATE_BUFFERING) {
            setLine2(getString(R.string.loading));
        } else {
            setLine2(getString(R.string.casting_to_device,
                    mCastManager.getDeviceName()));
        }
        switch (mediaStatus) {
            case MediaStatus.PLAYER_STATE_PLAYING:
                mIsFresh = false;
                if (mPlaybackState != MediaStatus.PLAYER_STATE_PLAYING) {
                    mPlaybackState = MediaStatus.PLAYER_STATE_PLAYING;
                    setPlaybackStatus(mPlaybackState);
                }
                break;
            case MediaStatus.PLAYER_STATE_PAUSED:
                mIsFresh = false;
                if (mPlaybackState != MediaStatus.PLAYER_STATE_PAUSED) {
                    mPlaybackState = MediaStatus.PLAYER_STATE_PAUSED;
                    setPlaybackStatus(mPlaybackState);
                }
                break;
            case MediaStatus.PLAYER_STATE_BUFFERING:
                mIsFresh = false;
                if (mPlaybackState != MediaStatus.PLAYER_STATE_BUFFERING) {
                    mPlaybackState = MediaStatus.PLAYER_STATE_BUFFERING;
                    setPlaybackStatus(mPlaybackState);
                }
                break;
            case MediaStatus.PLAYER_STATE_IDLE:
                switch (mCastManager.getIdleReason()) {
                    case MediaStatus.IDLE_REASON_FINISHED:
                        if (!mIsFresh) {
                            closeActivity();
                        }
                        break;
                    case MediaStatus.IDLE_REASON_CANCELED:
                        try {
                            if (mCastManager.isRemoteStreamLive()) {
                                if (mPlaybackState != MediaStatus.PLAYER_STATE_IDLE) {
                                    mPlaybackState = MediaStatus.PLAYER_STATE_IDLE;
                                    setPlaybackStatus(mPlaybackState);
                                }
                            }
                        } catch (TransientNetworkDisconnectionException e) {
                            LOGD(TAG, "Failed to determine if stream is live", e);
                        } catch (NoConnectionException e) {
                            LOGD(TAG, "Failed to determine if stream is live", e);
                        }
                    default:
                        break;
                }
                break;

            default:
                break;
        }
    }

    @Override
    public void onDestroy() {
        LOGD(TAG, "onDestroy()");
        stopTrickplayTimer();
        cleanup();
        super.onDestroy();
    }

    @Override
    public void onResume() {
        LOGD(TAG, "onResume() was called");
        try {
            mCastManager = VideoCastManager.getInstance(getActivity());
            boolean shouldFinish = !mCastManager.isConnected()
                    || (mCastManager.getPlaybackStatus() == MediaStatus.PLAYER_STATE_IDLE
                    && mCastManager.getIdleReason() == MediaStatus.IDLE_REASON_FINISHED
                    && !mIsFresh);
            if (shouldFinish) {
                //mCastController.closeActivity();
            }
            if (!mIsFresh) {
                updatePlayerStatus();
            }
            // updating metadata in case someone else has changed it and we are resuming the
            // activity
            try {
                mSelectedMedia = mCastManager.getRemoteMediaInformation();
                updateClosedCaptionState();
                updateMetadata();
            } catch (TransientNetworkDisconnectionException e) {
                LOGE(TAG, "Failed to update the metadata due to network issues", e);
            } catch (NoConnectionException e) {
                LOGE(TAG, "Failed to update the metadata due to network issues", e);
            }
        } catch (CastException e) {
            // logged already
        }
        super.onResume();
    }

    @Override
    public void onPause() {
        mIsFresh = false;
        super.onPause();
    }

    /**
     * Call this static method to create an instance of this fragment.
     *
     * @param extras
     * @return
     */
    public static WapoVideoCastControllerFragment newInstance(Bundle extras) {
        WapoVideoCastControllerFragment f = new WapoVideoCastControllerFragment();
        Bundle b = new Bundle();
        b.putBundle(EXTRAS, extras);
        f.setArguments(b);
        return f;
    }

    /*
     * Gets the image at the given url and populates the image view with that. It tries to cache the
     * image to avoid unnecessary network calls.
     */
    private void showImage(final Uri url) {
        if (null != mImageAsyncTask) {
            mImageAsyncTask.cancel(true);
        }
        if (null == url) {
            setImage(BitmapFactory.decodeResource(getActivity().getResources(),
                    R.drawable.dummy_album_art_large));
            return;
        }
        if (null != mUrlAndBitmap && mUrlAndBitmap.isMatch(url)) {
            // we can reuse mBitmap
            setImage(mUrlAndBitmap.mBitmap);
            return;
        }
        mUrlAndBitmap = null;
        mImageAsyncTask = new FetchBitmapTask() {
            @Override
            protected void onPostExecute(Bitmap bitmap) {
                if (null != bitmap) {
                    mUrlAndBitmap = new UrlAndBitmap();
                    mUrlAndBitmap.mBitmap = bitmap;
                    mUrlAndBitmap.mUrl = url;
                    setImage(bitmap);
                    blurImage.setImageBitmap(Utils.blurBitmap(bitmap,getActivity()));
                }
                if (this == mImageAsyncTask) {
                    mImageAsyncTask = null;
                }
            }
        };
        mImageAsyncTask.start(url);
    }

    /*
     * A modal dialog with an OK button, where upon clicking on it, will finish the activity. We use
     * a DialogFragment so during configuration changes, system manages the dialog for us.
     */
    public static class ErrorDialogFragment extends DialogFragment {

        private IVideoCastController mController;
        private static final String MESSAGE = "message";

        public static ErrorDialogFragment newInstance(String message) {
            ErrorDialogFragment frag = new ErrorDialogFragment();
            Bundle args = new Bundle();
            args.putString(MESSAGE, message);
            frag.setArguments(args);
            return frag;
        }

        @Override
        public void onAttach(Activity activity) {
            mController = (IVideoCastController) activity;
            super.onAttach(activity);
            setCancelable(false);
        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            String message = getArguments().getString(MESSAGE);
            return new AlertDialog.Builder(getActivity())
                    .setTitle(R.string.error)
                    .setMessage(message)
                    .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {

                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            sDialogCanceled = true;
                            mController.closeActivity();
                        }
                    })
                    .create();
        }
    }

    /*
     * Shows an error dialog
     */
    private void showErrorDialog(String message) {
        ErrorDialogFragment.newInstance(message).show(getFragmentManager(), "dlg");
    }

    // ------- Implementation of OnVideoCastControllerListener interface ----------------- //
    @Override
    public void onStopTrackingTouch(SeekBar seekBar) {
        try {
            if (mPlaybackState == MediaStatus.PLAYER_STATE_PLAYING) {
                mPlaybackState = MediaStatus.PLAYER_STATE_BUFFERING;
                setPlaybackStatus(mPlaybackState);
                mCastManager.play(seekBar.getProgress());
            } else if (mPlaybackState == MediaStatus.PLAYER_STATE_PAUSED) {
                mCastManager.seek(seekBar.getProgress());
            }
            restartTrickplayTimer();
        } catch (Exception e) {
            LOGE(TAG, "Failed to complete seek", e);
            closeActivity();
        }
    }

    @Override
    public void onStartTrackingTouch(SeekBar seekBar) {
        stopTrickplayTimer();

    }

    @Override
    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
    }

    @Override
    public void onPlayPauseClicked(View v) throws CastException,
            TransientNetworkDisconnectionException, NoConnectionException {
        LOGD(TAG, "isConnected returning: " + mCastManager.isConnected());
        togglePlayback();
    }

    private void togglePlayback() throws CastException, TransientNetworkDisconnectionException,
            NoConnectionException {
        switch (mPlaybackState) {
            case MediaStatus.PLAYER_STATE_PAUSED:
                mCastManager.play();
                mPlaybackState = MediaStatus.PLAYER_STATE_BUFFERING;
                restartTrickplayTimer();
                break;
            case MediaStatus.PLAYER_STATE_PLAYING:
                mCastManager.pause();
                mPlaybackState = MediaStatus.PLAYER_STATE_BUFFERING;
                break;
            case MediaStatus.PLAYER_STATE_IDLE:
                if ((mSelectedMedia.getStreamType() == MediaInfo.STREAM_TYPE_LIVE)
                        && (mCastManager.getIdleReason() == MediaStatus.IDLE_REASON_CANCELED)) {
                    mCastManager.play();
                } else {
                    mCastManager.loadMedia(mSelectedMedia, true, 0);
                }
                mPlaybackState = MediaStatus.PLAYER_STATE_BUFFERING;
                restartTrickplayTimer();
                break;
            default:
                break;
        }
        setPlaybackStatus(mPlaybackState);
    }

    @Override
    public void onConfigurationChanged() {
        updateOverallState();
        if (null == mSelectedMedia) {
            if (null != mMediaAuthService) {
                showImage(Utils.getImageUri(mMediaAuthService.getMediaInfo(), 0));
            }
        } else {
            updateMetadata();
            updatePlayerStatus();
            updateControllersStatus(mCastManager.isConnected());
        }
    }

    @Override
    public void onPlayerStatusChanged(boolean status) {
        updateControllersStatus(status);
    }

    @Override
    public void onPlayerStatusUpdated() {
        updatePlayerStatus();
    }

    @Override
    public void onPlayerMetaDataUpdated() {
        try {
            mSelectedMedia = mCastManager.getRemoteMediaInformation();
            updateMetadata();
        } catch (TransientNetworkDisconnectionException e) {
            LOGE(TAG, "Failed to update the metadata due to network issues", e);
        } catch (NoConnectionException e) {
            LOGE(TAG, "Failed to update the metadata due to network issues", e);
        }
    }

    // ------- Implementation of IMediaAuthListener interface --------------------------- //
    @Override
    public void onResult(MediaAuthStatus status, final MediaInfo info, final String message,
                         final int startPoint, final JSONObject customData) {
        if (status == MediaAuthStatus.RESULT_AUTHORIZED && mAuthSuccess) {
            // successful authorization
            mMediaAuthService = null;
            if (null != mMediaAuthTimer) {
                mMediaAuthTimer.cancel();
            }
            mSelectedMedia = info;
            updateClosedCaptionState();
            mHandler.post(new Runnable() {

                @Override
                public void run() {
                    mOverallState = OverallState.PLAYBACK;
                    onReady(info, true, startPoint, customData);
                }
            });
        } else {
            if (null != mMediaAuthTimer) {
                mMediaAuthTimer.cancel();
            }
            mHandler.post(new Runnable() {
                @Override
                public void run() {
                    mOverallState = OverallState.UNKNOWN;
                    showErrorDialog(message);
                }
            });

        }
    }

    @Override
    public void onFailure(final String failureMessage) {
        if (null != mMediaAuthTimer) {
            mMediaAuthTimer.cancel();
        }
        mHandler.post(new Runnable() {

            @Override
            public void run() {
                mOverallState = OverallState.UNKNOWN;
                showErrorDialog(failureMessage);
            }
        });

    }

    @Override
    public void onTracksSelected(List<MediaTrack> tracks) {
        long[] tracksArray;
        if (tracks.size() == 0) {
            tracksArray = new long[]{};
        } else {
            tracksArray = new long[tracks.size()];
            for (int i = 0; i < tracks.size(); i++) {
                tracksArray[i] = tracks.get(i).getId();
            }
        }
        mCastManager.setActiveTrackIds(tracksArray);
        if (tracks.size() > 0) {
            mCastManager.setTextTrackStyle(mCastManager.getTracksPreferenceManager()
                    .getTextTrackStyle());
        }
    }

    // ----------- Some utility methods --------------------------------------------------------- //

    /*
     * A simple class that holds a URL and a bitmap, mainly used to cache the fetched image
     */
    private class UrlAndBitmap {

        private Bitmap mBitmap;
        private Uri mUrl;

        private boolean isMatch(Uri url) {
            return null != url && null != mBitmap && url.equals(mUrl);
        }
    }

    /*
     * Cleanup of threads and timers and bitmap and ...
     */
    private void cleanup() {
        IMediaAuthService authService = mCastManager.getMediaAuthService();
        if (null != mMediaAuthTimer) {
            mMediaAuthTimer.cancel();
        }
        if (null != mAuthThread) {
            mAuthThread = null;
        }
        if (null != mCastManager.getMediaAuthService()) {
            authService.setOnResult(null);
            mCastManager.removeMediaAuthService();
        }
        if (null != mCastManager) {
            // Moved to activity
            //mCastManager.removeVideoCastConsumer(mCastConsumer);
        }
        if (null != mHandler) {
            mHandler.removeCallbacksAndMessages(null);
        }
        if (null != mUrlAndBitmap) {
            mUrlAndBitmap.mBitmap = null;
        }
        if (!sDialogCanceled && null != mMediaAuthService) {
            mMediaAuthService.abort(MediaAuthStatus.ABORT_USER_CANCELLED);
        }
    }

    // -------------- IVideoCastController implementation ---------------- //
    @Override
    public void showLoading(boolean visible) {
        mLoading.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
    }

    @Override
    public void adjustControllersForLiveStream(boolean isLive) {
        int visibility = isLive ? View.INVISIBLE : View.VISIBLE;
        mLiveText.setVisibility(isLive ? View.VISIBLE : View.INVISIBLE);
        mStart.setVisibility(visibility);
        mEnd.setVisibility(visibility);
        mSeekbar.setVisibility(visibility);
    }

    @Override
    public void updateClosedCaption(int status) {
        switch (status) {
            case CC_ENABLED:
                mClosedCaptionIcon.setVisibility(View.VISIBLE);
                mClosedCaptionIcon.setEnabled(true);
                break;
            case CC_DISABLED:
                mClosedCaptionIcon.setVisibility(View.VISIBLE);
                mClosedCaptionIcon.setEnabled(false);
                break;
            case CC_HIDDEN:
                mClosedCaptionIcon.setVisibility(View.GONE);
                break;
        }
    }

    @Override
    public void setPlaybackStatus(int state) {
        LOGD(TAG, "setPlaybackStatus(): state = " + state);
        switch (state) {
            case MediaStatus.PLAYER_STATE_PLAYING:
                mLoading.setVisibility(View.INVISIBLE);
                mPlayPause.setVisibility(View.VISIBLE);

                if (mStreamType == MediaInfo.STREAM_TYPE_LIVE) {
                    mPlayPause.setImageDrawable(mStopDrawable);
                } else {
                    mPlayPause.setImageDrawable(mPauseDrawable);
                }

                mLine2.setText(getString(R.string.casting_to_device,
                        mCastManager.getDeviceName()));
                mControllers.setVisibility(View.VISIBLE);
                break;
            case MediaStatus.PLAYER_STATE_PAUSED:
                mControllers.setVisibility(View.VISIBLE);
                mLoading.setVisibility(View.INVISIBLE);
                mPlayPause.setVisibility(View.VISIBLE);
                mPlayPause.setImageDrawable(mPlayDrawable);
                mLine2.setText(getString(R.string.casting_to_device,
                        mCastManager.getDeviceName()));
                break;
            case MediaStatus.PLAYER_STATE_IDLE:
                mLoading.setVisibility(View.INVISIBLE);
                mPlayPause.setImageDrawable(mPlayDrawable);
                mPlayPause.setVisibility(View.VISIBLE);
                mLine2.setText(getString(R.string.casting_to_device,
                        mCastManager.getDeviceName()));
                break;
            case MediaStatus.PLAYER_STATE_BUFFERING:
                mPlayPause.setVisibility(View.INVISIBLE);
                mLoading.setVisibility(View.VISIBLE);
                mLine2.setText(getString(R.string.loading));
                break;
            default:
                break;
        }
    }

    @Override
    public void updateSeekbar(int position, int duration) {
        mSeekbar.setProgress(position);
        mSeekbar.setMax(duration);
        mStart.setText(Utils.formatMillis(position));
        mEnd.setText(Utils.formatMillis(duration));
    }

    @SuppressWarnings("deprecation")
    @Override
    public void setImage(Bitmap bitmap) {
        if (null != bitmap) {
            mCastThumbNail.setImageBitmap(bitmap);
        }
    }

    @Override
    public void setLine1(String text) {
        if (null == text) {
            text = "";
        }
        mLine1.setText(text);
    }

    @Override
    public void setLine2(String text) {
        if (null == text) {
            text = "";
        }
        mLine2.setText(text);
    }

    @Override
    public void setOnVideoCastControllerChangedListener(OnVideoCastControllerListener listener) {
    }

    @Override
    public void setStreamType(int streamType) {
        this.mStreamType = streamType;
    }

    @Override
    public void updateControllersStatus(boolean enabled) {
        mControllers.setVisibility(enabled ? View.VISIBLE : View.INVISIBLE);
        if (enabled) {
            adjustControllersForLiveStream(mStreamType == MediaInfo.STREAM_TYPE_LIVE);
        }
    }

    /*
    * Since it's will part of the main Activity - DON'T WANT TO KILL THE ACTIVITY
    */
    @Override
    public void closeActivity() {
        //finish();
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        super.onCreateOptionsMenu(menu, inflater);
        final MediaRouteButton mediaRouteButton = mCastManager.addMediaRouterButton(mediaRouteFeatureButton);
        mediaRouteButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mediaRouteButton.showDialog();
            }
        });
    }
}
