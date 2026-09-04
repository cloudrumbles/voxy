package me.cortex.voxy.common.util;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public class MultiGson {
    private final List<Class<?>> classes;
    private final Gson gson;

    private MultiGson(Gson gson, List<Class<?>> classes) {
        this.gson = gson;
        this.classes = classes;
    }

    public String toJson(Object... objects) {
        Object[] map = new Object[this.classes.size()];
        if (map.length != objects.length) {
            throw new IllegalArgumentException("Incorrect number of input args");
        }
        for (var obj : objects) {
            if (obj == null) {
                throw new IllegalArgumentException("Input objects cannot be null");
            }
            int index = this.classes.indexOf(obj.getClass());
            if (index == -1) {
                throw new IllegalArgumentException("Unknown object class: " + obj.getClass());
            }
            if (map[index] != null) {
                throw new IllegalArgumentException("Duplicate entry classes");
            }
            map[index] = obj;
        }

        var json = new JsonObject();
        for (Object entry : map) {
            JsonObject entryJson = this.gson.toJsonTree(entry).getAsJsonObject();
            for (Map.Entry<String, JsonElement> field : entryJson.entrySet()) {
                if (json.has(field.getKey())) {
                    throw new IllegalArgumentException(
                            "Duplicate name inside unified json: " + field.getKey());
                }
                json.add(field.getKey(), field.getValue());
            }
        }
        return this.gson.toJson(json);
    }

    public Map<Class<?>, Object> fromJson(String json) {
        var object = this.gson.fromJson(json, JsonObject.class);
        LinkedHashMap<Class<?>, Object> objects = new LinkedHashMap<>();
        for (var type : this.classes) {
            objects.put(type, this.gson.fromJson(object, type));
        }
        return objects;
    }

    public static class Builder {
        private final LinkedHashSet<Class<?>> classes = new LinkedHashSet<>();
        private final GsonBuilder gsonBuilder;

        public Builder(GsonBuilder gsonBuilder) {
            this.gsonBuilder = gsonBuilder;
        }

        public Builder() {
            this(new GsonBuilder()
                    .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
                    .setPrettyPrinting()
                    .excludeFieldsWithModifiers(Modifier.PRIVATE));
        }

        public Builder add(Class<?> type) {
            if (!this.classes.add(type)) {
                throw new IllegalArgumentException("Class has already been added");
            }
            return this;
        }

        public MultiGson build() {
            return new MultiGson(this.gsonBuilder.create(), new ArrayList<>(this.classes));
        }
    }

    private static final class A {
        public int a;
        public int b;
        public int c;
        public int d;
    }

    private static final class B {
        public int q;
        public int e;
        public int g;
        public int l;
    }

    public static void main(String[] args) {
        var gson = new Builder().add(A.class).add(B.class).build();
        var a = new A();
        a.c = 11;
        var b = new B();
        System.out.println(gson.fromJson(gson.toJson(a, b)));
    }
}
