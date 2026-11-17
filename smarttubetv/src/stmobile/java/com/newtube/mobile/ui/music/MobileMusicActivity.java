package com.newtube.mobile.ui.music;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.google.android.material.button.MaterialButton;
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.BrowseSection;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.VideoActionPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.menu.VideoMenuPresenter;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;
import com.liskovsoft.youtubeapi.service.YouTubeSignInService;
import com.newtube.mobile.ui.browse.AccountsSheet;
import com.newtube.mobile.ui.common.MobileActivity;
import com.newtube.mobile.ui.common.MobileSnackbar;
import com.newtube.mobile.ui.playback.MiniPlayerBridge;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * SyTube Music - the phone's YouTube-Music-style home.
 *
 * <p>Renders the signed-in music feed ({@code ContentService.getMusicObserve()}) as the same
 * thing the backend already returns: rows of shelf groups. The first emission is "Liked music",
 * the second the curated music rows; each becomes a horizontal
 * {@link MusicShelfAdapter} shelf. Taps route through the ordinary touch presenters, so songs
 * open the real player, music playlists start their first track, and long-press opens the same
 * context menu as every other card.</p>
 *
 * <p>The screen is also a {@link MiniPlayerBridge.MiniHost}: Back in a song's player minimizes
 * onto this page, and the docked now-playing strip reads the live mini session for transport
 * and progress. The strip deliberately does not adopt the video surface - music only needs the
 * artwork and controls, and the playback Activity keeps owning its player.</p>
 */
public class MobileMusicActivity extends MobileActivity implements MiniPlayerBridge.MiniHost {
    private static final long NOW_PLAYING_TICK_MS = 500;

    private SwipeRefreshLayout mSwipe;
    private LinearLayout mShelves;
    private View mState;
    private ProgressBar mStateProgress;
    private ImageView mStateIcon;
    private TextView mStateTitle;
    private TextView mStateMessage;
    private MaterialButton mStateAction;

    private View mNowPlaying;
    private ImageView mNowPlayingArt;
    private TextView mNowPlayingTitle;
    private TextView mNowPlayingAuthor;
    private ImageButton mNowPlayingPlayPause;
    private ProgressBar mNowPlayingProgress;
    private String mBoundNowPlayingArtUrl;

    /** Shelf views in delivery order, keyed by title so a duplicated row is not shown twice. */
    private final Map<String, View> mShelfViews = new LinkedHashMap<>();
    @Nullable
    private Disposable mLoad;
    private boolean mLoaded;

