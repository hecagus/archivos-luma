package mx.hecagus.archivos;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int BACKGROUND = 0xff111720;
    private static final int SURFACE = 0xff202a39;
    private static final int TEXT = 0xfff2f6ff;
    private static final int MUTED = 0xffb0bdcf;
    private static final int BLUE = 0xffaac9ff;
    private static final int DARK_BLUE = 0xff17315a;

    private final ExecutorService disk = Executors.newSingleThreadExecutor();
    private Map<Path, String> volumes = new LinkedHashMap<>();
    private final List<Row> rows = new ArrayList<>();
    private final List<Row> filtered = new ArrayList<>();
    private Path directory;
    private Path clipboard;
    private boolean cut;
    private boolean trash;
    private boolean busy;
    private int generation;
    private LinearLayout panel;
    private TextView location;
    private TextView empty;
    private EditText search;
    private ListView list;
    private RowAdapter adapter;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) {
            String saved = state.getString("directory");
            if (saved != null) directory = Paths.get(saved);
            trash = state.getBoolean("trash");
        }
    }

    @Override public void onResume() {
        super.onResume();
        volumes = StorageAccess.volumes(this);
        if (directory != null && volumes.keySet().stream().noneMatch(directory::startsWith)) directory = null;
        render();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (directory != null) state.putString("directory", directory.toString());
        state.putBoolean("trash", trash);
    }

    @Override protected void onDestroy() {
        disk.shutdown();
        super.onDestroy();
    }

    private void render() {
        generation++;
        panel = column();
        panel.setBackgroundColor(BACKGROUND);
        panel.setFocusableInTouchMode(true);
        panel.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets sides = insets.getInsets(android.view.WindowInsets.Type.systemBars()
                    | android.view.WindowInsets.Type.displayCutout() | android.view.WindowInsets.Type.ime());
                view.setPadding(dp(16) + sides.left, dp(12) + sides.top,
                    dp(16) + sides.right, dp(8) + sides.bottom);
            }
            return insets;
        });
        panel.setPadding(dp(16), dp(12), dp(16), dp(8));
        setContentView(panel);

        TextView title = label("Archivos Luma", 28, TEXT, true);
        panel.addView(title);
        location = label("", 14, MUTED, false);
        location.setPadding(0, dp(4), 0, dp(12));
        panel.addView(location);
        if (!StorageAccess.granted(this)) {
            location.setText("Acceso a tus archivos");
            TextView message = label("Activa el acceso al almacenamiento para explorar y organizar las carpetas del teléfono. Tus archivos se quedan en el dispositivo.", 17, TEXT, false);
            message.setPadding(0, dp(20), 0, dp(20));
            panel.addView(message);
            panel.addView(button("Dar acceso al almacenamiento", true, this::requestAccess));
            return;
        }

        location.setText(trash ? "Papelera" : directory == null ? "Explorar" : displayPath(directory));
        if (directory != null && !trash) {
            LinearLayout actions = row();
            actions.addView(button("↑ Subir", false, this::goUp), weight());
            actions.addView(button("+ Carpeta", false, () -> enterName(null)), weight());
            actions.addView(button("Actualizar", false, this::refresh), weight());
            panel.addView(actions);
        }
        if (clipboard != null) {
            LinearLayout actions = row();
            String pending = (cut ? "Mover " : "Copiar ") + name(clipboard);
            Button paste = button("Pegar", true, this::paste);
            paste.setContentDescription("Pegar. " + pending);
            paste.setEnabled(directory != null && !trash);
            actions.addView(paste, weight());
            actions.addView(button("Cancelar", false, () -> {
                clipboard = null;
                render();
            }), weight());
            TextView selection = label(pending, 13, BLUE, false);
            selection.setSingleLine(true);
            selection.setEllipsize(TextUtils.TruncateAt.END);
            panel.addView(selection);
            panel.addView(actions);
        }

        search = new EditText(this);
        search.setSingleLine(true);
        search.setTextSize(15);
        search.setTextColor(TEXT);
        search.setHintTextColor(MUTED);
        search.setHint(trash ? "Buscar en papelera" : "Buscar en esta ubicación");
        search.setContentDescription(search.getHint());
        search.setBackground(background(SURFACE, 12));
        search.setPadding(dp(14), 0, dp(14), 0);
        panel.addView(search, new LinearLayout.LayoutParams(-1, dp(48)));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { filter(); }
            @Override public void afterTextChanged(Editable s) {}
        });

        list = new ListView(this);
        list.setDivider(null);
        list.setPadding(0, dp(8), 0, dp(4));
        adapter = new RowAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> open(filtered.get(position)));
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            menu(filtered.get(position));
            return true;
        });
        panel.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        empty = label("", 16, MUTED, false);
        empty.setGravity(Gravity.CENTER);
        panel.addView(empty, new LinearLayout.LayoutParams(-1, 0, 1));
        list.setEmptyView(empty);

        LinearLayout tabs = row();
        tabs.addView(button("Explorar", !trash, () -> navigate(null, false)), weight());
        tabs.addView(button("Papelera", trash, () -> navigate(null, true)), weight());
        panel.addView(tabs);
        panel.requestFocus();
        refresh();
    }

    private void requestAccess() {
        if (Build.VERSION.SDK_INT < 30) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + getPackageName())));
        } catch (android.content.ActivityNotFoundException missing) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            } catch (android.content.ActivityNotFoundException unavailable) {
                notice("Abre Ajustes de Android y busca «Acceso a todos los archivos».");
            }
        }
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == 1) render();
    }

    private void refresh() {
        if (!StorageAccess.granted(this) || busy) return;
        int request = ++generation;
        Path current = directory;
        boolean inTrash = trash;
        Map<Path, String> locations = new LinkedHashMap<>(volumes);
        FileEngine engine = new FileEngine(new ArrayList<>(locations.keySet()));
        rows.clear();
        filter();
        empty.setText("Cargando…");
        disk.execute(() -> {
            List<Row> result = new ArrayList<>();
            String failure = null;
            try {
                if (inTrash) {
                    for (FileEngine.TrashEntry entry : engine.trashEntries()) {
                        result.add(new Row(entry.original, name(entry.original), date(entry.deletedAt), false, entry));
                    }
                } else if (current != null) {
                    for (Path path : engine.children(current)) result.add(fileRow(path));
                } else {
                    for (Map.Entry<Path, String> volume : locations.entrySet()) {
                        result.add(new Row(volume.getKey(), volume.getValue(), "Explorar carpetas", true, null));
                    }
                    if (!locations.isEmpty()) {
                        Path root = locations.keySet().iterator().next();
                        shortcut(result, root, "Download", "Descargas");
                        shortcut(result, root, "Documents", "Documentos");
                        shortcut(result, root, "DCIM", "Cámara");
                        shortcut(result, root, "Pictures", "Imágenes");
                        shortcut(result, root, "Movies", "Videos");
                        shortcut(result, root, "Music", "Música");
                    }
                }
            } catch (IOException | RuntimeException error) {
                failure = "No se pudo leer esta ubicación.";
            }
            String message = failure;
            runOnUiThread(() -> {
                if (isDestroyed() || request != generation) return;
                rows.clear();
                rows.addAll(result);
                filter();
                if (message != null) empty.setText(message);
            });
        });
    }

    private void shortcut(List<Row> result, Path root, String folder, String title) {
        Path path = root.resolve(folder);
        if (Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            result.add(new Row(path, title, folder, true, null));
        }
    }

    private Row fileRow(Path path) {
        boolean folder = Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS);
        String subtitle = folder ? "Carpeta" : "Archivo";
        if (!folder) {
            try {
                subtitle = Formatter.formatFileSize(this, Files.size(path));
            } catch (IOException unavailable) {
                // The item can disappear while the folder is read.
            }
        }
        return new Row(path, name(path), subtitle, folder, null);
    }

    private void filter() {
        if (adapter == null || empty == null) return;
        String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        filtered.clear();
        for (Row item : rows) {
            if (item.title.toLowerCase(Locale.ROOT).contains(query)) filtered.add(item);
        }
        empty.setText(!query.isEmpty() ? "Sin coincidencias" : trash ? "La papelera está vacía"
            : directory == null ? "No hay almacenamiento disponible" : "Esta carpeta está vacía");
        adapter.notifyDataSetChanged();
    }

    private void navigate(Path path, boolean showTrash) {
        if (busy) return;
        directory = path;
        trash = showTrash;
        render();
    }

    private void goUp() {
        if (directory == null || volumes.containsKey(directory)) navigate(null, false);
        else navigate(directory.getParent(), false);
    }

    @Override public void onBackPressed() {
        if (busy) return;
        if (trash || directory != null) goUp();
        else super.onBackPressed();
    }

    private void open(Row item) {
        if (item.entry != null) menu(item);
        else if (item.folder) navigate(item.path, false);
        else send(item.path, false);
    }

    private void menu(Row item) {
        if (busy) return;
        if (item.entry != null) {
            new AlertDialog.Builder(this).setTitle(item.title).setItems(
                new String[]{"Restaurar", "Eliminar definitivamente"}, (dialog, selected) -> {
                    if (selected == 0) operate("Restaurando…", "Elemento restaurado", engine -> engine.restore(item.entry));
                    else confirm("¿Eliminar definitivamente?", item.title + " ya no se podrá recuperar desde la papelera.",
                        "Eliminar", () -> operate("Eliminando…", "Elemento eliminado", engine -> engine.purge(item.entry)));
                }).show();
            return;
        }
        if (directory == null) {
            open(item);
            return;
        }
        List<String> actions = new ArrayList<>(java.util.Arrays.asList("Copiar", "Mover", "Renombrar", "Enviar a papelera"));
        if (!item.folder) actions.add("Compartir");
        actions.add("Detalles");
        new AlertDialog.Builder(this).setTitle(item.title)
            .setItems(actions.toArray(new String[0]), (dialog, selected) -> {
                switch (actions.get(selected)) {
                    case "Copiar": select(item.path, false); break;
                    case "Mover": select(item.path, true); break;
                    case "Renombrar": enterName(item.path); break;
                    case "Enviar a papelera":
                        confirm("¿Mover a papelera?", item.title, "Mover",
                            () -> operate("Moviendo…", "Elemento en papelera", engine -> engine.trash(item.path)));
                        break;
                    case "Compartir": send(item.path, true); break;
                    default: new AlertDialog.Builder(this).setTitle(item.title)
                        .setMessage(displayPath(item.path) + "\n\n" + item.subtitle)
                        .setPositiveButton("Cerrar", null).show();
                }
            }).show();
    }

    private void select(Path path, boolean move) {
        clipboard = path;
        cut = move;
        render();
        notice("Abre la carpeta de destino y toca Pegar.");
    }

    private void paste() {
        if (clipboard == null || directory == null) return;
        Path source = clipboard;
        Path destination = directory;
        boolean move = cut;
        operate(move ? "Moviendo…" : "Copiando…", move ? "Elemento movido" : "Copia guardada", engine -> {
            engine.transfer(source, destination, move);
        }, () -> {
            clipboard = null;
            render();
        });
    }

    private void enterName(Path source) {
        Path parent = directory;
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Nombre");
        if (source != null) {
            input.setText(name(source));
            input.selectAll();
        }
        LinearLayout content = column();
        content.setPadding(dp(22), 0, dp(22), 0);
        content.addView(input);
        new AlertDialog.Builder(this).setTitle(source == null ? "Nueva carpeta" : "Renombrar")
            .setView(content).setNegativeButton("Cancelar", null)
            .setPositiveButton("Guardar", (dialog, selected) -> {
                String value = input.getText().toString();
                operate("Guardando…", source == null ? "Carpeta creada" : "Nombre actualizado", engine -> {
                    if (source == null) engine.createFolder(parent, value);
                    else engine.rename(source, value);
                });
            }).show();
    }

    private void confirm(String title, String message, String action, Runnable confirmed) {
        new AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setNegativeButton("Cancelar", null).setPositiveButton(action, (dialog, selected) -> confirmed.run()).show();
    }

    private void operate(String loading, String success, Operation operation) {
        operate(loading, success, operation, this::refresh);
    }

    private void operate(String loading, String success, Operation operation, Runnable finished) {
        if (busy || !StorageAccess.granted(this)) return;
        busy = true;
        AlertDialog progress = new AlertDialog.Builder(this).setMessage(loading).setCancelable(false).show();
        FileEngine engine = new FileEngine(new ArrayList<>(volumes.keySet()));
        disk.execute(() -> {
            String error = null;
            try {
                operation.run(engine);
            } catch (java.nio.file.FileAlreadyExistsException duplicate) {
                error = "Ya existe un elemento con ese nombre.";
            } catch (IOException failure) {
                error = failure.getClass() == IOException.class && failure.getMessage() != null
                    ? failure.getMessage() : "No se pudo completar. Revisa el espacio y los permisos de la carpeta.";
            } catch (RuntimeException failure) {
                error = "La ubicación no está disponible.";
            }
            String message = error;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                busy = false;
                progress.dismiss();
                notice(message == null ? success : message);
                if (message == null) finished.run();
                else refresh();
            });
        });
    }

    private void send(Path path, boolean share) {
        Uri uri = LocalFileProvider.uri(path);
        String mime = LocalFileProvider.mime(path);
        Intent intent = new Intent(share ? Intent.ACTION_SEND : Intent.ACTION_VIEW);
        if (share) {
            intent.setType(mime);
            intent.putExtra(Intent.EXTRA_STREAM, uri);
        } else intent.setDataAndType(uri, mime);
        intent.setClipData(ClipData.newUri(getContentResolver(), name(path), uri));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(intent, share ? "Compartir archivo" : "Abrir archivo"));
        } catch (android.content.ActivityNotFoundException unavailable) {
            notice("Android no encontró una app compatible.");
        }
    }

    private String displayPath(Path path) {
        for (Map.Entry<Path, String> volume : volumes.entrySet()) {
            if (path.startsWith(volume.getKey())) {
                String relative = volume.getKey().relativize(path).toString();
                return relative.isEmpty() ? volume.getValue() : volume.getValue() + " / " + relative.replace("/", " / ");
            }
        }
        return name(path);
    }

    private String date(long timestamp) {
        return DateFormat.getDateFormat(this).format(new Date(timestamp));
    }

    private static String name(Path path) { return path.getFileName().toString(); }
    private void notice(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
    private int dp(int value) { return Math.round(getResources().getDisplayMetrics().density * value); }
    private LinearLayout column() {
        LinearLayout view = new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }
    private LinearLayout row() {
        LinearLayout view = new LinearLayout(this);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }
    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, dp(48), 1);
    }
    private GradientDrawable background(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }
    private TextView label(String value, int size, int color, boolean bold) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        if (bold) text.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return text;
    }
    private Button button(String label, boolean active, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setTextColor(active ? BLUE : TEXT);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(active ? DARK_BLUE : SURFACE));
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private final class RowAdapter extends BaseAdapter {
        @Override public int getCount() { return filtered.size(); }
        @Override public Row getItem(int index) { return filtered.get(index); }
        @Override public long getItemId(int index) { return index; }
        @Override public View getView(int index, View convert, ViewGroup parent) {
            RowView view = convert instanceof RowView ? (RowView) convert : new RowView();
            view.bind(getItem(index));
            return view;
        }
    }

    private final class RowView extends LinearLayout {
        final TextView icon;
        final TextView title;
        final TextView subtitle;
        final TextView actions;

        RowView() {
            super(MainActivity.this);
            setGravity(Gravity.CENTER_VERTICAL);
            setMinimumHeight(dp(65));
            setPadding(dp(4), dp(4), 0, dp(4));
            icon = label("", 18, BLUE, true);
            icon.setGravity(Gravity.CENTER);
            icon.setBackground(background(DARK_BLUE, 12));
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(icon, new LinearLayout.LayoutParams(dp(43), dp(43)));
            LinearLayout copy = column();
            copy.setPadding(dp(12), 0, 0, 0);
            title = label("", 16, TEXT, true);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            subtitle = label("", 13, MUTED, false);
            copy.addView(title);
            copy.addView(subtitle);
            addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
            actions = label("⋮", 24, BLUE, true);
            actions.setGravity(Gravity.CENTER);
            actions.setFocusable(false);
            addView(actions, new LinearLayout.LayoutParams(dp(48), dp(48)));
        }

        void bind(Row row) {
            icon.setText(row.entry != null ? "↶" : row.folder ? "▰" : "▤");
            title.setText(row.title);
            subtitle.setText(row.subtitle);
            actions.setText(directory == null && !trash ? "›" : "⋮");
            actions.setContentDescription("Opciones de " + row.title);
            actions.setOnClickListener(view -> menu(row));
            setOnClickListener(view -> open(row));
            setOnLongClickListener(view -> {
                menu(row);
                return true;
            });
        }
    }

    private interface Operation { void run(FileEngine engine) throws IOException; }

    private static final class Row {
        final Path path;
        final String title;
        final String subtitle;
        final boolean folder;
        final FileEngine.TrashEntry entry;

        Row(Path path, String title, String subtitle, boolean folder, FileEngine.TrashEntry entry) {
            this.path = path;
            this.title = title;
            this.subtitle = subtitle;
            this.folder = folder;
            this.entry = entry;
        }
    }
}
