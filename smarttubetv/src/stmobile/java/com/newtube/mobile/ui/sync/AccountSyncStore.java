package com.newtube.mobile.ui.sync;

import android.content.Context;

import com.liskovsoft.mediaserviceinterfaces.oauth.Account;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Disk snapshots of the data synced from the signed-in YouTube account.
 *
 * <p>History and recommendations are fetched by {@link AccountSyncManager} from the account,
 * then written here so the Sync screen can paint them instantly on the next cold start (and even
 * while signed out, until the user switches accounts). Snapshots are keyed by account: switching
 * accounts never shows the previous account's watch history.</p>
 */
final class AccountSyncStore {
    private static final String TAG = AccountSyncStore.class.getSimpleName();
    private static final String DIR = "account_sync";
    private static final String HISTORY_FILE = "history.snap";
    private static final String RECOMMENDED_FILE = "recommended.snap";
    private static final String META_FILE = "meta.txt";
    /** A couple of screenfuls per shelf is enough for an offline first paint. */
    private static final int MAX_ITEMS = 60;

    private AccountSyncStore() {
    }

    static final class Snapshot {
        final List<Video> history;
        final List<Video> recommended;
        final long syncedAt;
        final String accountLabel;

        Snapshot(List<Video> history, List<Video> recommended, long syncedAt, String accountLabel) {
            this.history = history;
            this.recommended = recommended;
            this.syncedAt = syncedAt;
            this.accountLabel = accountLabel;
        }

        static Snapshot empty() {
            return new Snapshot(Collections.emptyList(), Collections.emptyList(), 0L, null);
        }
    }

    static String accountKey(Account account) {
        if (account == null) {
            return "signed_out";
        }
        String raw = account.getEmail();
        if (raw == null || raw.isEmpty()) {
            raw = account.getName();
        }
        if (raw == null || raw.isEmpty()) {
            raw = "account_" + account.getId();
        }
        return account.getId() + "_" + raw.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    static String accountLabel(Account account) {
        if (account == null) {
            return null;
        }
        String name = account.getName();
        String email = account.getEmail();
        if (name != null && !name.isEmpty()) {
            return email != null && !email.isEmpty() && !email.equals(name) ? name + " <" + email + ">" : name;
        }
        return email != null && !email.isEmpty() ? email : null;
    }

    static void save(Context context, Account account, List<Video> history, List<Video> recommended) {
        File dir = snapshotDir(context, account);
        if (dir == null) {
            return;
        }
        long now = System.currentTimeMillis();
        write(dir, HISTORY_FILE, history);
        write(dir, RECOMMENDED_FILE, recommended);
        String label = accountLabel(account);
        writeRaw(dir, META_FILE, accountKey(account) + "\n" + now + "\n" + (label == null ? "" : label.replace('\n', ' ')));
    }

    static Snapshot load(Context context, Account account) {
        File dir = snapshotDir(context, account);
        if (dir == null) {
            return Snapshot.empty();
        }
        File meta = new File(dir, META_FILE);
        if (!meta.isFile()) {
            return Snapshot.empty();
        }
        String[] parts = readRaw(meta).split("\n", -1);
        // Never show another account's snapshot, even if its files were left behind.
        if (parts.length == 0 || !accountKey(account).equals(parts[0])) {
            return Snapshot.empty();
        }
        long timestamp = 0L;
        try {
            timestamp = parts.length > 1 ? Long.parseLong(parts[1]) : 0L;
        } catch (NumberFormatException e) {
            Log.d(TAG, "Bad sync timestamp: %s", e.getMessage());
        }
        String label = parts.length > 2 ? parts[2] : null;
        return new Snapshot(readVideos(new File(dir, HISTORY_FILE)),
                readVideos(new File(dir, RECOMMENDED_FILE)), timestamp,
                label == null || label.isEmpty() ? null : label);
    }

    private static File snapshotDir(Context context, Account account) {
        File dir = new File(context.getApplicationContext().getFilesDir(), DIR + File.separator + accountKey(account));
        return dir.isDirectory() || dir.mkdirs() ? dir : null;
    }

    private static void write(File dir, String name, List<Video> videos) {
        List<Video> capped = videos == null ? Collections.emptyList()
                : videos.subList(0, Math.min(videos.size(), MAX_ITEMS));
        writeRaw(dir, name, Helpers.mergeList(capped));
    }

    private static void writeRaw(File dir, String name, String content) {
        File tmp = new File(dir, name + ".tmp");
        File target = new File(dir, name);
        try (Writer out = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            out.write(content);
        } catch (IOException | RuntimeException e) {
            Log.d(TAG, "Sync snapshot write failed (%s): %s", name, e.getMessage());
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(target)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    private static List<Video> readVideos(File file) {
        if (!file.isFile()) {
            return Collections.emptyList();
        }
        List<Video> result = new ArrayList<>();
        for (String spec : Helpers.splitArray(readRaw(file))) {
            Video video = Video.fromString(spec);
            if (video != null && (video.videoId != null || video.playlistId != null || video.channelId != null)) {
                result.add(video);
            }
        }
        return result;
    }

    private static String readRaw(File file) {
        try {
            byte[] raw = new byte[(int) file.length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
                int off = 0;
                while (off < raw.length) {
                    int read = in.read(raw, off, raw.length - off);
                    if (read < 0) {
                        break;
                    }
                    off += read;
                }
            }
            return new String(raw, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            Log.d(TAG, "Sync snapshot read failed (%s): %s", file.getName(), e.getMessage());
            return "";
        }
    }
}
