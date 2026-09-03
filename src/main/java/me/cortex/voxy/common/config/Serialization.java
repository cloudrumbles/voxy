package me.cortex.voxy.common.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.ForgePlatform;
import me.cortex.voxy.commonImpl.VoxyCommon;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Serialization {
    public static final Set<Class<?>> CONFIG_TYPES = new HashSet<>();
    public static Gson GSON;

    private static final class GsonConfigSerialization<T> implements TypeAdapterFactory {
        private final String typeField = "TYPE";
        private final Class<T> clz;

        private final Map<String, Class<? extends T>> name2type = new HashMap<>();
        private final Map<Class<? extends T>, String> type2name = new HashMap<>();

        private GsonConfigSerialization(Class<T> clz) {
            this.clz = clz;
        }

        public GsonConfigSerialization<T> register(String typeName, Class<? extends T> cls) {
            if (this.name2type.put(typeName, cls) != null) {
                throw new IllegalStateException("Type name already registered: " + typeName);
            }
            if (this.type2name.put(cls, typeName) != null) {
                throw new IllegalStateException("Class already registered with type name: " + typeName + ", " + cls);
            }
            return this;
        }

        private T deserialize(Gson gson, JsonElement json) {
            var retype = this.name2type.get(json.getAsJsonObject().remove(this.typeField).getAsString());
            return gson.getDelegateAdapter(this, TypeToken.get(retype)).fromJsonTree(json);
        }

        private JsonElement serialize(Gson gson, T value) {
            String name = this.type2name.get(value.getClass());
            if (name == null) {
                name = "UNKNOWN_TYPE_{" + value.getClass().getName() + "}";
            }

            var valueJson = gson
                    .getDelegateAdapter(this, TypeToken.get((Class<T>) value.getClass()))
                    .toJsonTree(value);
            var json = new JsonObject();
            json.addProperty(this.typeField, name);
            for (Map.Entry<String, JsonElement> entry : valueJson.getAsJsonObject().entrySet()) {
                json.add(entry.getKey(), entry.getValue());
            }
            return json;
        }

        @Override
        public <X> TypeAdapter<X> create(Gson gson, TypeToken<X> type) {
            if (this.clz.isAssignableFrom(type.getRawType())) {
                var jsonObjectAdapter = gson.getAdapter(JsonElement.class);

                return (TypeAdapter<X>) new TypeAdapter<T>() {
                    @Override
                    public void write(JsonWriter out, T value) throws IOException {
                        jsonObjectAdapter.write(out, GsonConfigSerialization.this.serialize(gson, value));
                    }

                    @Override
                    public T read(JsonReader in) throws IOException {
                        var object = jsonObjectAdapter.read(in);
                        return GsonConfigSerialization.this.deserialize(gson, object);
                    }
                };
            }
            return null;
        }
    }

    public static void init() {
        String baseSearchPackage = "me.cortex.voxy";

        Map<Class<?>, GsonConfigSerialization<?>> serializers = new HashMap<>();

        Set<String> classNames = new LinkedHashSet<>();
        ForgePlatform.modRoot("voxy")
                .ifPresent(path -> classNames.addAll(collectAllClasses(path, baseSearchPackage)));
        classNames.addAll(collectAllClasses(baseSearchPackage));
        int count = 0;
        outer:
        for (var className : classNames) {
            if (VoxyCommon.IS_DEDICATED_SERVER && className.startsWith("me.cortex.voxy.client")) {
                continue;
            }
            if (!className.toLowerCase(Locale.ROOT).contains("config")) {
                continue;
            }
            if (className.contains("mixin")) {
                continue;
            }
            if (className.contains("ModMenuIntegration")) {
                continue;
            }
            if (className.contains("VoxyConfigScreenPages")) {
                continue;
            }
            if (className.endsWith("VoxyConfig")) {
                continue;
            }
            if (className.equals(Serialization.class.getName())) {
                continue;
            }

            try {
                var clz = Class.forName(className);
                if (Modifier.isAbstract(clz.getModifiers())) {
                    continue;
                }
                var original = clz;
                while ((clz = clz.getSuperclass()) != null) {
                    if (CONFIG_TYPES.contains(clz)) {
                        Method nameMethod = null;
                        try {
                            nameMethod = original.getMethod("getConfigTypeName");
                            nameMethod.setAccessible(true);
                        } catch (NoSuchMethodException ignored) {
                        }
                        if (nameMethod == null) {
                            Logger.error("WARNING: Config class " + className
                                    + " doesnt contain a getConfigTypeName and thus wont be serializable");
                            continue outer;
                        }
                        count++;
                        String name = (String) nameMethod.invoke(null);
                        serializers.computeIfAbsent(clz, GsonConfigSerialization::new)
                                .register(name, (Class) original);
                        Logger.info("Registered " + original.getSimpleName() + " as " + name
                                + " for config type " + clz.getSimpleName());
                        break;
                    }
                }
            } catch (Throwable exception) {
                Logger.error("Error while setting up config serialization", exception);
            }
        }

        var builder = new GsonBuilder().setPrettyPrinting();
        for (var entry : serializers.entrySet()) {
            builder.registerTypeAdapterFactory(entry.getValue());
        }

        GSON = builder.create();
        Logger.info("Registered " + count + " config types");
    }

    /**
     * Scans exploded development resources. Packaged SecureJar roots are
     * handled by the Path overload above; a missing directory resource in a
     * normal jar is expected and must not be logged as an error.
     */
    private static List<String> collectAllClasses(String pack) {
        InputStream stream = Serialization.class.getClassLoader()
                .getResourceAsStream(pack.replace('.', '/'));
        if (stream == null) {
            return List.of();
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
            return reader.lines().flatMap(inner -> {
                if (inner.endsWith(".class")) {
                    return Stream.of(pack + "." + inner.substring(0, inner.length() - ".class".length()));
                }
                if (!inner.contains(".")) {
                    return collectAllClasses(pack + "." + inner).stream();
                }
                return Stream.empty();
            }).collect(Collectors.toList());
        } catch (IOException exception) {
            Logger.error("Failed to collect classes in package: " + pack, exception);
            return List.of();
        }
    }

    private static List<String> collectAllClasses(Path base, String pack) {
        Path packageRoot = base.resolve(pack.replace('.', '/'));
        if (!Files.isDirectory(packageRoot)) {
            return List.of();
        }

        try (Stream<Path> children = Files.list(packageRoot)) {
            return children.flatMap(inner -> {
                String name = inner.getFileName().toString();
                if (name.endsWith(".class")) {
                    return Stream.of(pack + "." + name.substring(0, name.length() - ".class".length()));
                }
                if (Files.isDirectory(inner)) {
                    return collectAllClasses(base, pack + "." + name).stream();
                }
                return Stream.empty();
            }).collect(Collectors.toList());
        } catch (IOException exception) {
            throw new RuntimeException("Failed to scan packaged classes under " + packageRoot, exception);
        }
    }
}
