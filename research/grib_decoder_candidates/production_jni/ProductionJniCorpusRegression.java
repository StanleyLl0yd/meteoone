package com.sl.meteoone.forecast.data.grib;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class NativeGribMessage {
    final long[] metadata;
    final double[] geometry;
    final double[] values;

    NativeGribMessage(long[] metadata, double[] geometry, double[] values) {
        this.metadata = metadata;
        this.geometry = geometry;
        this.values = values;
    }
}

final class EcCodesNativeBridge {
    native void nativeConfigureDefinitions(String definitionsPath);

    native NativeGribMessage[] nativeDecode(
            byte[] payload,
            int maxMessages,
            int maxTotalValues);
}

public final class ProductionJniCorpusRegression {
    private static final int METADATA_LONG_COUNT = 35;
    private static final int GEOMETRY_DOUBLE_COUNT = 4;
    private static final int MAX_GRID_VALUES = 3_000_000;

    private ProductionJniCorpusRegression() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException(
                    "usage: ProductionJniCorpusRegression JNI_SO DEFINITIONS PREPARED_DIR EXPECTED_TSV");
        }

        Path jniLibrary = Path.of(args[0]).toAbsolutePath().normalize();
        Path definitions = Path.of(args[1]).toAbsolutePath().normalize();
        Path prepared = Path.of(args[2]).toAbsolutePath().normalize();
        Path expectedTsv = Path.of(args[3]).toAbsolutePath().normalize();
        requireRegularFile(jniLibrary, "JNI library");
        requireDirectory(definitions, "definitions");
        requireDirectory(prepared, "prepared corpus");
        requireRegularFile(expectedTsv, "expected envelope TSV");

        System.load(jniLibrary.toString());
        EcCodesNativeBridge bridge = new EcCodesNativeBridge();
        bridge.nativeConfigureDefinitions(definitions.toString());

        Map<String, List<Envelope>> expected = readExpected(expectedTsv);
        if (expected.size() != 6) {
            throw new AssertionError("expected six provider representatives, got " + expected.size());
        }

        long totalValues = 0;
        for (Map.Entry<String, List<Envelope>> entry : expected.entrySet()) {
            Path sample = prepared.resolve(entry.getKey()).normalize();
            if (!sample.getParent().equals(prepared)) {
                throw new AssertionError("unsafe prepared sample path: " + sample);
            }
            requireRegularFile(sample, "prepared sample");
            byte[] payload = Files.readAllBytes(sample);
            List<Envelope> envelopes = entry.getValue();
            NativeGribMessage[] messages = bridge.nativeDecode(
                    payload,
                    envelopes.size(),
                    MAX_GRID_VALUES);
            if (messages.length != envelopes.size()) {
                throw new AssertionError(
                        entry.getKey() + " message count " + messages.length + " != " + envelopes.size());
            }

            long sampleValues = 0;
            for (int index = 0; index < messages.length; index++) {
                NativeGribMessage message = messages[index];
                Envelope envelope = envelopes.get(index);
                verifyNativeLayout(entry.getKey(), index, message);
                verifyEnvelope(entry.getKey(), index, message, envelope);
                sampleValues += message.values.length;
            }
            totalValues += sampleValues;
            System.out.printf(
                    "PASS provider-envelope file=%s messages=%d values=%d%n",
                    entry.getKey(), messages.length, sampleValues);
        }

        Path noaaPrecipitation = prepared.resolve("noaa_precipitation.grib2");
        expectIllegalArgument(
                "message-count bound",
                () -> bridge.nativeDecode(Files.readAllBytes(noaaPrecipitation), 1, MAX_GRID_VALUES));

        Path ecmwfTemperature = prepared.resolve("ecmwf_temperature.grib2");
        expectIllegalArgument(
                "decoded-value bound",
                () -> bridge.nativeDecode(Files.readAllBytes(ecmwfTemperature), 1, 1));

        byte[] ecmwf = Files.readAllBytes(ecmwfTemperature);
        if (ecmwf.length < 64) {
            throw new AssertionError("ECMWF DRT42 regression seed unexpectedly small");
        }
        expectIllegalArgument(
                "truncated payload",
                () -> bridge.nativeDecode(Arrays.copyOf(ecmwf, ecmwf.length / 2), 1, MAX_GRID_VALUES));

        byte[] trailing = Arrays.copyOf(ecmwf, ecmwf.length + 1);
        trailing[trailing.length - 1] = 0x01;
        expectIllegalArgument(
                "trailing payload",
                () -> bridge.nativeDecode(trailing, 1, MAX_GRID_VALUES));
        expectIllegalArgument(
                "non-GRIB payload",
                () -> bridge.nativeDecode(new byte[] {0x01, 0x02, 0x03, 0x04}, 1, MAX_GRID_VALUES));
        expectIllegalArgument(
                "zero message limit",
                () -> bridge.nativeDecode(ecmwf, 0, MAX_GRID_VALUES));
        expectIllegalArgument(
                "zero value limit",
                () -> bridge.nativeDecode(ecmwf, 1, 0));

        System.out.printf(
                "PASS production-jni-corpus-regression representatives=%d decodedValues=%d%n",
                expected.size(), totalValues);
    }

    private static Map<String, List<Envelope>> readExpected(Path path) throws Exception {
        Map<String, List<Envelope>> expected = new LinkedHashMap<>();
        List<String> lines = Files.readAllLines(path);
        for (int lineNumber = 0; lineNumber < lines.size(); lineNumber++) {
            String raw = lines.get(lineNumber);
            if (raw.isBlank()) {
                continue;
            }
            String[] columns = raw.split("\\t", -1);
            if (columns.length != 9) {
                throw new AssertionError(
                        "malformed expected envelope line " + (lineNumber + 1) + ": " + raw);
            }
            String fileName = columns[0];
            int messageIndex = Integer.parseInt(columns[1]);
            Envelope envelope = new Envelope(
                    Long.parseLong(columns[2]),
                    Long.parseLong(columns[3]),
                    Long.parseLong(columns[4]),
                    Long.parseLong(columns[5]),
                    Long.parseLong(columns[6]),
                    Long.parseLong(columns[7]),
                    Long.parseLong(columns[8]));
            List<Envelope> messages = expected.computeIfAbsent(fileName, ignored -> new ArrayList<>());
            if (messageIndex != messages.size()) {
                throw new AssertionError(
                        "non-contiguous message indexes for " + fileName + ": " + messageIndex);
            }
            messages.add(envelope);
        }
        return expected;
    }

    private static void verifyNativeLayout(
            String fileName,
            int messageIndex,
            NativeGribMessage message) {
        if (message == null) {
            throw new AssertionError(fileName + " message " + messageIndex + " is null");
        }
        if (message.metadata == null || message.metadata.length != METADATA_LONG_COUNT) {
            throw new AssertionError(fileName + " native metadata layout drifted");
        }
        if (message.geometry == null || message.geometry.length != GEOMETRY_DOUBLE_COUNT) {
            throw new AssertionError(fileName + " native geometry layout drifted");
        }
        if (message.values == null || message.values.length == 0 || message.values.length > MAX_GRID_VALUES) {
            throw new AssertionError(fileName + " native value cardinality is outside the bounded limit");
        }
        if (message.metadata[28] != message.values.length) {
            throw new AssertionError(fileName + " native value cardinality metadata drifted");
        }
        boolean finiteValue = false;
        for (double value : message.values) {
            if (Double.isFinite(value)) {
                finiteValue = true;
                break;
            }
        }
        if (!finiteValue) {
            throw new AssertionError(fileName + " decoded no finite values");
        }
    }

    private static void verifyEnvelope(
            String fileName,
            int messageIndex,
            NativeGribMessage message,
            Envelope expected) {
        long[] actual = Arrays.copyOf(message.metadata, 7);
        long[] wanted = expected.asArray();
        if (!Arrays.equals(actual, wanted)) {
            throw new AssertionError(
                    fileName + " message " + messageIndex
                            + " provider envelope drifted: "
                            + Arrays.toString(actual) + " != " + Arrays.toString(wanted));
        }
    }

    private static void expectIllegalArgument(String label, ThrowingRunnable action) throws Exception {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            System.out.printf("PASS fail-closed case=%s message=%s%n", label, expected.getMessage());
            return;
        }
        throw new AssertionError("expected IllegalArgumentException for " + label);
    }

    private static void requireRegularFile(Path path, String label) {
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException(label + " is not a regular file: " + path);
        }
    }

    private static void requireDirectory(Path path, String label) {
        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException(label + " is not a directory: " + path);
        }
    }

    private record Envelope(
            long edition,
            long discipline,
            long parameterCategory,
            long parameterNumber,
            long productDefinitionTemplate,
            long gridDefinitionTemplate,
            long dataRepresentationTemplate) {
        long[] asArray() {
            return new long[] {
                edition,
                discipline,
                parameterCategory,
                parameterNumber,
                productDefinitionTemplate,
                gridDefinitionTemplate,
                dataRepresentationTemplate,
            };
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
