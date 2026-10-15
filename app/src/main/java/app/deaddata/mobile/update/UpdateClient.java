package app.deaddata.mobile.update;

import android.content.Context;

import app.deaddata.mobile.BuildConfig;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

public final class UpdateClient {
    public static final String PREFS = "deaddata";
    private static final String MANIFEST_URL =
            "https://github.com/DDWking/DeadData/releases/latest/download/update.json";
    private static final int MAX_APK_BYTES = 300 * 1024 * 1024;

    private UpdateClient() {
    }

    public static UpdateInfo check() throws Exception {
        JSONObject manifest = new JSONObject(readUrl(MANIFEST_URL, 1024 * 1024));
        int versionCode = manifest.optInt("versionCode", -1);
        String versionName = manifest.optString("versionName", "").trim();
        String apkUrl = manifest.optString("apkUrl", "").trim();
        String sha256 = manifest.optString("sha256", "").trim().toLowerCase(Locale.ROOT);
        String notes = manifest.optString("releaseNotes", "").trim();
        if (versionCode < 1 || versionName.isEmpty() || apkUrl.isEmpty()
                || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("更新清单缺少有效的版本号、APK 地址或 SHA-256");
        }
        requireHttps(apkUrl);
        if (versionCode <= BuildConfig.VERSION_CODE) return null;
        return new UpdateInfo(versionCode, versionName, apkUrl, sha256, notes);
    }

    public static File download(Context context, UpdateInfo update, Progress progress)
            throws Exception {
        requireHttps(update.apkUrl);
        File directory = new File(context.getCacheDir(), "updates");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("无法创建更新缓存目录");
        }
        File apk = new File(directory, "DeadData-" + update.versionCode + ".apk");
        HttpURLConnection connection = openHttpsConnection(update.apkUrl, 15000, 30000, null);
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("下载失败 (" + status + ")");
            }
            long total = connection.getContentLengthLong();
            if (total > MAX_APK_BYTES) throw new IllegalStateException("更新包超过 300 MB");
            long written = 0;
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 FileOutputStream output = new FileOutputStream(apk)) {
                byte[] buffer = new byte[32768];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    written += count;
                    if (written > MAX_APK_BYTES) throw new IllegalStateException("更新包超过 300 MB");
                    output.write(buffer, 0, count);
                    if (progress != null) progress.onProgress(written, total);
                }
                output.getFD().sync();
            }
            String actualHash = sha256(apk);
            if (!actualHash.equals(update.sha256)) {
                apk.delete();
                throw new SecurityException("更新包校验失败，已取消安装");
            }
            return apk;
        } finally {
            connection.disconnect();
        }
    }

    private static String readUrl(String target, int limit) throws Exception {
        HttpURLConnection connection = openHttpsConnection(
                target, 10000, 15000, "application/json");
        try {
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("更新服务返回错误 (" + status + ")");
            }
            try (InputStream input = connection.getInputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                StringBuilder result = new StringBuilder();
                while ((count = input.read(buffer)) != -1) {
                    if (result.length() + count > limit) {
                        throw new IllegalStateException("更新清单过大");
                    }
                    result.append(new String(buffer, 0, count, java.nio.charset.StandardCharsets.UTF_8));
                }
                return result.toString();
            }
        } finally {
            connection.disconnect();
        }
    }

    private static HttpURLConnection openHttpsConnection(String target, int connectTimeout,
                                                         int readTimeout, String accept)
            throws Exception {
        String current = target;
        for (int redirects = 0; redirects <= 5; redirects++) {
            requireHttps(current);
            HttpURLConnection connection =
                    (HttpURLConnection) new URL(current).openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(connectTimeout);
            connection.setReadTimeout(readTimeout);
            connection.setRequestProperty("User-Agent",
                    "DeadData-Android/" + BuildConfig.VERSION_NAME);
            if (accept != null) connection.setRequestProperty("Accept", accept);
            int status = connection.getResponseCode();
            if (status >= 300 && status < 400) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null || redirects == 5) {
                    throw new IllegalStateException("更新服务重定向失败");
                }
                current = new URL(new URL(current), location).toString();
                requireHttps(current);
                continue;
            }
            return connection;
        }
        throw new IllegalStateException("更新服务重定向次数过多");
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[32768];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }

    private static void requireHttps(String url) {
        if (url == null || !url.startsWith("https://")) {
            throw new IllegalArgumentException("更新地址必须使用 HTTPS");
        }
    }

    public static final class UpdateInfo {
        public final int versionCode;
        public final String versionName;
        public final String apkUrl;
        public final String sha256;
        public final String releaseNotes;

        public UpdateInfo(int versionCode, String versionName, String apkUrl,
                          String sha256, String releaseNotes) {
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.apkUrl = apkUrl;
            this.sha256 = sha256;
            this.releaseNotes = releaseNotes;
        }
    }

    public interface Progress {
        void onProgress(long downloaded, long total);
    }
}
