package io.github.bownz.btrecomp;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Serves the log reports written by {@link Diagnostics} (cache/share/*.txt, or a .zip with the test builds' memory snapshots) to the app the
 * player shares them with. Read-only, and only files in that folder.
 */
public final class LogProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        return true;
    }

    private File fileFor(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || name.contains("/") || name.startsWith(".")) {
            throw new FileNotFoundException(String.valueOf(uri));
        }
        File f = new File(new File(getContext().getCacheDir(), "share"), name);
        if (!f.isFile()) {
            throw new FileNotFoundException(f.getName());
        }
        return f;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        String name = uri.getLastPathSegment();
        return name != null && name.endsWith(".zip") ? "application/zip" : "text/plain";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sortOrder) {
        try {
            File f = fileFor(uri);
            MatrixCursor c = new MatrixCursor(new String[] { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE });
            c.addRow(new Object[] { f.getName(), f.length() });
            return c;
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        return 0;
    }
}
