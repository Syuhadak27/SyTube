package com.newtube.mobile.ui.sync;

import com.liskovsoft.googlecommon.common.helpers.RetrofitHelper;
import com.liskovsoft.googlecommon.common.models.items.Thumbnail;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.youtubeapi.browse.v1.BrowseApi;
import com.liskovsoft.youtubeapi.browse.v1.models.sections.Section;
import com.liskovsoft.youtubeapi.browse.v1.models.sections.SectionList;
import com.liskovsoft.youtubeapi.common.helpers.PostDataHelper;
import com.liskovsoft.youtubeapi.common.models.V2.TileItem;
import com.liskovsoft.youtubeapi.common.models.items.ItemWrapper;
import com.liskovsoft.youtubeapi.common.models.items.VideoItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import retrofit2.Call;

/**
 * The account-personalised "For You" feed, ported from LePlay's InnerTube home feed.
 *
 * <p>LePlay asks YouTube for {@code FEwhat_to_watch} - the same browse id youtube.com uses for
 * its signed-in For You feed - instead of taking the first shelf of the TV Home page (which is
 * often a generic shelf and is why SyTube's old suggestions did not match the account). SyTube's
 * credential is the TV OAuth token, so the request goes through the TV browse surface that this
 * token is accepted on, with the exact same personalisation browse id.</p>
 */
final class AccountHomeFeed {
    private static final String TAG = AccountHomeFeed.class.getSimpleName();
    private static final String WHAT_TO_WATCH = "\"browseId\":\"FEwhat_to_watch\"";
    /** Enough cards for the sync shelf; the account feed is paginated on demand elsewhere. */
    private static final int MAX_VIDEOS = 60;

    private AccountHomeFeed() {
    }

    static List<Video> fetch() {
        try {
            BrowseApi api = RetrofitHelper.create(BrowseApi.class);
            String query = PostDataHelper.createQueryTV(WHAT_TO_WATCH);
            Call<SectionList> call = api.getSectionList(query);
            SectionList result = RetrofitHelper.get(call);
            if (result == null) {
                return Collections.emptyList();
            }

            List<Video> videos = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            List<Section> sections = result.getSections();
            if (sections != null) {
                for (Section section : sections) {
                    if (section == null || section.getItemWrappers() == null) {
                        continue;
                    }
                    for (ItemWrapper item : section.getItemWrappers()) {
                        Video video = toVideo(item);
                        if (video == null || video.videoId == null || !seen.add(video.videoId)) {
                            continue;
                        }
                        videos.add(video);
                        if (videos.size() >= MAX_VIDEOS) {
                            return videos;
                        }
                    }
                }
            }
            Log.d(TAG, "FEwhat_to_watch returned %s videos", videos.size());
            return videos;
        } catch (Throwable e) {
            // The caller falls back to the previous ContentService shelf; never break the sync.
            Log.d(TAG, "FEwhat_to_watch failed: %s", e.getMessage());
            return Collections.emptyList();
        }
    }

    private static Video toVideo(ItemWrapper wrapper) {
        if (wrapper == null) {
            return null;
        }
        if (wrapper.getVideoItem() != null) {
            return fromVideoItem(wrapper.getVideoItem());
        }
        if (wrapper.getTileItem() != null) {
            return fromTileItem(wrapper.getTileItem());
        }
        return null;
    }

    private static Video fromVideoItem(VideoItem item) {
        Video video = new Video();
        video.videoId = item.getVideoId();
        video.playlistId = item.getPlaylistId();
        video.title = item.getTitle();
        video.author = item.getUserName() != null ? item.getUserName().toString() : null;
        video.channelId = item.getChannelId();
        video.cardImageUrl = bestThumbnail(item.getThumbnails());
        video.bgImageUrl = video.cardImageUrl;
        video.percentWatched = item.getPercentWatched();
        video.startTimeSeconds = item.getStartTimeSeconds();
        video.badge = item.getBadgeText();
        video.isLive = item.isLive();
        video.isUpcoming = item.isUpcoming();
        video.isMovie = item.isMovie();
        video.secondTitle = meta(item.getViewCountText(), item.getPublishedDate());
        return video;
    }

    private static Video fromTileItem(TileItem item) {
        Video video = new Video();
        video.videoId = item.getVideoId();
        video.playlistId = item.getPlaylistId();
        video.title = item.getTitle();
        video.author = item.getUserName();
        video.channelId = item.getChannelId();
        video.cardImageUrl = bestThumbnail(item.getThumbnails());
        video.bgImageUrl = video.cardImageUrl;
        video.percentWatched = item.getPercentWatched();
        video.startTimeSeconds = item.getStartTimeSeconds();
        video.badge = item.getBadgeText();
        video.isLive = item.isLive();
        video.isUpcoming = item.isUpcoming();
        video.isMovie = item.isMovie();
        video.secondTitle = meta(item.getViewCountText(), item.getPublishedTime());
        return video;
    }

    private static String bestThumbnail(List<Thumbnail> thumbnails) {
        if (thumbnails == null || thumbnails.isEmpty()) {
            return null;
        }
        Thumbnail best = null;
        for (Thumbnail thumbnail : thumbnails) {
            if (thumbnail != null && (best == null || thumbnail.getWidth() > best.getWidth())) {
                best = thumbnail;
            }
        }
        return best != null ? best.getUrl() : null;
    }

    private static CharSequence meta(CharSequence views, String published) {
        if (views == null || views.length() == 0) {
            return published;
        }
        if (published == null || published.isEmpty()) {
            return views;
        }
        return views + " • " + published;
    }
}
