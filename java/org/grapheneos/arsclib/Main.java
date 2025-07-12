package org.grapheneos.arsclib;

import com.google.gson.Gson;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import static com.google.common.base.Verify.verify;
import static com.google.common.base.Verify.verifyNotNull;

public class Main {

    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !args[0].equals("--json-stdin")) {
            throw new IllegalArgumentException("expected --json-stdin option");
        }

        var gson = new Gson();
        String jsonStr = new String(System.in.readAllBytes());
        var cmd = gson.fromJson(jsonStr, ConversionCommand.class);

        var moduleList = new ArrayList<String>();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var rootPath = Path.of(cmd.unpackedOsImageDir);
            var rp = ResourceProcessor.create(getAapt2Path(), rootPath, executor);
            var destination = Path.of(cmd.outDir);

            try (Stream<Path> fileStream = Files.walk(rootPath)) {
                fileStream
                        .parallel()
                        .filter(e -> {
                            String s = e.toString();
                            return s.endsWith(".apk") && s.contains("/overlay/");
                        })
                        .forEach(filePath -> {
                            String fileName = filePath.getFileName().toString();
                            String overlayName = fileName.substring(0, fileName.length() - ".apk".length());
                            if (cmd.pkgExclusionFilters.test(overlayName)) {
                                return;
                            }
                            Apk apk;
                            try {
                                apk = rp.loadApk(rootPath.relativize(filePath).toString());
                            } catch (IOException e) {
                                throw new RuntimeException(e);
                            }
                            String moduleName = ApkConverter.convert(apk, cmd, null, destination);
                            if (moduleName != null) {
                                synchronized (moduleList) {
                                    moduleList.add(moduleName);
                                }
                            }
                        });
            }

            cmd.syntheticOverlays.stream().parallel().forEach(soSpec -> {
                Path path = null;
                for (String sourcePath : soSpec.sourcePaths()) {
                    Path candidate = rootPath.resolve(sourcePath);
                    if (Files.isRegularFile(candidate)) {
                        path = candidate;
                        break;
                    }
                }
                if (path == null) {
                    throw new RuntimeException(soSpec.moduleName() + ": no available sourcePath" +
                            ", tried: " + Arrays.toString(soSpec.sourcePaths().toArray()));
                }
                Apk apk;
                try {
                    apk = rp.loadApk(path.toString());
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                String moduleName = ApkConverter.convert(apk, cmd, soSpec, destination);
                verify(moduleName != null);
                verify(moduleName.equals(soSpec.moduleName()));
                synchronized (moduleList) {
                    moduleList.add(moduleName);
                }
            });
        }
        moduleList.sort(String::compareTo);
        Files.writeString(Path.of(cmd.outModuleListPath), String.join("\n", moduleList));
    }

    private static String getAapt2Path() {
        return Path.of(verifyNotNull(System.getenv("ANDROID_HOST_OUT")), "bin", "aapt2").toString();
    }
}