    private final Runnable mNowPlayingTick = this::updateNowPlaying;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_mobile_music);

        registerBackHandler(this::handleBack);

        bindViews();
        setupSwipeRefresh();
        setGreeting();

        mSwipe.setRefreshing(true);
        showLoading();
    }

    private void handleBack() {
        finish();
    }

    private void bindViews() {
        mSwipe = findViewById(R.id.mobile_music_swipe);
        mShelves = findViewById(R.id.mobile_music_shelves);
        mState = findViewById(R.id.mobile_music_state);
        mStateProgress = findViewById(R.id.mobile_music_state_progress);
        mStateIcon = findViewById(R.id.mobile_music_state_icon);
        mStateTitle = findViewById(R.id.mobile_music_state_title);
        mStateMessage = findViewById(R.id.mobile_music_state_message);
        mStateAction = findViewById(R.id.mobile_music_state_action);

        findViewById(R.id.mobile_music_back)
                .setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());

        mNowPlaying = findViewById(R.id.music_now_playing);
        mNowPlayingArt = findViewById(R.id.music_now_playing_art);
        mNowPlayingTitle = findViewById(R.id.music_now_playing_title);
        mNowPlayingAuthor = findViewById(R.id.music_now_playing_author);
        mNowPlayingPlayPause = findViewById(R.id.music_now_playing_play_pause);
        mNowPlayingProgress = findViewById(R.id.music_now_playing_progress);

        mNowPlaying.setOnClickListener(v -> {
            if (MiniPlayerBridge.isActive()) {
                MiniPlayerBridge.expand(this);
            }
        });
        mNowPlayingPlayPause.setOnClickListener(v -> {
            ExoPlayer player = MiniPlayerBridge.getPlayer();
            if (player != null) {
                player.setPlayWhenReady(!player.getPlayWhenReady());
                updateNowPlaying();
            }
        });
        findViewById(R.id.music_now_playing_close).setOnClickListener(v -> {
            MiniPlayerBridge.close();
            hideNowPlaying();
        });
    }

    private void setupSwipeRefresh() {
        mSwipe.setColorSchemeColors(getResources().getColor(R.color.mobile_color_primary));
        mSwipe.setProgressBackgroundColorSchemeColor(getResources().getColor(R.color.mobile_color_surface));
        mSwipe.setOnRefreshListener(() -> {
            if (!YouTubeSignInService.instance().isSigned()) {
                mSwipe.setRefreshing(false);
                showSignIn();
                return;
            }
            loadMusic(false);
        });
    }

    private void setGreeting() {
        TextView greeting = findViewById(R.id.mobile_music_greeting);
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        int res;
        if (hour < 5) {
            res = R.string.mobile_music_greeting_night;
        } else if (hour < 12) {
            res = R.string.mobile_music_greeting_morning;
        } else if (hour < 18) {
            res = R.string.mobile_music_greeting_afternoon;
        } else {
            res = R.string.mobile_music_greeting_evening;
        }
        greeting.setText(res);
    }

    // ---------------------------------------------------------------------------------
    // Feed
    // ---------------------------------------------------------------------------------

    /** Load the music page. {@code keepContent}: pull-to-refresh leaves the shelves up. */
    private void loadMusic(boolean keepContent) {
        if (isFinishing() || isDestroyed()) {
            return;
        }

        if (!YouTubeSignInService.instance().isSigned()) {
            mSwipe.setRefreshing(false);
            showSignIn();
            return;
        }

        if (mLoad != null) {
            mLoad.dispose();
        }
        if (!keepContent) {
            mShelfViews.clear();
            mShelves.removeAllViews();
            mLoaded = false;
        }
        if (!keepContent) {
            showLoading();
        }

        mSwipe.setRefreshing(true);
        mLoad = YouTubeServiceManager.instance().getContentService().getMusicObserve()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(this::appendGroups, this::onLoadError, this::onLoadComplete);
    }

    private void appendGroups(@Nullable List<MediaGroup> groups) {
        if (groups == null || isFinishing() || isDestroyed()) {
            return;
        }

        for (MediaGroup mediaGroup : groups) {
            if (mediaGroup == null || mediaGroup.getMediaItems() == null
                    || mediaGroup.getMediaItems().isEmpty()) {
                continue; // e.g. "Liked music" before the user liked anything
            }

            String title = mediaGroup.getTitle();
            if (title == null || title.isEmpty()) {
                title = getString(R.string.mobile_music_title);
            }
            if (mShelfViews.containsKey(title)) {
                continue;
            }

            BrowseSection section = new BrowseSection(MediaGroup.TYPE_MUSIC, title,
                    BrowseSection.TYPE_ROW, R.drawable.icon_music);
            VideoGroup videoGroup = VideoGroup.from(mediaGroup, section);
            List<Video> videos = videoGroup.getVideos();
            if (videos == null || videos.isEmpty()) {
                continue;
            }

            addShelf(title, videos);
        }

        if (!mShelfViews.isEmpty()) {
            mLoaded = true;
            hideState();
        }
    }

    private void addShelf(String title, List<Video> videos) {
        View shelf = LayoutInflater.from(this).inflate(R.layout.item_music_shelf, mShelves, false);
        ((TextView) shelf.findViewById(R.id.music_shelf_title)).setText(title);

        RecyclerView list = shelf.findViewById(R.id.music_shelf_list);
        list.setLayoutManager(new LinearLayoutManager(this, RecyclerView.HORIZONTAL, false));
        list.setItemAnimator(null);
        MusicShelfAdapter adapter = new MusicShelfAdapter(new MusicShelfAdapter.Listener() {
            @Override
            public void onMusicClick(Video video) {
                onMusicClicked(video);
            }

            @Override
            public boolean onMusicLongClick(Video video) {
                VideoMenuPresenter.instance(MobileMusicActivity.this).showMenu(video);
                return true;
            }
        });
        list.setAdapter(adapter);
        adapter.submit(new ArrayList<>(videos));

        mShelves.addView(shelf);
        mShelfViews.put(title, shelf);
    }

    private void onMusicClicked(Video video) {
        if (video == null) {
            return;
        }
        // The context-menu content and the player route are the same as elsewhere on the phone.
        VideoActionPresenter.instance(this).apply(video);
        // Non-video rows (playlists/mixes) resolve asynchronously before any screen opens; the
        // pull-to-refresh spinner is the "tap received" feedback, cleared on pause.
        if (!video.hasVideo() && (video.hasPlaylist() || video.hasChannel()
                || video.hasNestedItems() || video.isPlaylistAsChannel())) {
            mSwipe.setRefreshing(true);
        }
    }

    private void onLoadComplete() {
        mSwipe.setRefreshing(false);
        if (mLoaded || !mShelfViews.isEmpty()) {
            hideState();
            // The feed may finish loading after a song was opened elsewhere; dock the strip now.
            updateNowPlaying();
        } else {
            showEmpty();
        }
    }

    private void onLoadError(Throwable error) {
        mSwipe.setRefreshing(false);
        if (!mShelfViews.isEmpty()) {
            // A refresh failed over content the user can still browse: keep it, say so quietly.
            MobileSnackbar.show(this, R.string.mobile_music_error_title);
            return;
        }
        if (!YouTubeSignInService.instance().isSigned()) {
            showSignIn();
        } else {
            showError();
        }
    }

    // ---------------------------------------------------------------------------------
    // States
    // ---------------------------------------------------------------------------------

    private void showLoading() {
        if (!mShelfViews.isEmpty()) {
            return;
        }
        mState.setVisibility(View.VISIBLE);
        mStateProgress.setVisibility(View.VISIBLE);
        mStateIcon.setVisibility(View.GONE);
        mStateTitle.setVisibility(View.GONE);
        mStateMessage.setVisibility(View.GONE);
        mStateAction.setVisibility(View.GONE);
    }

    private void showSignIn() {
        showState(R.drawable.ic_mobile_account, R.string.mobile_music_sign_in_title,
                R.string.mobile_music_sign_in_message, R.string.mobile_music_sign_in_button,
                () -> AccountsSheet.show(this, () -> {
                    if (YouTubeSignInService.instance().isSigned()) {
                        loadMusic(false);
                    }
                }));
    }

    private void showEmpty() {
        showState(R.drawable.ic_mobile_empty, R.string.mobile_music_empty_title,
                R.string.mobile_music_empty_message, R.string.mobile_music_retry,
                () -> loadMusic(false));
    }

    private void showError() {
        showState(R.drawable.ic_mobile_empty, R.string.mobile_music_error_title,
                R.string.mobile_music_error_message, R.string.mobile_music_retry,
                () -> loadMusic(false));
    }

    private void showState(int iconRes, int titleRes, int messageRes, int actionRes, Runnable action) {
        mState.setVisibility(View.VISIBLE);
        mStateProgress.setVisibility(View.GONE);
        mStateIcon.setImageResource(iconRes);
        mStateIcon.setVisibility(View.VISIBLE);
        mStateTitle.setText(titleRes);
        mStateTitle.setVisibility(View.VISIBLE);
        mStateMessage.setText(messageRes);
        mStateMessage.setVisibility(View.VISIBLE);
        mStateAction.setText(actionRes);
        mStateAction.setOnClickListener(v -> action.run());
        mStateAction.setVisibility(View.VISIBLE);
    }

    private void hideState() {
        mState.setVisibility(View.GONE);
    }

    // ---------------------------------------------------------------------------------
    // Now playing (mini-player bridge)
    // ---------------------------------------------------------------------------------

    private void updateNowPlaying() {
        Utils.removeCallbacks(mNowPlayingTick);

        if (isFinishing() || isDestroyed()) {
            return;
        }

        ExoPlayer player = MiniPlayerBridge.getPlayer();
        Video video = MiniPlayerBridge.getVideo();
        if (player == null || video == null) {
            hideNowPlaying();
            return;
        }

        mNowPlaying.setVisibility(View.VISIBLE);
        mNowPlayingTitle.setText(video.getTitle());
        String author = video.getAuthor();
        mNowPlayingAuthor.setText(author);
        mNowPlayingAuthor.setVisibility(author == null || author.isEmpty() ? View.GONE : View.VISIBLE);

        boolean playing = player.getPlayWhenReady()
                && player.getPlaybackState() != Player.STATE_ENDED
                && player.getPlaybackState() != Player.STATE_IDLE;
        mNowPlayingPlayPause.setImageResource(playing
                ? R.drawable.ic_player_pause : R.drawable.ic_player_play);

        long duration = player.getDuration();
        if (duration > 0) {
            mNowPlayingProgress.setProgress((int) (player.getCurrentPosition() * 1000 / duration));
        }

        bindNowPlayingArt(video);
        Utils.postDelayed(mNowPlayingTick, NOW_PLAYING_TICK_MS);
    }

    private void bindNowPlayingArt(Video video) {
        String url = video.getCardImageUrl();
        if (url == null ? mBoundNowPlayingArtUrl == null : url.equals(mBoundNowPlayingArtUrl)) {
            return;
        }
        mBoundNowPlayingArtUrl = url;
        Glide.with(this)
                .load(url)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .centerCrop()
                .transition(com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade(140))
                .into(mNowPlayingArt);
    }

    private void hideNowPlaying() {
        Utils.removeCallbacks(mNowPlayingTick);
        mNowPlaying.setVisibility(View.GONE);
    }

    // ---------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------

    @Override
    protected void onResume() {
        super.onResume();
        MiniPlayerBridge.registerMiniHost(this);
        if (!mLoaded) {
            loadMusic(false);
        } else {
            updateNowPlaying();
        }
    }

    @Override
    protected void onPause() {
        mSwipe.setRefreshing(false);
        hideNowPlaying();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        MiniPlayerBridge.unregisterMiniHost(this);
        if (mLoad != null) {
            mLoad.dispose();
            mLoad = null;
        }
        Utils.removeCallbacks(mNowPlayingTick);
        super.onDestroy();
    }

    // ---------------------------------------------------------------------------------
    // MiniPlayerBridge.MiniHost
    // ---------------------------------------------------------------------------------

    /**
     * The music strip is artwork + controls, not a live video card, so there is no surface to
     * pre-render. Returning false lets the player take its ordinary "no retained card" path and
     * reorder this Activity to the front; {@link #onResume} then docks the strip.
     */
    @Override
    public boolean prepareMiniPlayerForHandoff(Runnable onDrawn) {
        return false;
    }

    @Override
    public Class<?> getMiniHostViewClass() {
        return MobileMusicActivity.class;
    }

    @Override
    public int getMiniCardBottomOffsetPx() {
        return Math.round(64 * getResources().getDisplayMetrics().density);
    }
}
