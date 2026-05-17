package io.yupiik.statura.otel;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;

import java.io.ByteArrayOutputStream;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;

@ApplicationScoped
public class ProtobufSerializer {
    private static final int WIRE_VARINT = 0;
    private static final int WIRE_64BIT = 1;
    private static final int WIRE_LENGTH_DELIMITED = 2;

    private static final int FIELD_RESOURCE_METRICS = 1;
    private static final int FIELD_RESOURCE = 1;
    private static final int FIELD_SCOPE_METRICS = 2;
    private static final int FIELD_ATTRIBUTES = 1;
    private static final int FIELD_SCOPE = 1;
    private static final int FIELD_METRICS = 2;
    private static final int FIELD_SCOPE_NAME = 1;
    private static final int FIELD_SCOPE_VERSION = 2;
    private static final int FIELD_METRIC_NAME = 1;
    private static final int FIELD_METRIC_UNIT = 3;
    private static final int FIELD_GAUGE = 5;
    private static final int FIELD_SUM = 7;
    private static final int FIELD_DATAPOINTS = 1;
    private static final int FIELD_DP_ATTRIBUTES = 7;
    private static final int FIELD_DP_TIME_UNIX_NANO = 3;
    private static final int FIELD_DP_AS_DOUBLE = 4;
    private static final int FIELD_DP_AS_INT = 6;
    private static final int FIELD_AGGREGATION_TEMPORALITY = 2;
    private static final int FIELD_IS_MONOTONIC = 3;
    private static final int FIELD_KV_KEY = 1;
    private static final int FIELD_KV_VALUE = 2;
    private static final int FIELD_ANY_STRING = 1;
    private static final int FIELD_ANY_INT = 3;
    private static final int FIELD_ANY_DOUBLE = 4;

    public byte[] exportMetricsServiceRequest(final List<byte[]> serializedResourceMetrics) {
        final var os = new ByteArrayOutputStream();
        for (final var rm : serializedResourceMetrics) {
            writeMessage(os, FIELD_RESOURCE_METRICS, rm);
        }
        return os.toByteArray();
    }

    public byte[] resourceMetrics(final byte[] resource, final List<byte[]> scopeMetrics) {
        final var os = new ByteArrayOutputStream();
        if (resource != null) {
            writeMessage(os, FIELD_RESOURCE, resource);
        }
        for (final var sm : scopeMetrics) {
            writeMessage(os, FIELD_SCOPE_METRICS, sm);
        }
        return os.toByteArray();
    }

    public byte[] resource(final List<byte[]> attributes) {
        final var os = new ByteArrayOutputStream();
        for (final var attr : attributes) {
            writeMessage(os, FIELD_ATTRIBUTES, attr);
        }
        return os.toByteArray();
    }

    public byte[] scopeMetrics(final byte[] scope, final List<byte[]> metrics) {
        final var os = new ByteArrayOutputStream();
        writeMessage(os, FIELD_SCOPE, scope);
        for (final var m : metrics) {
            writeMessage(os, FIELD_METRICS, m);
        }
        return os.toByteArray();
    }

    public byte[] instrumentationScope(final String name, final String version) {
        final var os = new ByteArrayOutputStream();
        writeString(os, FIELD_SCOPE_NAME, name);
        if (version != null && !version.isEmpty()) {
            writeString(os, FIELD_SCOPE_VERSION, version);
        }
        return os.toByteArray();
    }

    public byte[] gaugeMetric(final String name, final String unit, final List<byte[]> dataPoints) {
        final var os = new ByteArrayOutputStream();
        writeString(os, FIELD_METRIC_NAME, name);
        writeString(os, FIELD_METRIC_UNIT, unit);
        writeMessage(os, FIELD_GAUGE, gauge(dataPoints));
        return os.toByteArray();
    }

    public byte[] sumMetric(final String name, final String unit,
                            final List<byte[]> dataPoints, final boolean isMonotonic) {
        final var os = new ByteArrayOutputStream();
        writeString(os, FIELD_METRIC_NAME, name);
        writeString(os, FIELD_METRIC_UNIT, unit);
        writeMessage(os, FIELD_SUM, sum(dataPoints, isMonotonic));
        return os.toByteArray();
    }

