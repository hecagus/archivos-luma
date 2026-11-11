package mx.hecagus.archivos;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.webkit.MimeTypeMap;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Locale;

public final class LocalFileProvider extends ContentProvider {
    private static final int TOKEN_FLAGS = Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING;

    static Uri uri(Path path) {
        String token = Base64.encodeToString(path.toString().getBytes(StandardCharsets.UTF_8), TOKEN_FLAGS);
        return new Uri.Builder().scheme("content").authority("mx.hecagus.archivos.files").appendPath(token).build();
    }

    static String mime(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot < 0) return "application/octet-stream";
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase(Locale.ROOT));
        return mime == null ? "application/octet-stream" : mime;
    }

    private Path resolve(Uri uri) throws FileNotFoundException {
        try {
            if (!StorageAccess.granted(getContext()) || uri.getPathSegments().size() != 1) {
                throw new IOException();
            }
            String decoded = new String(Base64.decode(uri.getLastPathSegment(), TOKEN_FLAGS), StandardCharsets.UTF_8);
            Path path = new FileEngine(new ArrayList<>(StorageAccess.volumes(getContext()).keySet())).checked(Paths.get(decoded));
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException();
            return path;
        } catch (IOException | IllegalArgumentException error) {
            throw new FileNotFoundException("El archivo no está disponible.");
        }
    }

    @Override public boolean onCreate() { return true; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Acceso de solo lectura.");
        return ParcelFileDescriptor.open(resolve(uri).toFile(), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public String getType(Uri uri) {
        try {
            return mime(resolve(uri));
        } catch (FileNotFoundException unavailable) {
            return "application/octet-stream";
        }
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor result = new MatrixCursor(columns, 1);
        try {
            Path path = resolve(uri);
            Object[] values = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) values[i] = path.getFileName().toString();
                if (OpenableColumns.SIZE.equals(columns[i])) values[i] = Files.size(path);
            }
            result.addRow(values);
        } catch (IOException unavailable) {
            // The file may have moved since the chooser opened.
        }
        return result;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
