package com.google.android.libraries.cast.companionlibrary.cast.player;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.Nullable;
import android.support.v4.app.DialogFragment;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentTransaction;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import com.google.android.gms.cast.MediaInfo;
import com.google.android.gms.cast.MediaMetadata;
import com.google.android.gms.cast.MediaQueueItem;
import com.google.android.gms.cast.MediaStatus;
import com.google.android.gms.cast.MediaTrack;
import com.google.android.gms.cast.RemoteMediaPlayer;
import com.google.android.libraries.cast.companionlibrary.R;
import com.google.android.libraries.cast.companionlibrary.cast.MediaQueue;
import com.google.android.libraries.cast.companionlibrary.cast.VideoCastManager;
import com.google.android.libraries.cast.companionlibrary.cast.exceptions.CastException;
import com.google.android.libraries.cast.companionlibrary.cast.exceptions.NoConnectionException;
import com.google.android.libraries.cast.companionlibrary.cast.exceptions.TransientNetworkDisconnectionException;
import com.google.android.libraries.cast.companionlibrary.cast.tracks.ui.TracksChooserDialog;
import com.google.android.libraries.cast.companionlibrary.utils.FetchBitmapTask;
import com.google.android.libraries.cast.companionlibrary.utils.LogUtils;
import com.google.android.libraries.cast.companionlibrary.utils.Utils;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import static com.google.android.libraries.cast.companionlibrary.utils.LogUtils.LOGD;
import static com.google.android.libraries.cast.companionlibrary.utils.LogUtils.LOGE;

/**
 * Created by mehtam2 on 8/22/14.
 * <p/>
 * This fragment is forked from VideoCastControllerFragment and VideoCastControllerActivity, with update in specs we have decided to add our fragment to the Main Activity.
 * All logic of establishing communication with CC is moved to our MainActivity.
 */