    private byte[] gauge(final List<byte[]> dataPoints) {
        final var os = new ByteArrayOutputStream();
        for (final var dp : dataPoints) {
            writeMessage(os, FIELD_DATAPOINTS, dp);
        }
        return os.toByteArray();
    }

    private byte[] sum(final List<byte[]> dataPoints, final boolean isMonotonic) {
        final var os = new ByteArrayOutputStream();
        for (final var dp : dataPoints) {
            writeMessage(os, FIELD_DATAPOINTS, dp);
        }
        writeTag(os, FIELD_AGGREGATION_TEMPORALITY, WIRE_VARINT);
        writeVarint(os, 2);
        writeTag(os, FIELD_IS_MONOTONIC, WIRE_VARINT);
        writeVarint(os, isMonotonic ? 1 : 0);
        return os.toByteArray();
    }

    public byte[] numberDataPoint(final long timeUnixNano, final double asDouble,
                                  final long asInt, final List<byte[]> attributes) {
        final var os = new ByteArrayOutputStream();
        for (final var attr : attributes) {
            writeMessage(os, FIELD_DP_ATTRIBUTES, attr);
        }
        writeTag(os, FIELD_DP_TIME_UNIX_NANO, WIRE_64BIT);
        writeFixed64(os, timeUnixNano);
        if (asDouble != 0.0) {
            writeTag(os, FIELD_DP_AS_DOUBLE, WIRE_64BIT);
            writeFixed64(os, Double.doubleToLongBits(asDouble));
        } else {
            writeTag(os, FIELD_DP_AS_INT, WIRE_64BIT);
            writeFixed64(os, asInt);
        }
        return os.toByteArray();
    }

    public byte[] keyValue(final String key, final byte[] value) {
        final var os = new ByteArrayOutputStream();
        writeString(os, FIELD_KV_KEY, key);
        writeMessage(os, FIELD_KV_VALUE, value);
        return os.toByteArray();
    }

    public byte[] anyValueString(final String value) {
        final var os = new ByteArrayOutputStream();
        writeString(os, FIELD_ANY_STRING, value);
        return os.toByteArray();
    }

    public byte[] anyValueInt(final long value) {
        final var os = new ByteArrayOutputStream();
        writeTag(os, FIELD_ANY_INT, WIRE_VARINT);
        writeVarint(os, value);
        return os.toByteArray();
    }

    public byte[] anyValueDouble(final double value) {
        final var os = new ByteArrayOutputStream();
        writeTag(os, FIELD_ANY_DOUBLE, WIRE_64BIT);
        writeFixed64(os, Double.doubleToLongBits(value));
        return os.toByteArray();
    }

    private void writeTag(final ByteArrayOutputStream os, final int fieldNumber, final int wireType) {
        writeVarint(os, (fieldNumber << 3) | wireType);
    }

    private void writeVarint(final ByteArrayOutputStream os, long value) {
        while (true) {
            if ((value & ~0x7FL) == 0) {
                os.write((byte) value);
                return;
            }
            os.write((byte) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
    }

    private void writeFixed64(final ByteArrayOutputStream os, final long value) {
        os.write((byte) (value & 0xFF));
        os.write((byte) ((value >>> 8) & 0xFF));
        os.write((byte) ((value >>> 16) & 0xFF));
        os.write((byte) ((value >>> 24) & 0xFF));
        os.write((byte) ((value >>> 32) & 0xFF));
        os.write((byte) ((value >>> 40) & 0xFF));
        os.write((byte) ((value >>> 48) & 0xFF));
        os.write((byte) ((value >>> 56) & 0xFF));
    }

    private void writeString(final ByteArrayOutputStream os, final int fieldNumber, final String value) {
        final var bytes = value.getBytes(UTF_8);
        writeTag(os, fieldNumber, WIRE_LENGTH_DELIMITED);
        writeVarint(os, bytes.length);
        os.writeBytes(bytes);
    }

    private void writeMessage(final ByteArrayOutputStream os, final int fieldNumber, final byte[] messageBytes) {
        writeTag(os, fieldNumber, WIRE_LENGTH_DELIMITED);
        writeVarint(os, messageBytes.length);
        os.writeBytes(messageBytes);
    }
}
