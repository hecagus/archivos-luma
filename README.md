# Archivos Luma

Gestor de archivos Android de HecAgus. En el lanzador aparece como **Archivos** y en la aplicación como **Archivos Luma**. Funciona localmente, sin anuncios, compras, cuentas ni suscripciones.

- [Descarga APK firmada](https://github.com/hecagus/archivos-luma/releases/tag/v1.0.0)
- [Página de descarga](https://archivos-luma.vercel.app/)

## Uso

1. Abre la APK en un teléfono con Android 10 o posterior. Confirma la instalación y, si Android lo pide, permite que el navegador o gestor que abrió el archivo lance el instalador.
2. Abre **Archivos**, toca **Dar acceso al almacenamiento** y concede el acceso en Ajustes.
3. En **Explorar**, abre una carpeta o volumen. El menú **⋮** de cada elemento permite copiar, mover, renombrar, compartir y enviar a la papelera. Para copiar o mover, abre el destino y toca **Pegar**.

La búsqueda filtra por nombre en la ubicación abierta. Los archivos se abren con una app compatible instalada. Compartir entrega un permiso temporal de lectura al destino que elijas.

La papelera guarda los archivos en el volumen de origen. Puedes restaurar un elemento o confirmar su eliminación definitiva. Si el nombre de destino ya está ocupado, copiar, mover y restaurar eligen un nombre libre. Los archivos en la papelera siguen ocupando espacio. Cuando mueves entre volúmenes, se copia al destino y se conserva el original en la papelera del volumen de origen.

## Almacenamiento y privacidad

Android 11 o posterior usa [acceso a todos los archivos](https://developer.android.com/training/data-storage/manage-all-files). Android 10 usa los permisos de almacenamiento compatibles con esa versión. Se explora el almacenamiento compartido y, en Android 11 o posterior, los volúmenes extraíbles que Android expone. Las carpetas privadas de otras apps quedan protegidas.

La APK carece de permiso de Internet. Vercel publica la página de descarga y GitHub aloja la APK. El teléfono gestiona los archivos sin servidor.

## Compilar y comprobar

Requiere Python 3 y Java 21. La preparación descarga y verifica las herramientas de Android y el compilador ECJ.

```sh
python3 scripts/prepare_toolchain.py
python3 scripts/build.py
java -jar .toolchain/ecj.jar -1.8 -warn:none -encoding UTF-8 -cp build/manual/classes -d build/file-tests tests/FileEngineTest.java
java -cp build/manual/classes:build/file-tests mx.hecagus.archivos.FileEngineTest
npm run build
```

El resultado firmado se guarda en `output/Archivos-Luma-1.0.0.apk`. Un primer build local genera una clave propia. Para emitir actualizaciones que se instalen sobre la versión distribuida aquí se necesita la clave privada original en `signing/`. Ese directorio se excluye del repositorio y de Vercel.

La primera versión pasó 26 comprobaciones del motor de archivos con datos de prueba, además de la verificación del manifiesto, alineación y firma de la APK. La instalación y la interfaz en un teléfono físico quedan pendientes.
