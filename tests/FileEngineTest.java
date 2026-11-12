package mx.hecagus.archivos;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public final class FileEngineTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path workspace = Files.createTempDirectory("archivos-test-");
        try {
            Path primary = Files.createDirectory(workspace.resolve("phone"));
            Path secondary = Files.createDirectory(workspace.resolve("sd"));
            FileEngine engine = new FileEngine(Arrays.asList(primary, secondary));
            Path downloads = engine.createFolder(primary, "Descargas");
            Path docs = engine.createFolder(primary, "Documentos");
            Path note = write(downloads.resolve("nota.txt"), "archivo de prueba");

            check(engine.children(primary).equals(Arrays.asList(downloads, docs)), "orden");
            check(engine.rename(note, "nota.txt").equals(note), "mismo nombre");
            reject(() -> engine.createFolder(primary, "../escape"));
            reject(() -> engine.createFolder(primary, ".archivos-luma-papelera"));
            reject(() -> engine.rename(primary, "otro"));
            reject(() -> engine.checked(workspace));
            reject(() -> engine.checked(primary.resolve("Android/data")));
            reject(() -> engine.checked(primary.resolve("Android/obb")));
            Path alias = Files.createSymbolicLink(downloads.resolve("enlace"), workspace);
            reject(() -> engine.checked(alias));
            check(engine.children(downloads).equals(List.of(note)), "oculta enlaces");
            Files.delete(alias);

            Path duplicate = engine.transfer(note, downloads, false);
            check(duplicate.equals(downloads.resolve("nota (2).txt")), "duplicado sin sobrescribir");
            check(read(note).equals(read(duplicate)), "copia exacta");
            reject(() -> engine.rename(duplicate, "nota.txt"));
            Path moved = engine.transfer(duplicate, docs, true);
            check(!Files.exists(duplicate) && Files.exists(moved), "mover");
            Path nested = engine.createFolder(downloads, "Subcarpeta");
            write(nested.resolve("foto.jpg"), "bytes");
            reject(() -> engine.transfer(downloads, nested, false));
            Path copied = engine.transfer(downloads, secondary, false);
            check(read(copied.resolve("Subcarpeta/foto.jpg")).equals("bytes"), "copia recursiva");
            Path foreign = engine.transfer(moved, secondary, true);
            check(read(foreign).equals("archivo de prueba") && !Files.exists(moved), "mover entre volúmenes");
            check(engine.trashEntries().get(0).original.equals(moved), "origen recuperable");

            engine.trash(note);
            check(!Files.exists(note), "mover a papelera");
            check(engine.children(primary).size() == 2, "papelera fuera de la lista");
            write(note, "nuevo contenido");
            FileEngine restoredEngine = new FileEngine(Arrays.asList(primary, secondary));
            FileEngine.TrashEntry entry = restoredEngine.trashEntries().stream()
                .filter(item -> item.original.equals(note)).findFirst().orElseThrow();
            Path restored = restoredEngine.restore(entry);
            check(restored.equals(downloads.resolve("nota (2).txt")), "restaurar sin sobrescribir");
            check(read(restored).equals("archivo de prueba") && read(note).equals("nuevo contenido"), "datos conservados");
            restoredEngine.purge(restoredEngine.trashEntries().get(0));
            check(restoredEngine.trashEntries().isEmpty(), "eliminar de papelera");
            check(Files.exists(foreign), "copia ajena a la papelera intacta");

            Files.createSymbolicLink(downloads.resolve("enlace"), docs);
            reject(() -> engine.transfer(downloads, docs, false));
            check(engine.children(docs).isEmpty(), "copia fallida no publica contenido parcial");
            Path newest = write(secondary.resolve("reciente.pdf"), "archivo nuevo");
            Path hidden = write(primary.resolve(".oculto"), "oculto");
            Path restricted = write(Files.createDirectories(primary.resolve("Android/data/demo")).resolve("privado"), "privado");
            for (Path path : List.of(newest, hidden, restricted)) {
                Files.setLastModifiedTime(path, FileTime.fromMillis(System.currentTimeMillis() + 60_000));
            }
            List<FileEngine.RecentFile> recent = engine.recent(100);
            check(recent.get(0).path.equals(newest), "recientes ordenados entre volúmenes");
            check(recent.stream().anyMatch(file -> file.path.equals(nested.resolve("foto.jpg"))), "incluye subcarpetas");
            check(recent.stream().noneMatch(file -> file.path.equals(hidden) || file.path.equals(restricted)
                || file.path.equals(downloads.resolve("enlace"))), "excluye archivos ocultos, privados y enlaces");
            check(engine.recent(1).size() == 1 && engine.recent(1).get(0).size == Files.size(newest), "límite y tamaño");
            System.out.println(checks + " comprobaciones de archivos aprobadas.");
        } finally {
            try (Stream<Path> paths = Files.walk(workspace)) {
                for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) Files.delete(path);
            }
        }
    }

    private static Path write(Path path, String text) throws IOException {
        return Files.write(path, text.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static void reject(Action action) throws IOException {
        try {
            action.run();
        } catch (IOException expected) {
            checks++;
            return;
        }
        throw new AssertionError("Se esperaba un error de archivo");
    }

    private static void check(boolean result, String what) {
        if (!result) throw new AssertionError(what);
        checks++;
    }

    private interface Action { void run() throws IOException; }
}
