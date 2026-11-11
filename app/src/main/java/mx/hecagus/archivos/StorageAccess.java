package mx.hecagus.archivos;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

final class StorageAccess {
    private StorageAccess() {}

    static boolean granted(Context context) {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            && context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    static Map<Path, String> volumes(Context context) {
        Map<Path, String> roots = new LinkedHashMap<>();
        if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())
                || Environment.MEDIA_MOUNTED_READ_ONLY.equals(Environment.getExternalStorageState())) {
            add(roots, Environment.getExternalStorageDirectory(), "Almacenamiento interno");
        }
        if (Build.VERSION.SDK_INT >= 30) {
            StorageManager manager = context.getSystemService(StorageManager.class);
            if (manager != null) {
                for (StorageVolume volume : manager.getStorageVolumes()) {
                    if (!volume.isPrimary()) add(roots, volume.getDirectory(), volume.getDescription(context));
                }
            }
        }
        return roots;
    }

    private static void add(Map<Path, String> roots, File directory, String label) {
        if (directory == null || !directory.isDirectory()) return;
        try {
            roots.put(directory.getCanonicalFile().toPath(), label);
        } catch (IOException ignored) {
            // A volume can disconnect during discovery.
        }
    }
}