public class WapoVideoCastControllerFragment extends Fragment implements OnVideoCastControllerListener,
        MediaAuthListener, VideoCastController {

    public static final String DIALOG_TAG = "dialog";
    private static final String EXTRAS = "extras";
    private static final String TAG = LogUtils.makeLogTag(WapoVideoCastControllerFragment.class);
    private MediaInfo mSelectedMedia;
    private VideoCastManager mCastManager;
    private MediaAuthService mMediaAuthService;
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
    private ImageView mClosedCaptionIcon;
    private ImageView blurImage;
    private android.support.v7.app.MediaRouteButton mediaRouteFeatureButton;
    private View mPlaybackControls;
    private int mNextPreviousVisibilityPolicy
            = VideoCastController.NEXT_PREV_VISIBILITY_POLICY_DISABLED;
    private ImageButton mSkipNext;
    private ImageButton mSkipPrevious;
    private MediaStatus mMediaStatus;

    private enum OverallState {
        AUTHORIZING, PLAYBACK, UNKNOWN
    }

    // ------- Overriding of Fragment interface ----------------- //
    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        sDialogCanceled = false;
        mHandler = new Handler();
        mCastManager = VideoCastManager.getInstance();
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
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        if (mCastManager.getPreferenceAccessor()
                .getBooleanFromPreference(VideoCastManager.PREFS_KEY_IMMERSIVE_MODE, true)) {
            setImmersive();
        }

        loadAndSetupViews();
        setHasOptionsMenu(true);
        Bundle bundle = getArguments();
        if (bundle == null) {
            return;
        }
        Bundle extras = bundle.getBundle(EXTRAS);
        Bundle mediaWrapper = extras.getBundle(VideoCastManager.EXTRA_MEDIA);

        mCastManager.addTracksSelectedListener(this);
        // NOTE : We don't need this logic.
//        boolean explicitStartActivity = false;
//        Utils.getBooleanFromPreference(getActivity(),
//                VideoCastManager.PREFS_KEY_START_ACTIVITY, false);
//        if (explicitStartActivity) {
//            mIsFresh = true;
//        }
        mCastManager.getPreferenceAccessor().saveBooleanToPreference(
                VideoCastManager.PREFS_KEY_START_ACTIVITY, false);
        int nextPreviousVisibilityPolicy = mCastManager.getPreferenceAccessor()
                .getIntFromPreference(VideoCastManager.PREFS_KEY_NEXT_PREV_POLICY,
                        VideoCastController.NEXT_PREV_VISIBILITY_POLICY_DISABLED);
        setNextPreviousVisibilityPolicy(nextPreviousVisibilityPolicy);


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
            MediaInfo info = Utils.bundleToMediaInfo(mediaWrapper);
            int startPoint = extras.getInt(VideoCastManager.EXTRA_START_POINT, 0);
            onReady(info, shouldStartPlayback, startPoint, customData);
            //onReady(info, shouldStartPlayback && explicitStartActivity, startPoint, customData);
        }
    }

    /*
     *  Initialize Views
     */
    private void loadAndSetupViews() {
        mediaRouteFeatureButton = (android.support.v7.app.MediaRouteButton)getView().findViewById(R.id.castConnectedButton);
        mPauseDrawable = getResources().getDrawable(R.drawable.ic_pause_circle_white_80dp);
        mPlayDrawable = getResources().getDrawable(R.drawable.ic_play_circle_white_80dp);
        mStopDrawable = getResources().getDrawable(R.drawable.ic_stop_circle_white_80dp);
        mPageView = getView().findViewById(R.id.pageView);
        mPlayPause = (ImageButton) getView().findViewById(R.id.play_pause_toggle);
        mLiveText = (TextView) getView().findViewById(R.id.live_text);
        mStart = (TextView) getView().findViewById(R.id.start_text);
        mEnd = (TextView) getView().findViewById(R.id.end_text);
        mSeekbar = (SeekBar) getView().findViewById(R.id.seekbar);
        mLine1 = (TextView) getView().findViewById(R.id.textView1);
        mLine2 = (TextView) getView().findViewById(R.id.textView2);
        mLoading = (ProgressBar) getView().findViewById(R.id.progressBar1);
        mControllers = getView().findViewById(R.id.controllers);
        //mCastThumbNail = (ImageView) getView().findViewById(R.id.castThumbNail);
        mClosedCaptionIcon = (ImageView) getView().findViewById(R.id.cc);
        mSkipNext = (ImageButton) getView().findViewById(R.id.next);
        mSkipPrevious = (ImageButton) getView().findViewById(R.id.previous);
        mPlaybackControls = getView().findViewById(R.id.playback_controls);
        blurImage = (ImageView) getView().findViewById(R.id.blurImg);
        setClosedCaptionState(CC_DISABLED);
        mPlayPause.setOnClickListener(new View.OnClickListener() {

            @Override
            public void onClick(View v) {
                try {
                    onPlayPauseClicked(v);
                } catch (TransientNetworkDisconnectionException e) {
                    LOGE(TAG, "Failed to toggle playback due to temporary network issue", e);
                    Utils.showToast(getActivity(),
                            R.string.ccl_failed_no_connection_trans);
                } catch (NoConnectionException e) {
                    LOGE(TAG, "Failed to toggle playback due to network issues", e);
                    Utils.showToast(getActivity(),
                            R.string.ccl_failed_no_connection);
                } catch (Exception e) {
                    LOGE(TAG, "Failed to toggle playback due to other issues", e);
                    Utils.showToast(getActivity(),
                            R.string.ccl_failed_perform_action);
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

        mClosedCaptionIcon.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    showTracksChooserDialog();
                } catch (TransientNetworkDisconnectionException e) {
                    LOGE(TAG, "Failed to get the media", e);
                } catch (NoConnectionException e) {
                    LOGE(TAG, "Failed to get the media", e);
                }
            }
        });

        mSkipNext.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    onSkipNextClicked(v);
                } catch (TransientNetworkDisconnectionException | NoConnectionException e) {
                    LOGE(TAG, "Failed to move to the next item in the queue", e);
                }
            }
        });

        mSkipPrevious.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    onSkipPreviousClicked(v);
                } catch (TransientNetworkDisconnectionException | NoConnectionException e) {
                    LOGE(TAG, "Failed to move to the previous item in the queue", e);
                }
            }
        });
    }

    private void showTracksChooserDialog()
            throws TransientNetworkDisconnectionException, NoConnectionException {
        FragmentTransaction transaction = getActivity().getSupportFragmentManager().beginTransaction();
        Fragment prev = getActivity().getSupportFragmentManager().findFragmentByTag(DIALOG_TAG);
        if (prev != null) {
            transaction.remove(prev);
        }
        transaction.addToBackStack(null);

        // Create and show the dialog.
        TracksChooserDialog dialogFragment = TracksChooserDialog
                .newInstance(mCastManager.getRemoteMediaInformation());
        dialogFragment.show(transaction, DIALOG_TAG);
    }

    /*
     * Starts a background thread for starting the Auth Service
     */
    private void handleMediaAuthTask(final MediaAuthService authService) {
        showLoading(true);
        if (authService == null) {
            return;
        }
        setSubTitle(authService.getPendingMessage() != null
                ? authService.getPendingMessage() : "");
        mAuthThread = new Thread(new Runnable() {

            @Override
            public void run() {
                authService.setMediaAuthListener(WapoVideoCastControllerFragment.this);
                authService.startAuthorization();
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
                        showErrorDialog(getString(R.string.ccl_failed_authorization_timeout));
                        mAuthSuccess = false;
                        if (null != mMediaAuthService
                                && mMediaAuthService.getStatus() == MediaAuthStatus.PENDING) {
                            mMediaAuthService.abortAuthorization(MediaAuthStatus.TIMED_OUT);
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
                    } catch (TransientNetworkDisconnectionException | NoConnectionException e) {
                        LOGE(TAG, "Failed to update the progress bar due to network issues", e);
                    }
                }
            });
        }
    }

    /**
     * Loads the media on the cast device.
     *
     * @param mediaInfo The media to be loaded
     * @param shouldStartPlayback If {@code true}, playback starts after load automatically
     * @param startPoint The position to start the play back
     * @param customData An optional custom data to be sent along the load api; it can be
     * {@code null}
     */
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
                if (mCastManager.isRemoteMediaPlaying()) {
                    mPlaybackState = MediaStatus.PLAYER_STATE_PLAYING;
                } else {
                    mPlaybackState = MediaStatus.PLAYER_STATE_PAUSED;
                }
                setPlaybackStatus(mPlaybackState);
            }
        } catch (Exception e) {
            LOGE(TAG, "Failed to get playback and media information", e);
        }
        MediaQueue mediaQueue = mCastManager.getMediaQueue();
        int size = 0;
        int position = 0;
        if (mediaQueue != null) {
            size = mediaQueue.getCount();
            position = mediaQueue.getCurrentItemPosition();
        }
        onQueueItemsUpdated(size, position);
        updateMetadata();
        restartTrickplayTimer();
    }

    private void updateClosedCaptionState() {
        int state = VideoCastController.CC_HIDDEN;
        if (mCastManager != null && mCastManager.isFeatureEnabled(VideoCastManager.FEATURE_CAPTIONS_PREFERENCE)
                && mSelectedMedia != null
                && mCastManager.getTracksPreferenceManager().isCaptionEnabled()) {
            List<MediaTrack> tracks = mSelectedMedia.getMediaTracks();
            state = tracks == null || tracks.isEmpty() ? VideoCastController.CC_DISABLED
                    : VideoCastController.CC_ENABLED;
        }
        setClosedCaptionState(state);
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
        MediaAuthService authService;
        switch (mOverallState) {
            case AUTHORIZING:
                authService = mCastManager.getMediaAuthService();
                if (null != authService) {
                    setSubTitle(null != authService.getPendingMessage()
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
        if (mSelectedMedia == null) {
            if (mMediaAuthService != null) {
                imageUrl = Utils.getImageUri(mMediaAuthService.getMediaInfo(), 0);
            }
        } else {
            imageUrl = Utils.getImageUri(mSelectedMedia, 0);
        }
        showImage(imageUrl);
        if (mSelectedMedia == null) {
            return;
        }
        MediaMetadata mm = mSelectedMedia.getMetadata();
        setTitle(mm.getString(MediaMetadata.KEY_TITLE) != null
                ? mm.getString(MediaMetadata.KEY_TITLE) : "");
        boolean isLive = mSelectedMedia.getStreamType() == MediaInfo.STREAM_TYPE_LIVE;
        adjustControllersForLiveStream(isLive);
    }

    private void updatePlayerStatus() {
        int mediaStatus = mCastManager.getPlaybackStatus();
        mMediaStatus = mCastManager.getMediaStatus();
        LOGD(TAG, "updatePlayerStatus(), state: " + mediaStatus);
        if (mSelectedMedia == null) {
            return;
        }
        setStreamType(mSelectedMedia.getStreamType());
        if (mediaStatus == MediaStatus.PLAYER_STATE_BUFFERING) {
            setSubTitle(getString(R.string.ccl_loading));
        } else {
            setSubTitle(getString(R.string.ccl_casting_to_device,
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
                        if (!mIsFresh && mMediaStatus.getLoadingItemId()
                                == MediaQueueItem.INVALID_ITEM_ID) {
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
                        } catch (TransientNetworkDisconnectionException | NoConnectionException e) {
                            LOGD(TAG, "Failed to determine if stream is live", e);
                        }
                        break;
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
        super.onResume();
        LOGD(TAG, "onResume() was called");
        try {
            mCastManager = VideoCastManager.getInstance();

            try {
                if (mCastManager.isRemoteMediaPaused() || mCastManager.isRemoteMediaPlaying()) {
                    if (mCastManager.getRemoteMediaInformation() != null &&
                            mSelectedMedia.getContentId()
                                    .equals(mCastManager.getRemoteMediaInformation()
                                            .getContentId())) {
                        mIsFresh = false;
                    }
                }
            } catch (TransientNetworkDisconnectionException | NoConnectionException e) {
                LOGE(TAG, "Failed to get media information or status of media playback", e);
            }

            if (!mCastManager.isConnecting()) {
                boolean shouldFinish = !mCastManager.isConnected()
                        || (mCastManager.getPlaybackStatus() == MediaStatus.PLAYER_STATE_IDLE
                        && mCastManager.getIdleReason() == MediaStatus.IDLE_REASON_FINISHED);
                if (shouldFinish && !mIsFresh) {
                    closeActivity();
                    return;
                }
            }

            mMediaStatus = mCastManager.getMediaStatus();

            if (!mIsFresh) {
                updatePlayerStatus();
                // updating metadata in case someone else has changed it and we are resuming the
                // activity
                try {
                    mSelectedMedia = mCastManager.getRemoteMediaInformation();
                    updateClosedCaptionState();
                    updateMetadata();
                } catch (TransientNetworkDisconnectionException | NoConnectionException e) {
                    LOGE(TAG, "Failed to get media information or status of media playback", e);
                }
            }
        } catch (Exception e) {
            // logged already\
            LOGE(TAG, "Failed to get VideoCastManager", e);
        }
    }

    @Override
    public void onPause() {
        mIsFresh = false;
        super.onPause();
    }

    @Override
    public void onStop() {
        super.onStop();
        if (mImageAsyncTask != null) {
            mImageAsyncTask.cancel(true);
            mImageAsyncTask = null;
        }
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
                    R.drawable.album_art_placeholder_large));
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
        mImageAsyncTask.execute(url);
    }

    /*
     * A modal dialog with an OK button, where upon clicking on it, will finish the activity. We use
     * a DialogFragment so during configuration changes, system manages the dialog for us.
     */
    public static class ErrorDialogFragment extends DialogFragment {

        private VideoCastController mController;
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
            mController = (VideoCastController) activity;
            super.onAttach(activity);
            setCancelable(false);
        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            String message = getArguments().getString(MESSAGE);
            return new AlertDialog.Builder(getActivity())
                    .setTitle(R.string.ccl_error)
                    .setMessage(message)
                    .setPositiveButton(R.string.ccl_ok, new DialogInterface.OnClickListener() {

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
    public void onPlayerStatusUpdated()
    {
        if(mCastManager != null) {
            updatePlayerStatus();
        }
    }

    @Override
    public void onPlayerMetaDataUpdated() {
        try {
            if(mCastManager != null) {
                mSelectedMedia = mCastManager.getRemoteMediaInformation();
                updateClosedCaptionState();
                updateMetadata();
            }
        }
        catch (TransientNetworkDisconnectionException e) {
            LOGE(TAG, "Failed to update the metadata due to network issues", e);
        } catch (NoConnectionException e) {
            LOGE(TAG, "Failed to update the metadata due to network issues", e);
        }
    }

    @Override
    public void onFailed(int resourceId, int statusCode) {
        LOGD(TAG, "onFailed(): " + getString(resourceId) + ", status code: " + statusCode);
        if (statusCode == RemoteMediaPlayer.STATUS_FAILED
                || statusCode == RemoteMediaPlayer.STATUS_TIMED_OUT) {
            Utils.showToast(getActivity(), resourceId);
        }
        if(mCastManager != null) {
            closeActivity();
        }
    }

    // ------- Implementation of IMediaAuthListener interface --------------------------- //
//    @Override
//    public void onResult(MediaAuthStatus status, final MediaInfo info, final String message,
//                         final int startPoint, final JSONObject customData) {
//        if (status == MediaAuthStatus.RESULT_AUTHORIZED && mAuthSuccess) {
//            // successful authorization
//            mMediaAuthService = null;
//            if (null != mMediaAuthTimer) {
//                mMediaAuthTimer.cancel();
//            }
//            mSelectedMedia = info;
//            mHandler.post(new Runnable() {
//
//                @Override
//                public void run() {
//                    updateClosedCaptionState();
//                    mOverallState = OverallState.PLAYBACK;
//                    onReady(info, true, startPoint, customData);
//                }
//            });
//        } else {
//            if (null != mMediaAuthTimer) {
//                mMediaAuthTimer.cancel();
//            }
//            mHandler.post(new Runnable() {
//                @Override
//                public void run() {
//                    mOverallState = OverallState.UNKNOWN;
//                    showErrorDialog(message);
//                }
//            });
//
//        }
//    }
//
//    @Override
//    public void onFailure(final String failureMessage) {
//        if (null != mMediaAuthTimer) {
//            mMediaAuthTimer.cancel();
//        }
//        mHandler.post(new Runnable() {
//
//            @Override
//            public void run() {
//                mOverallState = OverallState.UNKNOWN;
//                showErrorDialog(failureMessage);
//            }
//        });
//
//    }
    @Override
    public void onAuthResult(MediaAuthStatus status, final MediaInfo info, final String message,
                             final int startPoint, final JSONObject customData) {
        if (status == MediaAuthStatus.AUTHORIZED && mAuthSuccess) {
            // successful authorization
            mMediaAuthService = null;
            if (mMediaAuthTimer != null) {
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
            if (mMediaAuthTimer != null) {
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
    public void onAuthFailure(final String failureMessage) {
        if (mMediaAuthTimer != null) {
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
        MediaAuthService authService = mCastManager.getMediaAuthService();
        if (null != mMediaAuthTimer) {
            mMediaAuthTimer.cancel();
        }
        if (null != mAuthThread) {
            mAuthThread = null;
        }
        if (null != mCastManager.getMediaAuthService()) {
            authService.setMediaAuthListener(null);
            mCastManager.removeMediaAuthService();
        }
        if (null != mCastManager) {
            // Moved to activity
            //mCastManager.mImageAsyncTask(mCastConsumer);
        }
        if (null != mHandler) {
            mHandler.removeCallbacksAndMessages(null);
        }
        if (null != mUrlAndBitmap) {
            mUrlAndBitmap.mBitmap = null;
        }
        if (!sDialogCanceled && null != mMediaAuthService) {
            mMediaAuthService.abortAuthorization(MediaAuthStatus.CANCELED_BY_USER);
        }

        //mCastManager.clearContext(getActivity());
        mCastManager.removeTracksSelectedListener(this);
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
    public void setClosedCaptionState(int status) {
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
            default:
                LOGE(TAG, "setClosedCaptionState(): Invalid state requested: " + status);
        }
    }

    @Override
    public void setPlaybackStatus(int state) {
        LOGD(TAG, "setPlaybackStatus(): state = " + state);
        switch (state) {
            case MediaStatus.PLAYER_STATE_PLAYING:
                mLoading.setVisibility(View.INVISIBLE);
                mPlaybackControls.setVisibility(View.VISIBLE);
                if (mStreamType == MediaInfo.STREAM_TYPE_LIVE) {
                    mPlayPause.setImageDrawable(mStopDrawable);
                } else {
                    mPlayPause.setImageDrawable(mPauseDrawable);
                }

                mLine2.setText(getString(R.string.ccl_casting_to_device,
                        mCastManager.getDeviceName()));
                mControllers.setVisibility(View.VISIBLE);
                break;
            case MediaStatus.PLAYER_STATE_PAUSED:
                mControllers.setVisibility(View.VISIBLE);
                mLoading.setVisibility(View.INVISIBLE);
                mPlaybackControls.setVisibility(View.VISIBLE);
                mPlayPause.setImageDrawable(mPlayDrawable);
                mLine2.setText(getString(R.string.ccl_casting_to_device,
                        mCastManager.getDeviceName()));
                break;
            case MediaStatus.PLAYER_STATE_IDLE:
                mLoading.setVisibility(View.INVISIBLE);
                mPlayPause.setImageDrawable(mPlayDrawable);
                mPlaybackControls.setVisibility(View.VISIBLE);
                mLine2.setText(getString(R.string.ccl_casting_to_device,
                        mCastManager.getDeviceName()));
                break;
            case MediaStatus.PLAYER_STATE_BUFFERING:
                mPlaybackControls.setVisibility(View.INVISIBLE);
                mLoading.setVisibility(View.VISIBLE);
                mLine2.setText(getString(R.string.ccl_loading));
                break;
            default:
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
        if (bitmap != null) {
            if (mPageView instanceof ImageView) {
                ((ImageView) mPageView).setImageBitmap(bitmap);
            } else {
                mPageView.setBackgroundDrawable(new BitmapDrawable(getResources(), bitmap));
            }
        }
    }

    @Override
    public void setTitle(String text) {
        if (null == text) {
            text = "";
        }
        mLine1.setText(text);
    }

    @Override
    public void setSubTitle(String text) {
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

        mediaRouteFeatureButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mediaRouteFeatureButton.showDialog();
            }
        });

        mCastManager.addMediaRouterButton(mediaRouteFeatureButton);
    }

    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    private void setImmersive() {
        if (Build.VERSION.SDK_INT < 11) {
            return;
        }
        int newUiOptions = getActivity().getWindow().getDecorView().getSystemUiVisibility();

        // Navigation bar hiding:  Backwards compatible to ICS.
        if (Build.VERSION.SDK_INT >= 14) {
            newUiOptions ^= View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
        }

        // Status bar hiding: Backwards compatible to Jellybean
        if (Build.VERSION.SDK_INT >= 16) {
            newUiOptions ^= View.SYSTEM_UI_FLAG_FULLSCREEN;
        }

        if (Build.VERSION.SDK_INT >= 18) {
            newUiOptions ^= View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        }

        getActivity().getWindow().getDecorView().setSystemUiVisibility(newUiOptions);
    }

    @Override
    public void onSkipNextClicked(View v) throws TransientNetworkDisconnectionException, NoConnectionException {
        mCastManager.queueNext(null);
    }

    @Override
    public void onSkipPreviousClicked(View v) throws TransientNetworkDisconnectionException, NoConnectionException {
        mCastManager.queuePrev(null);
    }

    @Override
    public void onQueueItemsUpdated(int queueLength, int position) {
        boolean prevAvailable = position > 0 ;
        boolean nextAvailable = position < queueLength - 1;
        switch(mNextPreviousVisibilityPolicy) {
            case VideoCastController.NEXT_PREV_VISIBILITY_POLICY_HIDDEN:
                if (nextAvailable) {
                    mSkipNext.setVisibility(View.VISIBLE);
                    mSkipNext.setEnabled(true);
                } else {
                    mSkipNext.setVisibility(View.INVISIBLE);
                }
                if (prevAvailable) {
                    mSkipPrevious.setVisibility(View.VISIBLE);
                    mSkipPrevious.setEnabled(true);
                } else {
                    mSkipPrevious.setVisibility(View.INVISIBLE);
                }
                break;
            case VideoCastController.NEXT_PREV_VISIBILITY_POLICY_ALWAYS:
                mSkipNext.setVisibility(View.VISIBLE);
                mSkipNext.setEnabled(true);
                mSkipPrevious.setVisibility(View.VISIBLE);
                mSkipPrevious.setEnabled(true);
                break;
            case VideoCastController.NEXT_PREV_VISIBILITY_POLICY_DISABLED:
                if (nextAvailable) {
                    mSkipNext.setVisibility(View.VISIBLE);
                    mSkipNext.setEnabled(true);
                } else {
                    mSkipNext.setVisibility(View.VISIBLE);
                    mSkipNext.setEnabled(false);
                }
                if (prevAvailable) {
                    mSkipPrevious.setVisibility(View.VISIBLE);
                    mSkipPrevious.setEnabled(true);
                } else {
                    mSkipPrevious.setVisibility(View.VISIBLE);
                    mSkipPrevious.setEnabled(false);
                }
                break;
            default:
                LOGE(TAG, "onQueueItemsUpdated(): Invalid NextPreviousPolicy has been set");
        }
    }

    @Override
    public void setNextPreviousVisibilityPolicy(int policy) {
        mNextPreviousVisibilityPolicy = policy;
    }
}
