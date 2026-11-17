package com.newtube.mobile.ui.sync;

import android.content.Context;

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.mediaserviceinterfaces.oauth.Account;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.BrowseSection;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.VideoGroup;
import com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.youtubeapi.service.YouTubeSignInService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * Pulls the signed-in account's watch history and suggested videos into SyTube.
 *
 * <p>Both shelves come from the account itself through {@code ContentService}: history from
 * {@code getHistoryObserve()}, suggestions from {@code getRecommended()} (the first Home shelf).
 * They are converted to the app's {@link Video} cards and persisted per account by
 * {@link AccountSyncStore}, so the Sync screen shows the account's data even on a cold start.</p>
 */
final class AccountSyncManager {
    interface Callback {
        void onSuccess(int historyCount, int recommendedCount, long syncedAt);
        void onError(Throwable error);
        void onSignedOut();
    }

    private AccountSyncManager() {
    }

    static boolean isSignedIn() {
        return YouTubeSignInService.instance().isSigned();
    }

    static Disposable sync(Context context, Callback callback) {
        if (!isSignedIn()) {
            callback.onSignedOut();
            return null;
        }

        Account account = MediaServiceManager.instance().getSelectedAccount();
        // Each source degrades to an empty shelf on its own: an account without recommendations
        // (or a transient Home failure) must not prevent history from being synced, and vice versa.
        Observable<List<Video>> history = MediaServiceManager.instance().getContentService()
                .getHistoryObserve()
                .map(group -> toVideos(group, MediaGroup.TYPE_HISTORY, R.drawable.icon_history))
                .onErrorResumeNext(Observable.just(Collections.<Video>emptyList()));
        Observable<List<Video>> recommended = MediaServiceManager.instance().getContentService()
                .getRecommendedObserve()
                .map(group -> toVideos(group, MediaGroup.TYPE_RECOMMENDED, R.drawable.icon_home))
                .onErrorResumeNext(Observable.just(Collections.<Video>emptyList()));

        return Observable.zip(history, recommended, Result::new)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(result -> {
                    long now = System.currentTimeMillis();
                    AccountSyncStore.save(context, account, result.history, result.recommended);
                    callback.onSuccess(result.history.size(), result.recommended.size(), now);
                }, callback::onError);
    }

    private static List<Video> toVideos(MediaGroup group, int sectionId, int iconRes) {
        if (group == null || group.getMediaItems() == null || group.getMediaItems().isEmpty()) {
            return new ArrayList<>();
        }
        String title = group.getTitle();
        if (title == null || title.isEmpty()) {
            title = sectionId == MediaGroup.TYPE_HISTORY ? "History" : "Recommended";
        }
        BrowseSection section = new BrowseSection(sectionId, title, BrowseSection.TYPE_GRID, iconRes);
        List<Video> videos = VideoGroup.from(group, section).getVideos();
        return videos == null ? new ArrayList<>() : new ArrayList<>(videos);
    }

    private static final class Result {
        final List<Video> history;
        final List<Video> recommended;

        Result(List<Video> history, List<Video> recommended) {
            this.history = history;
            this.recommended = recommended;
        }
    }
}
