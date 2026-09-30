package max.plus.frp.library;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 版本 API：从 GitHub Releases 读取公开的 frp.aar，无需密钥。
 * https://github.com/jahen/frp/releases
 */
public class VersionApi {
    private static final String TAG = "VersionApi";
    private static final String RELEASES_URL =
            "https://api.github.com/repos/jahen/frp/releases?per_page=100";
    private static final String AAR_ASSET_NAME = "frp.aar";
    private static final Pattern SEMVER = Pattern.compile("(\\d+\\.\\d+\\.\\d+)");

    private OkHttpClient httpClient;

    public VersionApi() {
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    public void getVersions(VersionListListener listener) {
        Request request = new Request.Builder()
                .url(RELEASES_URL)
                .header("User-Agent", "frp-android")
                .header("Accept", "application/vnd.github+json")
                .get()
                .build();

        httpClient.newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(okhttp3.Call call, IOException e) {
                Log.e(TAG, "Failed to get GitHub releases", e);
                if (listener != null) {
                    listener.onError(e.getMessage());
                }
            }

            @Override
            public void onResponse(okhttp3.Call call, Response response) throws IOException {
                try {
                    if (!response.isSuccessful()) {
                        String errorMsg = "Failed to get versions: " + response.code();
                        Log.e(TAG, errorMsg);
                        if (listener != null) {
                            listener.onError(errorMsg);
                        }
                        return;
                    }

                    String jsonString = response.body().string();
                    JSONArray releases = new JSONArray(jsonString);
                    List<VersionInfo> versions = new ArrayList<>();
                    Set<String> seen = new HashSet<>();

                    for (int i = 0; i < releases.length(); i++) {
                        JSONObject release = releases.getJSONObject(i);
                        if (release.optBoolean("draft", false)) {
                            continue;
                        }
                        String version = extractVersion(
                                release.optString("name", ""),
                                release.optString("tag_name", ""));
                        if (version == null || seen.contains(version)) {
                            continue;
                        }
                        String downloadUrl = findAarUrl(release.optJSONArray("assets"));
                        if (downloadUrl == null) {
                            continue;
                        }
                        VersionInfo info = new VersionInfo();
                        info.version = version;
                        info.url = downloadUrl;
                        info.size = findAarSize(release.optJSONArray("assets"));
                        info.description = release.optString("body", "");
                        info.releaseDate = formatReleaseDate(release.optString("published_at", ""));
                        versions.add(info);
                        seen.add(version);
                    }

                    if (listener != null) {
                        listener.onSuccess(versions);
                    }
                } catch (JSONException e) {
                    Log.e(TAG, "Failed to parse GitHub releases", e);
                    if (listener != null) {
                        listener.onError(e.getMessage());
                    }
                } finally {
                    if (response.body() != null) {
                        response.close();
                    }
                }
            }
        });
    }

    private String extractVersion(String name, String tagName) {
        String fromName = firstSemver(name);
        if (fromName != null) {
            return fromName;
        }
        return firstSemver(tagName);
    }

    private String firstSemver(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        Matcher matcher = SEMVER.matcher(text);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private String findAarUrl(JSONArray assets) {
        JSONObject asset = findAarAsset(assets);
        if (asset == null) {
            return null;
        }
        String url = asset.optString("browser_download_url", "");
        return url.isEmpty() ? null : url;
    }

    private long findAarSize(JSONArray assets) {
        JSONObject asset = findAarAsset(assets);
        return asset == null ? 0 : asset.optLong("size", 0);
    }

    private JSONObject findAarAsset(JSONArray assets) {
        if (assets == null) {
            return null;
        }
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null) {
                continue;
            }
            if (AAR_ASSET_NAME.equalsIgnoreCase(asset.optString("name", ""))) {
                return asset;
            }
        }
        return null;
    }

    private String formatReleaseDate(String publishedAt) {
        if (publishedAt == null || publishedAt.length() < 10) {
            return "";
        }
        return publishedAt.substring(0, 10);
    }

    public interface VersionListListener {
        void onSuccess(List<VersionInfo> versions);

        void onError(String error);
    }

    public static class VersionInfo {
        public String version;
        public String url;
        public long size;
        public String description;
        public String releaseDate;
    }
}
