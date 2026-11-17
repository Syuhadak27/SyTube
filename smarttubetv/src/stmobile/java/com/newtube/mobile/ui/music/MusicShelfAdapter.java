package com.newtube.mobile.ui.music;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DecodeFormat;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.newtube.mobile.ui.common.GlassMaterial;

import java.util.List;

/**
 * Square-artwork card adapter for one music shelf (songs, mixes, playlists, artists).
 *
 * <p>Deliberately smaller than the Home {@code VideoCardAdapter}: music rows are
 * horizontal, the artwork is 1:1, and the meta line is the artist - not the video
 * "views · date" line. Taps and long-presses are reported to the screen, which routes
 * them through the same presenters as every other touch surface.</p>
 */
public class MusicShelfAdapter extends ListAdapter<Video, MusicShelfAdapter.Holder> {
    public interface Listener {
        void onMusicClick(Video video);
        boolean onMusicLongClick(Video video);
    }

    private static final DiffUtil.ItemCallback<Video> DIFF = new DiffUtil.ItemCallback<Video>() {
        @Override
        public boolean areItemsTheSame(@NonNull Video oldItem, @NonNull Video newItem) {
            return oldItem.equals(newItem);
        }

        @Override
        public boolean areContentsTheSame(@NonNull Video oldItem, @NonNull Video newItem) {
            return oldItem == newItem;
        }
    };

    private final Listener mListener;

    public MusicShelfAdapter(Listener listener) {
        super(DIFF);
        mListener = listener;
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new Holder(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_music_card, parent, false), mListener);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        holder.bind(getItem(position));
    }

    /** Replace the whole shelf without the change animation of a single append. */
    public void submit(List<Video> videos) {
        submitList(new java.util.ArrayList<>(videos));
    }

    public static class Holder extends RecyclerView.ViewHolder {
        private final ImageView mArt;
        private final ImageView mBadge;
        private final TextView mTitle;
        private final TextView mMeta;
        private final Listener mListener;
        private Video mVideo;
        private String mBoundUrl;

        Holder(@NonNull View itemView, Listener listener) {
            super(itemView);
            mArt = itemView.findViewById(R.id.music_card_art);
            mBadge = itemView.findViewById(R.id.music_card_badge);
            mTitle = itemView.findViewById(R.id.music_card_title);
            mMeta = itemView.findViewById(R.id.music_card_meta);
            mListener = listener;

            itemView.setOnClickListener(v -> {
                if (mVideo != null && mListener != null) {
                    mListener.onMusicClick(mVideo);
                }
            });
            itemView.setOnLongClickListener(v -> mVideo != null && mListener != null
                    && mListener.onMusicLongClick(mVideo));
            GlassMaterial.applyCardTouchFeedback(itemView);
        }

        void bind(Video video) {
            mVideo = video;
            Context context = itemView.getContext();

            mTitle.setText(video.getTitle());

            String meta = video.getAuthor();
            if (meta == null || meta.isEmpty()) {
                meta = video.hasPlaylist() || video.isPlaylistAsChannel()
                        ? context.getString(R.string.mobile_music_playlist)
                        : context.getString(R.string.mobile_music_artist);
            }
            mMeta.setText(meta);

            boolean playlist = video.hasPlaylist() || video.isPlaylistAsChannel() || video.hasNestedItems();
            mBadge.setVisibility(playlist ? View.VISIBLE : View.GONE);

            bindArt(video);
        }

        private void bindArt(Video video) {
            com.newtube.mobile.ui.common.LocalThumbnails.fill(video);
            String url = video.getCardImageUrl();
            if (url == null ? mBoundUrl == null : url.equals(mBoundUrl)) {
                return;
            }
            mBoundUrl = url;

            Glide.with(itemView)
                    .load(url)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .format(DecodeFormat.PREFER_RGB_565)
                    .centerCrop()
                    .transition(com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade(140))
                    .into(mArt);
        }
    }
}
