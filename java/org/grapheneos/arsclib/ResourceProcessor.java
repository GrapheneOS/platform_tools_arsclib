package org.grapheneos.arsclib;

import com.android.aapt.Resources;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static com.google.common.base.Verify.verify;
import static org.grapheneos.arsclib.Constants.ANDROID_MANIFEST_NAME;

public record ResourceProcessor(String aapt2Path, Path rootPath, Apk frameworkApk, ExecutorService executor) {

    static ResourceProcessor create(String aapt2Path, Path rootPath, ExecutorService executor) throws IOException {
        Path fwPath = rootPath.resolve( "system/system/framework/framework-res.apk");
        Apk frameworkApk;
        try (ZipInputStream zis = convertToProtoApk(aapt2Path, fwPath)) {
            frameworkApk = processProtoApk(zis, executor);
        }
        var res = new ResourceProcessor(aapt2Path, rootPath, frameworkApk, executor);
        frameworkApk.resourceProcessor = res;
        return res;
    }

    static Apk processProtoApk(ZipInputStream zipStream, ExecutorService executor) throws IOException {
        Future<IndexedResourceTable> resTableF = null;
        HashMap<String, Apk.FileContents> files = new HashMap<>();
        Future<Resources.XmlNode> androidManifestF = null;
        for (ZipEntry entry = zipStream.getNextEntry(); entry != null; entry = zipStream.getNextEntry()) {
            String name = entry.getName();
            if (name.startsWith("res/")) {
                files.put(name, new Apk.FileContents(zipStream.readAllBytes()));
                continue;
            }
            switch (name) {
                case ANDROID_MANIFEST_NAME -> {
                    verify(androidManifestF == null);
                    byte[] bytes = zipStream.readAllBytes();
                    androidManifestF = executor.submit(() -> Resources.XmlNode.parseFrom(bytes));
                }
                case "resources.pb" -> {
                    byte[] bytes = zipStream.readAllBytes();
                    resTableF = executor.submit(() -> new IndexedResourceTable(Resources.ResourceTable.parseFrom(bytes)));
                }
            }
        }
        verify(androidManifestF != null);
        verify(resTableF != null);
        IndexedResourceTable resTable;
        Resources.XmlNode androidManifest;
        try {
            resTable = resTableF.get();
            androidManifest = androidManifestF.get();
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            throw new IllegalArgumentException(e);
        }
        var res = new Apk(resTable, androidManifest, files);
        return res;
    }

    Apk loadApk(String path) throws IOException {
        Path resolvedPath = path.startsWith("/") ? Path.of(path) : rootPath.resolve(path);
        Apk res;
        try (ZipInputStream zis = convertToProtoApk(aapt2Path, resolvedPath)) {
            res = processProtoApk(zis, executor);
        }
        res.path = resolvedPath;
        res.fileName = resolvedPath.getFileName().toString();
        res.resourceProcessor = this;
        res.maybeParseOverlayInfo();
        return res;
    }

    static ZipInputStream convertToProtoApk(String aapt2Path, Path apkPath) throws IOException {
        var cmd = new ArrayList<String>();
        Path output = Files.createTempFile("aapt2-protoapk", ".apk");
        cmd.addAll(Arrays.asList(
                aapt2Path,
                "convert",
                "--for-adevtool",
                "--output-format",
                "proto",
                apkPath.toString(),
                "-v", // verbose logging
                "-o",
                output.toString()
        ));
        Predicate<String> isAllowedStderrLine =
                l -> l.startsWith("note: writing ") && l.endsWith("to archive.");
        IntPredicate isAllowedExitCode = exitCode -> exitCode == 0;
        var b = new ProcessBuilder(cmd);
        try {
            ProcessUtil.runChecking(b, isAllowedStderrLine, isAllowedExitCode);
        } catch (IOException e) {
            Files.delete(output);
            throw e;
        }
        return new ZipInputStream(Files.newInputStream(output, StandardOpenOption.DELETE_ON_CLOSE));
    }
}
