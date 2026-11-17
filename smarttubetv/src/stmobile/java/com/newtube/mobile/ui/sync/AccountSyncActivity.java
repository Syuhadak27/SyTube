package com.newtube.mobile.ui.sync;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.button.MaterialButton;
import com.liskovsoft.mediaserviceinterfaces.oauth.Account;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.VideoActionPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs.menu.VideoMenuPresenter;
import com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.browse.AccountsSheet;
import com.newtube.mobile.ui.common.MobileActivity;
import com.newtube.mobile.ui.common.MobileSnackbar;
import com.newtube.mobile.ui.music.MusicShelfAdapter;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import io.reactivex.rxjava3.disposables.Disposable;

/**
 * "Sync from account" screen: everything personal is pulled from the account that is signed in.
 *
 * <p>Two shelves - watch history and suggested videos - are fetched from the account through
 * {@link AccountSyncManager} and cached per account by {@link AccountSyncStore}. The cached shelves
 * are painted first, then a sync runs in the background whenever the screen opens, so the account's
 * latest data lands here without the user having to hunt through the TV-style sections.</p>
 */
public class AccountSyncActivity extends MobileActivity {
    private SwipeRefreshLayout mSwipe;
    private TextView mAccountText;
    private TextView mStatus;
    private MaterialButton mSyncButton;
    private ProgressBar mSyncProgress;
    private LinearLayout mHistoryShelf;
    private LinearLayout mRecommendedShelf;

    @Nullable
    private Disposable mSync;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_account_sync);
        registerBackHandler(this::finish);

        mSwipe = findViewById(R.id.mobile_sync_swipe);
        mAccountText = findViewById(R.id.mobile_sync_account);
        mStatus = findViewById(R.id.mobile_sync_status);
        mSyncButton = findViewById(R.id.mobile_sync_button);
        mSyncProgress = findViewById(R.id.mobile_sync_progress);
        mHistoryShelf = findViewById(R.id.mobile_sync_history_shelf);
        mRecommendedShelf = findViewById(R.id.mobile_sync_recommended_shelf);

        findViewById(R.id.mobile_sync_back).setOnClickListener(v -> getOnBackPressedDispatcher().onBackPressed());
        mSyncButton.setOnClickListener(v -> {
            if (!AccountSyncManager.isSignedIn()) {
                AccountsSheet.show(this, this::onAccountChanged);
                return;
            }
            startSync();
        });
        mSwipe.setOnRefreshListener(this::startSync);
        mSwipe.setColorSchemeColors(getResources().getColor(R.color.mobile_color_primary));
        mSwipe.setProgressBackgroundColorSchemeColor(getResources().getColor(R.color.mobile_color_surface));

        updateAccount();
        renderCached();
        if (AccountSyncManager.isSignedIn()) {
            startSync();
        }
    }

    @Override
    protected void onDestroy() {
        if (mSync != null && !mSync.isDisposed()) {
            mSync.dispose();
        }
        super.onDestroy();
    }

    private void onAccountChanged() {
        updateAccount();
        renderCached();
        if (AccountSyncManager.isSignedIn()) {
            startSync();
        }
    }

    private void updateAccount() {
        Account account = MediaServiceManager.instance().getSelectedAccount();
        String label = AccountSyncStore.accountLabel(account);
        if (label == null || label.isEmpty()) {
            label = getString(R.string.mobile_sync_signed_out);
        }
        mAccountText.setText(label);
        mSyncButton.setText(AccountSyncManager.isSignedIn()
                ? R.string.mobile_sync_now : R.string.mobile_sync_sign_in);
    }

    private void renderCached() {
        Account account = MediaServiceManager.instance().getSelectedAccount();
        AccountSyncStore.Snapshot snapshot = AccountSyncStore.load(this, account);
        bindShelf(mHistoryShelf, R.string.mobile_sync_history, snapshot.history);
        bindShelf(mRecommendedShelf, R.string.mobile_sync_recommended, snapshot.recommended);
        if (snapshot.syncedAt > 0) {
            mStatus.setText(getString(R.string.mobile_sync_status,
                    snapshot.history.size(), snapshot.recommended.size(),
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(snapshot.syncedAt))));
        } else {
            mStatus.setText(AccountSyncManager.isSignedIn()
                    ? R.string.mobile_sync_waiting : R.string.mobile_sync_sign_in_message);
        }
    }

    private void bindShelf(LinearLayout container, int titleRes, List<Video> videos) {
        container.removeAllViews();
        View shelf = LayoutInflater.from(this).inflate(R.layout.item_music_shelf, container, false);
        ((TextView) shelf.findViewById(R.id.music_shelf_title)).setText(titleRes);
        RecyclerView list = shelf.findViewById(R.id.music_shelf_list);
        list.setLayoutManager(new LinearLayoutManager(this, RecyclerView.HORIZONTAL, false));
        list.setItemAnimator(null);
        MusicShelfAdapter adapter = new MusicShelfAdapter(new MusicShelfAdapter.Listener() {
            @Override
            public void onMusicClick(Video video) {
                VideoActionPresenter.instance(AccountSyncActivity.this).apply(video);
            }

            @Override
            public boolean onMusicLongClick(Video video) {
                VideoMenuPresenter.instance(AccountSyncActivity.this).showMenu(video);
                return true;
            }
        });
        list.setAdapter(adapter);
        adapter.submit(new ArrayList<>(videos));
        container.addView(shelf);

        if (videos.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.mobile_sync_empty_shelf);
            empty.setTextColor(getResources().getColor(R.color.mobile_color_on_surface_secondary));
            empty.setTextSize(14f);
            empty.setPadding(dp(16), 0, dp(16), dp(8));
            container.addView(empty);
        }
    }

    private void startSync() {
        if (mSync != null && !mSync.isDisposed()) {
            mSync.dispose();
        }
        setSyncing(true);
        mStatus.setText(R.string.mobile_sync_in_progress);
        mSync = AccountSyncManager.sync(this, new AccountSyncManager.Callback() {
            @Override
            public void onSuccess(int historyCount, int recommendedCount, long syncedAt) {
                setSyncing(false);
                renderCached();
            }

            @Override
            public void onError(Throwable error) {
                setSyncing(false);
                mStatus.setText(R.string.mobile_sync_error);
                MobileSnackbar.show(AccountSyncActivity.this, R.string.mobile_sync_error);
            }

            @Override
            public void onSignedOut() {
                setSyncing(false);
                updateAccount();
                mStatus.setText(R.string.mobile_sync_sign_in_message);
            }
        });
    }

    private void setSyncing(boolean syncing) {
        mSwipe.setRefreshing(syncing);
        mSyncProgress.setVisibility(syncing ? View.VISIBLE : View.GONE);
        mSyncButton.setEnabled(!syncing);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
