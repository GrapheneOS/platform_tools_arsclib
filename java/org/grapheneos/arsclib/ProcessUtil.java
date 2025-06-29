package org.grapheneos.arsclib;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.function.IntPredicate;
import java.util.function.Predicate;

import static java.nio.charset.StandardCharsets.UTF_8;

public class ProcessUtil {
    static void runChecking(ProcessBuilder pb, Predicate<String> isAllowedStderr, IntPredicate isAllowedExitCode) throws IOException {
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        Process process = pb.start();
        try (BufferedReader errorReader = process.errorReader(UTF_8)) {
            String line;
            var invalidLines = new ArrayList<String>();
            while ((line = errorReader.readLine()) != null) {
                if (!isAllowedStderr.test(line)) {
                    invalidLines.add(line);
                }
            }
            if (!invalidLines.isEmpty()) {
                throw new IOException("invalid lines:\n" + String.join("\n", invalidLines));
            }
        }
        int exitCode;
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException e) {
            throw new IOException(e);
        }

        if (!isAllowedExitCode.test(exitCode)) {
            throw new IOException("invalid exit code: " + exitCode);
        }
    }
}
