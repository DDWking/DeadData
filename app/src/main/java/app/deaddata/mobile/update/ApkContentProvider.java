package app.deaddata.mobile.update;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

public final class ApkContentProvider extends ContentProvider {
    private static final String MIME_APK = "application/vnd.android.package-archive";

    public static Uri uriFor(Context context, File file) {
        String authority = context.getPackageName() + ".apkprovider";
        return new Uri.Builder()
                .scheme("content")
                .authority(authority)
                .appendPath(file.getName())
                .build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        return MIME_APK;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        File file = resolve(uri);
        String[] columns = projection == null
                ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}
                : projection;
        MatrixCursor cursor = new MatrixCursor(columns);
        Object[] values = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) values[i] = file.getName();
            if (OpenableColumns.SIZE.equals(columns[i])) values[i] = file.length();
        }
        cursor.addRow(values);
        return cursor;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read-only");
        File file = resolve(uri);
        if (!file.isFile()) throw new FileNotFoundException(file.getName());
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    private File resolve(Uri uri) {
        Context context = getContext();
        if (context == null) throw new IllegalStateException("Provider is not attached");
        String name = uri.getLastPathSegment();
        if (name == null || !name.matches("DeadData-[0-9]+\\.apk")) {
            throw new IllegalArgumentException("Invalid update file");
        }
        File root;
        try {
            root = new File(context.getCacheDir(), "updates").getCanonicalFile();
            File target = new File(root, name).getCanonicalFile();
            if (!target.getPath().startsWith(root.getPath() + File.separator)) {
                throw new IllegalArgumentException("Invalid update file");
            }
            return target;
        } catch (IOException exception) {
            throw new IllegalArgumentException("Invalid update file", exception);
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }
}
