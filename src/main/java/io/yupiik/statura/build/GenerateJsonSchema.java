/*
 * Copyright (c) 2026 - present - Yupiik SAS - https://www.yupiik.com
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.yupiik.statura.build;

import io.yupiik.fusion.json.internal.JsonMapperImpl;
import io.yupiik.fusion.json.internal.JsonStrings;

import java.io.FileWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class GenerateJsonSchema implements Runnable {
    private static final Set<Class<?>> PRIMITIVE_TYPES = Set.of(
            String.class, CharSequence.class,
            boolean.class, Boolean.class,
            int.class, Integer.class,
            long.class, Long.class,
            float.class, Float.class,
            double.class, Double.class,
            BigInteger.class,
            BigDecimal.class);

    private final Path sourceBase;

    public GenerateJsonSchema(final Path sourceBase) {
        this.sourceBase = sourceBase;
    }

    @Override
    public void run() {
        final var base = sourceBase.getParent().getParent().getParent();
        final var docPath = base.resolve("target/classes/META-INF/fusion/configuration/documentation.json");
        final var output = sourceBase.resolve("content/_partials/generated/jsonschema.json");

        try (final var mapper = new JsonMapperImpl(List.of(), _ -> Optional.empty())) {
            final var raw = mapper.fromString(Object.class, Files.readString(docPath));
            if (!(raw instanceof Map<?, ?> root)) {
                throw new IllegalArgumentException("Invalid documentation.json format");
            }

            @SuppressWarnings("unchecked") final var classes = (Map<String, List<Map<String, Object>>>) ((Map<String, Object>) root).get("classes");
            final var schemaContent = generate(classes, "io.yupiik.statura.CheckExecutor$CheckExecutorConfiguration");
            Files.createDirectories(output.getParent());
            try (var writer = new FileWriter(output.toFile())) {
                writer.write(schemaContent);
            }
        } catch (final IOException ioe) {
            throw new UncheckedIOException(ioe);
        }

        Logger.getLogger(getClass().getName()).info(() -> "Generated '" + output + "'");
    }

    public String generate(final Map<String, List<Map<String, Object>>> classDocs, final String rootClassName) {
        final var defs = new LinkedHashMap<String, Object>();
        final var root = new LinkedHashMap<String, Object>();
        root.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        root.put("title", "Statura Configuration");
        root.put("type", "object");
        root.put("properties", propertiesFor(classDocs, defs, rootClassName));
        if (!defs.isEmpty()) {
            root.put("$defs", defs);
        }
        return toJson(root, 0);
    }

    private Map<String, Object> propertiesFor(
            final Map<String, List<Map<String, Object>>> classDocs,
            final Map<String, Object> defs,
            final String className) {
        final var props = new LinkedHashMap<String, Object>();
        final var entries = classDocs.get(className);
        if (entries == null) {
            return props;
        }

        final var recordClass = loadClass(className);

        final Map<String, RecordComponentInfo> compTypes;
        if (recordClass != null) {
            compTypes = Stream.of(recordClass.getRecordComponents())
                    .collect(Collectors.toMap(
                            RecordComponent::getName,
                            c -> new RecordComponentInfo(c.getType(), c.getGenericType())));
        } else {
            compTypes = Map.of();
        }

        for (final var entry : entries) {
            final var javaName = (String) entry.getOrDefault("javaName", "");
            final var ref = (String) entry.get("ref");
            final var name = (String) entry.get("name");
            final var documentation = (String) entry.getOrDefault("documentation", "");
            final var defaultValue = entry.get("defaultValue");

            final var propName = normalizePropertyName(name, javaName);
            final var compInfo = compTypes.get(javaName);

            if (ref != null && name != null && name.endsWith(".$index")) {
                props.put(propName, arrayOfRef(ref, documentation, defaultValue, classDocs, defs));
            } else if (compInfo != null && isListType(compInfo.rawType())) {
                props.put(propName, listSchema(compInfo.genericType(), ref, documentation, defaultValue, classDocs, defs));
            } else if (compInfo != null && isMapType(compInfo.rawType())) {
                props.put(propName, mapSchema(compInfo.genericType(), documentation, defaultValue, defs));
            } else if (ref != null) {
                props.put(propName, refOrNested(ref, documentation, defaultValue, classDocs, defs));
            } else if (compInfo != null && compInfo.rawType().isEnum()) {
                final var defName = compInfo.rawType().getName().replace('$', '.');
                ensureEnumDef(defName, compInfo.rawType(), documentation, defaultValue, defs);
                final var refMap = new LinkedHashMap<String, Object>();
                refMap.put("$ref", "#/$defs/" + defName);
                props.put(propName, refMap);
            } else {
                props.put(propName, leafSchema(compInfo != null ? compInfo.rawType() : String.class, documentation, defaultValue));
            }
        }
        return props;
    }

    private String normalizePropertyName(final String name, final String javaName) {
        if (name == null) return javaName;
        final var stripped = name.startsWith("-.") ? name.substring(2) : name;
        if (stripped.endsWith(".$index")) {
            return javaName;
        }
        return stripped;
    }

    private boolean isListType(final Class<?> type) {
        return type == List.class || type == Collection.class;
    }

    private boolean isMapType(final Class<?> type) {
        return type == Map.class;
    }

    private Object leafSchema(final Class<?> type, final String documentation, final Object defaultValue) {
        final var schema = typeOnlySchema(type);
        addMeta(schema, documentation, defaultValue, schema.get("type"));
        return schema;
    }

    private Object listSchema(final Type genericType, final String ref,
                              final String documentation, final Object defaultValue,
                              final Map<String, List<Map<String, Object>>> classDocs,
                              final Map<String, Object> defs) {
        final Object itemsSchema;
        if (ref != null) {
            itemsSchema = refOrNested(ref, null, null, classDocs, defs);
        } else {
            final var itemType = genericType instanceof ParameterizedType pt && pt.getActualTypeArguments().length == 1
                    ? pt.getActualTypeArguments()[0]
                    : String.class;
            itemsSchema = itemSchema(itemType, classDocs, defs);
        }

        final var schema = new LinkedHashMap<String, Object>();
        schema.put("type", "array");
        schema.put("items", itemsSchema);
        addMeta(schema, documentation, defaultValue, "array");
        return schema;
    }

    private Object arrayOfRef(final String ref, final String documentation,
                              final Object defaultValue,
                              final Map<String, List<Map<String, Object>>> classDocs,
                              final Map<String, Object> defs) {
        final var itemsSchema = refOrNested(ref, null, null, classDocs, defs);
        final var schema = new LinkedHashMap<String, Object>();
        schema.put("type", "array");
        schema.put("items", itemsSchema);
        addMeta(schema, documentation, defaultValue, "array");
        return schema;
    }

    private Object itemSchema(final Type type,
                              final Map<String, List<Map<String, Object>>> classDocs,
                              final Map<String, Object> defs) {
        final var raw = type instanceof Class<?> c ? c :
                type instanceof ParameterizedType pt ? (Class<?>) pt.getRawType() :
                Object.class;
        if (PRIMITIVE_TYPES.contains(raw)) {
            return typeOnlySchema(raw);
        }
        if (raw.isEnum()) {
            final var defName = raw.getName().replace('$', '.');
            ensureEnumDef(defName, raw, null, null, defs);
            final var refMap = new LinkedHashMap<String, Object>();
            refMap.put("$ref", "#/$defs/" + defName);
            return refMap;
        }
        if (raw == List.class && type instanceof ParameterizedType pt) {
            return listSchema(pt, null, "", null, classDocs, defs);
        }
        if (raw == Map.class && type instanceof ParameterizedType pt) {
            return mapSchema(pt, "", null, defs);
        }
        return typeOnlySchema(raw);
    }

    private Object mapSchema(final Type genericType,
                             final String documentation,
                             final Object defaultValue,
                             final Map<String, Object> defs) {
        final Type valueType;
        if (genericType instanceof ParameterizedType pt && pt.getActualTypeArguments().length == 2) {
            valueType = pt.getActualTypeArguments()[1];
        } else {
            valueType = String.class;
        }
        final var valueRaw = valueType instanceof Class ? (Class<?>) valueType : Object.class;

        final Object additionalSchema;
        if (PRIMITIVE_TYPES.contains(valueRaw)) {
            additionalSchema = typeOnlySchema(valueRaw);
        } else if (valueRaw.isEnum()) {
            final var defName = valueRaw.getName().replace('$', '.');
            ensureEnumDef(defName, valueRaw, null, null, defs);
            final var refMap = new LinkedHashMap<String, Object>();
            refMap.put("$ref", "#/$defs/" + defName);
            additionalSchema = refMap;
        } else {
            additionalSchema = typeOnlySchema(valueRaw);
        }

        final var schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        schema.put("additionalProperties", additionalSchema);
        final var note = "Use {\"$asList\": true, ...} for Map entries serialization.";
        final var fullDoc = documentation.isBlank() ? note : documentation + " " + note;
        addMeta(schema, fullDoc, defaultValue, "object");
        return schema;
    }

    private Map<String, Object> refOrNested(final String refClassName, final String documentation,
                                            final Object defaultValue,
                                            final Map<String, List<Map<String, Object>>> classDocs,
                                            final Map<String, Object> defs) {
        final var defName = refClassName.replace('$', '.');
        if (!defs.containsKey(defName)) {
            final var props = propertiesFor(classDocs, defs, defName);
            final var nestedSchema = new LinkedHashMap<String, Object>();
            nestedSchema.put("type", "object");
            nestedSchema.put("properties", props);
            addMeta(nestedSchema, documentation, defaultValue, "object");
            defs.put(defName, nestedSchema);
        }
        final var ref = new LinkedHashMap<String, Object>();
        ref.put("$ref", "#/$defs/" + defName);
        return ref;
    }

    private void ensureEnumDef(final String defName, final Class<?> enumType,
                               final String documentation, final Object defaultValue,
                               final Map<String, Object> defs) {
        if (defs.containsKey(defName)) {
            return;
        }
        final var schema = new LinkedHashMap<String, Object>();
        schema.put("type", "string");
        final var constants = new ArrayList<>();
        for (final var c : enumType.getEnumConstants()) {
            constants.add(c instanceof Enum<?> e ? e.name() : c.toString());
        }
        schema.put("enum", constants);
        addMeta(schema, documentation, defaultValue, "string");
        defs.put(defName, schema);
    }

    private LinkedHashMap<String, Object> typeOnlySchema(final Class<?> type) {
        final var schema = new LinkedHashMap<String, Object>();
        if (type == String.class || type == CharSequence.class ||
                type == java.math.BigInteger.class || type == java.math.BigDecimal.class) {
            schema.put("type", "string");
        } else if (type == boolean.class || type == Boolean.class) {
            schema.put("type", "boolean");
        } else if (type == int.class || type == Integer.class || type == long.class || type == Long.class) {
            schema.put("type", "integer");
        } else if (type == float.class || type == Float.class || type == double.class || type == Double.class) {
            schema.put("type", "number");
        } else {
            schema.put("type", "string");
        }
        return schema;
    }

    private void addMeta(final LinkedHashMap<String, Object> schema, final String documentation,
                         final Object defaultValue, final Object typeObj) {
        if (documentation != null && !documentation.isBlank()) {
            schema.put("description", documentation);
        }
        if (defaultValue != null) {
            final var jsonDefault = toJsonDefault(defaultValue, typeObj);
            if (jsonDefault != null) {
                schema.put("default", jsonDefault);
            }
        }
    }

    private Object toJsonDefault(final Object rawDefault, final Object typeObj) {
        switch (rawDefault) {
            case null -> {
                return null;
            }
            case Number n -> {
                return n;
            }
            case Boolean b -> {
                return b;
            }
            default -> {
            }
        }

        final var javaDefault = rawDefault.toString();
        final var type = typeObj instanceof String s ? s : null;

        if ("boolean".equals(type)) {
            return Boolean.parseBoolean(javaDefault);
        }
        if ("integer".equals(type)) {
            try {
                if (javaDefault.startsWith("\"") && javaDefault.endsWith("\"")) {
                    return Integer.parseInt(javaDefault.substring(1, javaDefault.length() - 1));
                }
                return Integer.parseInt(javaDefault);
            } catch (final NumberFormatException e) {
                return javaDefault;
            }
        }
        if ("number".equals(type)) {
            try {
                return Double.parseDouble(javaDefault);
            } catch (final NumberFormatException e) {
                return javaDefault;
            }
        }

        if (javaDefault.startsWith("\"") && javaDefault.endsWith("\"")) {
            return javaDefault.substring(1, javaDefault.length() - 1);
        }

        if ("true".equals(javaDefault) || "false".equals(javaDefault)) {
            return Boolean.parseBoolean(javaDefault);
        }

        try {
            return Integer.parseInt(javaDefault);
        } catch (final NumberFormatException e) {
            // not an int
        }

        java.util.regex.Matcher matcher;
        if (javaDefault.contains("Map.of()")) return Map.of();
        if (javaDefault.contains("List.of()")) return List.of();

        // simple Map.of(key, value) without nested calls
        matcher = java.util.regex.Pattern.compile("Map\\.of\\((.+)\\)").matcher(javaDefault);
        if (matcher.find()) {
            return javaDefault;
        }

        // extract simple enum constant from FQN like "io.yupiik.statura.CheckExecutor.CheckType.HTTP"
        if (javaDefault.chars().allMatch(c -> Character.isJavaIdentifierPart(c) || c == '.')) {
            final var lastDot = javaDefault.lastIndexOf('.');
            if (lastDot > 0 && lastDot < javaDefault.length() - 1) {
                final var candidate = javaDefault.substring(lastDot + 1);
                if (candidate.equals(candidate.toUpperCase()) || candidate.contains("_")) {
                    return candidate;
                }
            }
        }

        return javaDefault;
    }

    private Class<?> loadClass(final String name) {
        try {
            return Class.forName(name);
        } catch (final ClassNotFoundException e) {
            final var dot = name.lastIndexOf('.');
            if (dot > 0) {
                try {
                    return Class.forName(name.substring(0, dot) + "$" + name.substring(dot + 1));
                } catch (final ClassNotFoundException e2) {
                    return null;
                }
            }
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private String toJson(final Object value, final int indent) {
        final var in = "  ".repeat(indent);
        final var in1 = "  ".repeat(indent + 1);
        final var in2 = "  ".repeat(indent + 2);
        if (value == null) return "null";
        if (value instanceof String s) return JsonStrings.escape(s);
        if (value instanceof Boolean || value instanceof Number) return value.toString();
        if (value instanceof Map<?, ?> map) {
            if (map.isEmpty()) return "{}";
            final var sb = new StringBuilder();
            sb.append("{\n");
            var first = true;
            for (final var entry : ((Map<String, Object>) map).entrySet()) {
                if (!first) sb.append(",\n");
                first = false;
                sb.append(in1).append(JsonStrings.escape(entry.getKey())).append(": ").append(toJson(entry.getValue(), indent + 1));
            }
            sb.append("\n").append(in).append("}");
            return sb.toString();
        }
        if (value instanceof List<?> list) {
            if (list.isEmpty()) return "[]";
            final var sb = new StringBuilder();
            sb.append("[\n");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(",\n");
                sb.append(in2).append(toJson(list.get(i), indent + 2));
            }
            sb.append("\n").append(in1).append("]");
            return sb.toString();
        }
        return String.valueOf(value);
    }

    private record RecordComponentInfo(Class<?> rawType, Type genericType) {
    }
}
