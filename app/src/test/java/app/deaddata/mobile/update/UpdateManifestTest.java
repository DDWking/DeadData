package app.deaddata.mobile.update;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import app.deaddata.mobile.BuildConfig;
import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * The update manifest decides whether the app offers a download, so malformed or
 * downgrade manifests must be rejected before the user sees anything.
 */
public class UpdateManifestTest {
    private static final String SHA =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private static JSONObject manifest(String json) {
        try {
            return new JSONObject(json);
        } catch (Exception error) {
            throw new AssertionError("invalid test payload: " + json, error);
        }
    }

    private static String manifestWith(int versionCode, String versionName,
                                       String apkUrl, String sha256) {
        return "{\"versionCode\":" + versionCode
                + ",\"versionName\":\"" + versionName
                + "\",\"apkUrl\":\"" + apkUrl
                + "\",\"sha256\":\"" + sha256
                + "\",\"releaseNotes\":\"notes\"}";
    }

    @Test
    public void parsesAndNormalisesAValidManifest() {
        UpdateClient.UpdateInfo info = UpdateClient.parseManifest(manifest(manifestWith(
                10, " 1.4.1 ", " https://example.com/DeadData-1.4.1.apk ",
                " " + SHA.toUpperCase(Locale.ROOT) + " ")));

        assertEquals(10, info.versionCode);
        assertEquals("1.4.1", info.versionName);
        assertEquals("https://example.com/DeadData-1.4.1.apk", info.apkUrl);
        assertEquals(SHA, info.sha256);
        assertEquals("notes", info.releaseNotes);
    }

    @Test
    public void missingReleaseNotesDefaultToEmptyText() {
        UpdateClient.UpdateInfo info = UpdateClient.parseManifest(manifest(manifestWith(
                10, "1.4.1", "https://example.com/a.apk", SHA)));

        assertEquals("notes", info.releaseNotes);
        JSONObject withoutNotes = manifest("{\"versionCode\":10,\"versionName\":\"1.4.1\","
                + "\"apkUrl\":\"https://example.com/a.apk\",\"sha256\":\"" + SHA + "\"}");
        assertEquals("", UpdateClient.parseManifest(withoutNotes).releaseNotes);
    }

    @Test
    public void integerVersionCodeIsRequired() {
        JSONObject missing = manifest("{\"versionName\":\"1.4.1\","
                + "\"apkUrl\":\"https://example.com/a.apk\",\"sha256\":\"" + SHA + "\"}");
        assertThrows(IllegalArgumentException.class, () -> UpdateClient.parseManifest(missing));

        JSONObject zero = manifest(manifestWith(0, "1.4.1", "https://example.com/a.apk", SHA));
        assertThrows(IllegalArgumentException.class, () -> UpdateClient.parseManifest(zero));
    }

    @Test
    public void blankVersionNameIsRejected() {
        JSONObject blank = manifest(manifestWith(10, "   ", "https://example.com/a.apk", SHA));
        assertThrows(IllegalArgumentException.class, () -> UpdateClient.parseManifest(blank));
    }

    @Test
    public void blankApkUrlIsRejected() {
        JSONObject blank = manifest(manifestWith(10, "1.4.1", "", SHA));
        assertThrows(IllegalArgumentException.class, () -> UpdateClient.parseManifest(blank));
    }

    @Test
    public void sha256MustBeSixtyFourHexCharacters() {
        JSONObject shortHash = manifest(manifestWith(
                10, "1.4.1", "https://example.com/a.apk", SHA.substring(0, 63)));
        assertThrows(IllegalArgumentException.class,
                () -> UpdateClient.parseManifest(shortHash));

        JSONObject nonHex = manifest(manifestWith(
                10, "1.4.1", "https://example.com/a.apk", "g" + SHA.substring(1)));
        assertThrows(IllegalArgumentException.class,
                () -> UpdateClient.parseManifest(nonHex));

        JSONObject emptyHash = manifest(manifestWith(10, "1.4.1",
                "https://example.com/a.apk", ""));
        assertThrows(IllegalArgumentException.class,
                () -> UpdateClient.parseManifest(emptyHash));
    }

    @Test
    public void plainHttpDownloadUrlIsRejected() {
        JSONObject insecure = manifest(manifestWith(
                10, "1.4.1", "http://example.com/a.apk", SHA));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> UpdateClient.parseManifest(insecure));
        assertTrue(error.getMessage().contains("HTTPS"));
    }

    @Test
    public void newerVersionCodesAreOfferedAndCurrentOnesAreNot() {
        int installed = BuildConfig.VERSION_CODE;
        UpdateClient.UpdateInfo newer = new UpdateClient.UpdateInfo(
                installed + 1, "x", "https://example.com/a.apk", SHA, "");
        UpdateClient.UpdateInfo same = new UpdateClient.UpdateInfo(
                installed, "x", "https://example.com/a.apk", SHA, "");
        UpdateClient.UpdateInfo older = new UpdateClient.UpdateInfo(
                installed - 1, "x", "https://example.com/a.apk", SHA, "");

        assertTrue(UpdateClient.isNewer(newer));
        assertFalse(UpdateClient.isNewer(same));
        assertFalse(UpdateClient.isNewer(older));
        assertFalse(UpdateClient.isNewer(null));
    }

    /**
     * The release tooling has two producers (CI python and the local PowerShell
     * script). Both fixtures were generated by those tools for the same APK, so
     * this pins that the app accepts both and that they agree with each other.
     */
    @Test
    public void acceptsManifestsProducedByTheReleaseTooling() throws Exception {
        UpdateClient.UpdateInfo fromCi =
                UpdateClient.parseManifest(manifest(readResource("/manifest-from-ci.json")));
        UpdateClient.UpdateInfo fromPowerShell = UpdateClient.parseManifest(
                manifest(readResource("/manifest-from-powershell.json")));

        assertEquals(fromCi.versionCode, fromPowerShell.versionCode);
        assertEquals(fromCi.versionName, fromPowerShell.versionName);
        assertEquals(fromCi.apkUrl, fromPowerShell.apkUrl);
        assertEquals(fromCi.sha256, fromPowerShell.sha256);
        assertEquals(fromCi.releaseNotes, fromPowerShell.releaseNotes);

        assertTrue(fromCi.versionCode >= 1);
        assertTrue(fromCi.apkUrl.startsWith("https://"));
        assertFalse(fromCi.releaseNotes.trim().isEmpty());
        assertTrue(fromCi.sha256.matches("[0-9a-f]{64}"));
    }

    private static String readResource(String name) throws Exception {
        try (InputStream stream = UpdateManifestTest.class.getResourceAsStream(name)) {
            assertNotNull("missing test resource " + name, stream);
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int count;
            while ((count = stream.read(chunk)) != -1) {
                buffer.write(chunk, 0, count);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
