package mx.hecagus.archivos;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.stream.Stream;

final class FileEngine {
    private static final String TRASH = ".archivos-luma-papelera";
    private final List<Path> roots;

    FileEngine(List<Path> roots) {
        this.roots = new ArrayList<>(roots);
    }

    Path rootOf(Path path) throws IOException {
        for (Path root : roots) {
            if (path.startsWith(root)) return root;
        }
        throw new IOException("La ubicación no está disponible.");
    }

    Path checked(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        Path root = rootOf(normalized);
        Path relative = root.relativize(normalized);
        if (relative.startsWith(TRASH) || relative.startsWith("Android/data")
                || relative.startsWith("Android/obb")) {
            throw new IOException("La carpeta está protegida.");
        }
        Path cursor = root;
        for (Path part : relative) {
            cursor = cursor.resolve(part);
            if (Files.isSymbolicLink(cursor)) throw new IOException("El enlace no se puede abrir.");
        }
        return normalized;
    }

    List<Path> children(Path directory) throws IOException {
        Path folder = checked(directory);
        List<Path> result = new ArrayList<>();
        try (Stream<Path> paths = Files.list(folder)) {
            paths.filter(path -> !path.getFileName().toString().startsWith("."))
                .filter(path -> !Files.isSymbolicLink(path)).forEach(result::add);
        }
        result.sort(Comparator.<Path, Boolean>comparing(path -> !Files.isDirectory(path))
            .thenComparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    Path createFolder(Path parent, String name) throws IOException {
        return Files.createDirectory(checked(checked(parent).resolve(validName(name))));
    }

    Path rename(Path source, String name) throws IOException {
        Path original = item(source);
        Path target = checked(original.resolveSibling(validName(name)));
        if (original.equals(target)) return original;
        return Files.move(original, target);
    }

    Path transfer(Path source, Path destination, boolean move) throws IOException {
        Path original = item(source);
        Path folder = checked(destination);
        if (folder.startsWith(original)) throw new IOException("Elige una carpeta fuera del elemento.");
        if (move && folder.equals(original.getParent())) return original;
        Path target = available(folder, original.getFileName().toString());
        if (move && rootOf(original).equals(rootOf(folder))) return Files.move(original, target);
        Path staging = Files.createTempDirectory(folder, ".archivos-luma-copia-");
        try {
            Path payload = staging.resolve("contenido");
            copyTree(original, payload);
            Files.move(payload, target);
        } finally {
            deleteTree(staging);
        }
        if (move) {
            try {
                trash(original);
            } catch (IOException error) {
                throw new IOException("Se guardó una copia. El original sigue en su ubicación.", error);
            }
        }
        return target;
    }

    private static void copyTree(Path original, Path destination) throws IOException {
        Files.walkFileTree(original, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult preVisitDirectory(Path folder, BasicFileAttributes attrs) throws IOException {
                Files.createDirectory(destination.resolve(original.relativize(folder)));
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!attrs.isRegularFile()) throw new IOException("La selección contiene un archivo especial.");
                Path copy = destination.resolve(original.relativize(file));
                Files.copy(file, copy, LinkOption.NOFOLLOW_LINKS);
                BasicFileAttributes after = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!after.isRegularFile() || after.size() != attrs.size() || Files.size(copy) != attrs.size()
                        || !attrs.lastModifiedTime().equals(after.lastModifiedTime())) {
                    throw new IOException("Un archivo cambió durante la copia. Intenta de nuevo.");
                }
                try {
                    Files.setLastModifiedTime(copy, attrs.lastModifiedTime());
                } catch (IOException ignored) {
                    // Some removable volumes cannot set timestamps.
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    void trash(Path source) throws IOException {
        Path original = item(source);
        Path root = rootOf(original);
        Path base = trashBase(root, true);
        Path slot = Files.createDirectory(base.resolve(UUID.randomUUID().toString()));
        Properties info = new Properties();
        info.setProperty("original", root.relativize(original).toString());
        info.setProperty("deletedAt", Long.toString(System.currentTimeMillis()));
        try {
            try (OutputStream output = Files.newOutputStream(slot.resolve("info.properties"))) {
                info.store(output, "Archivos Luma");
            }
            Files.move(original, slot.resolve("contenido"));
        } catch (IOException failure) {
            if (!Files.exists(slot.resolve("contenido"), LinkOption.NOFOLLOW_LINKS)) deleteTree(slot);
            throw failure;
        }
    }

    List<TrashEntry> trashEntries() throws IOException {
        List<TrashEntry> entries = new ArrayList<>();
        for (Path root : roots) {
            Path base = trashBase(root, false);
            if (!Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS)) continue;
            try (Stream<Path> paths = Files.list(base)) {
                for (Path slot : (Iterable<Path>) paths::iterator) {
                    if (!Files.isDirectory(slot, LinkOption.NOFOLLOW_LINKS)
                            || !Files.exists(slot.resolve("contenido"), LinkOption.NOFOLLOW_LINKS)) continue;
                    try {
                        entries.add(readEntry(root, slot));
                    } catch (IOException | IllegalArgumentException ignored) {
                        // Leave incomplete entries on disk for recovery.
                    }
                }
            }
        }
        entries.sort(Comparator.comparingLong((TrashEntry entry) -> entry.deletedAt).reversed());
        return entries;
    }

    Path restore(TrashEntry entry) throws IOException {
        Path original = checked(entry.original);
        Path parent = original.getParent();
        Files.createDirectories(parent);
        Path target = available(parent, original.getFileName().toString());
        Files.move(payload(entry), target);
        deleteTree(entry.slot);
        return target;
    }

    void purge(TrashEntry entry) throws IOException {
        payload(entry);
        deleteTree(entry.slot);
    }

    private Path payload(TrashEntry entry) throws IOException {
        Path base = trashBase(rootOf(entry.original), false);
        if (!base.equals(entry.slot.getParent()) || !Files.isDirectory(entry.slot, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("El elemento ya no está en la papelera.");
        }
        return entry.slot.resolve("contenido");
    }

    private static Path trashBase(Path root, boolean create) throws IOException {
        Path base = root.resolve(TRASH);
        if (Files.exists(base, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("La papelera no está disponible.");
        }
        if (create) {
            Files.createDirectories(base);
            Path marker = base.resolve(".nomedia");
            if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) Files.createFile(marker);
        }
        return base;
    }

    private TrashEntry readEntry(Path root, Path slot) throws IOException {
        Properties info = new Properties();
        try (InputStream stream = Files.newInputStream(slot.resolve("info.properties"), LinkOption.NOFOLLOW_LINKS)) {
            info.load(stream);
        }
        Path original = item(root.resolve(info.getProperty("original", "")));
        if (!original.startsWith(root)) throw new IOException("Ubicación inválida.");
        return new TrashEntry(slot, original, Long.parseLong(info.getProperty("deletedAt", "0")));
    }

    private Path item(Path path) throws IOException {
        Path result = checked(path);
        if (roots.contains(result) || result.equals(rootOf(result).resolve("Android"))) throw new IOException("No puedes modificar el almacenamiento completo.");
        return result;
    }

    private static String validName(String name) throws IOException {
        String clean = name.trim();
        if (clean.isEmpty() || clean.startsWith(".") || clean.matches(".*[\\\\/:*?\"<>|\\p{Cntrl}].*")
                || clean.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 240) {
            throw new IOException("Escribe un nombre válido.");
        }
        return clean;
    }

    private static Path available(Path parent, String name) {
        Path target = parent.resolve(name);
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String suffix = dot > 0 ? name.substring(dot) : "";
        for (int copy = 2; Files.exists(target, LinkOption.NOFOLLOW_LINKS); copy++) {
            target = parent.resolve(stem + " (" + copy + ")" + suffix);
        }
        return target;
    }

    private static void deleteTree(Path path) throws IOException {
        Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult postVisitDirectory(Path folder, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(folder);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static final class TrashEntry {
        private final Path slot;
        final Path original;
        final long deletedAt;

        private TrashEntry(Path slot, Path original, long deletedAt) {
            this.slot = slot;
            this.original = original;
            this.deletedAt = deletedAt;
        }
    }
}
