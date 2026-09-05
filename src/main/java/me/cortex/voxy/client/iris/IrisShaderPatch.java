package me.cortex.voxy.client.iris;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.JsonAdapter;
import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import me.cortex.voxy.common.Logger;
import net.coderbot.iris.shaderpack.ShaderPack;
import net.coderbot.iris.shaderpack.include.AbsolutePackPath;
import org.lwjgl.opengl.ARBDrawBuffersBlend;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import static org.lwjgl.opengl.GL11.GL_BLEND;
import static org.lwjgl.opengl.GL11.GL_DST_ALPHA;
import static org.lwjgl.opengl.GL11.GL_DST_COLOR;
import static org.lwjgl.opengl.GL11.GL_ONE;
import static org.lwjgl.opengl.GL11.GL_ONE_MINUS_DST_ALPHA;
import static org.lwjgl.opengl.GL11.GL_ONE_MINUS_DST_COLOR;
import static org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_ONE_MINUS_SRC_COLOR;
import static org.lwjgl.opengl.GL11.GL_SRC_ALPHA;
import static org.lwjgl.opengl.GL11.GL_SRC_ALPHA_SATURATE;
import static org.lwjgl.opengl.GL11.GL_SRC_COLOR;
import static org.lwjgl.opengl.GL11.GL_ZERO;
import static org.lwjgl.opengl.GL14.glBlendFuncSeparate;
import static org.lwjgl.opengl.GL11.glDisable;
import static org.lwjgl.opengl.GL11.glEnable;
import static org.lwjgl.opengl.GL33.GL_ONE_MINUS_SRC1_ALPHA;
import static org.lwjgl.opengl.GL33.GL_ONE_MINUS_SRC1_COLOR;
import static org.lwjgl.opengl.GL33.GL_SRC1_COLOR;
import static org.lwjgl.opengl.GL33.glDisablei;
import static org.lwjgl.opengl.GL33.glEnablei;

/** Parsed, preprocessed Voxy shader-pack contract. */
public final class IrisShaderPatch {
    public static final int VERSION = 1;
    public static final int SHADER_DEFINE_VERSION = 2;

    private static final Gson GSON = new GsonBuilder()
            .excludeFieldsWithModifiers(Modifier.PRIVATE)
            .setLenient()
            .create();

    private final PatchData patchData;
    private final Int2ObjectMap<String> ssbos;

    private IrisShaderPatch(PatchData patchData) {
        this.patchData = patchData;
        this.ssbos = patchData.ssbos == null
                ? new Int2ObjectOpenHashMap<>()
                : patchData.ssbos;
    }

    private static final class SsboDeserializer
            implements JsonDeserializer<Int2ObjectOpenHashMap<String>> {
        @Override
        public Int2ObjectOpenHashMap<String> deserialize(
                JsonElement json,
                Type typeOfT,
                JsonDeserializationContext context) throws JsonParseException {
            if (json == null || json.isJsonNull()) {
                return null;
            }

            Int2ObjectOpenHashMap<String> result = new Int2ObjectOpenHashMap<>();
            for (var entry : json.getAsJsonObject().entrySet()) {
                result.put(Integer.parseInt(entry.getKey()), entry.getValue().getAsString());
            }
            return result;
        }
    }

    private static final class SamplerDeserializer
            implements JsonDeserializer<Object2ObjectLinkedOpenHashMap<String, String>> {
        @Override
        public Object2ObjectLinkedOpenHashMap<String, String> deserialize(
                JsonElement json,
                Type typeOfT,
                JsonDeserializationContext context) throws JsonParseException {
            if (json == null || json.isJsonNull()) {
                return null;
            }

            Object2ObjectLinkedOpenHashMap<String, String> result =
                    new Object2ObjectLinkedOpenHashMap<>();
            if (json.isJsonArray()) {
                for (JsonElement element : json.getAsJsonArray()) {
                    String name = element.getAsString();
                    result.put(name, defaultSamplerType(name));
                }
                return result;
            }

            for (var entry : json.getAsJsonObject().entrySet()) {
                String type = entry.getValue().isJsonNull()
                        ? defaultSamplerType(entry.getKey())
                        : entry.getValue().getAsString();
                result.put(entry.getKey(), type);
            }
            return result;
        }

        private static String defaultSamplerType(String name) {
            return name.startsWith("shadowtex") ? "sampler2DShadow" : "sampler2D";
        }
    }

    public record BlendState(int buffer, boolean off, int sourceRgb, int destinationRgb,
                             int sourceAlpha, int destinationAlpha) {
        private static final BlendState ALL_OFF = new BlendState(-1, true, 0, 0, 0, 0);
    }

    private static final class BlendStateDeserializer
            implements JsonDeserializer<Int2ObjectMap<BlendState>> {
        @Override
        public Int2ObjectMap<BlendState> deserialize(
                JsonElement json,
                Type typeOfT,
                JsonDeserializationContext context) throws JsonParseException {
            if (json == null || json.isJsonNull()) {
                return null;
            }

            Int2ObjectMap<BlendState> result = new Int2ObjectOpenHashMap<>();
            if (json.isJsonPrimitive()) {
                if (json.getAsString().equalsIgnoreCase("off")) {
                    result.put(-1, BlendState.ALL_OFF);
                    return result;
                }
                throw new JsonParseException("unknown global Voxy blend state: " + json);
            }

            for (var entry : json.getAsJsonObject().entrySet()) {
                int buffer = Integer.parseInt(entry.getKey());
                JsonElement value = entry.getValue();
                if (value.isJsonPrimitive()
                        && value.getAsString().equalsIgnoreCase("off")) {
                    result.put(buffer, new BlendState(buffer, true, 0, 0, 0, 0));
                    continue;
                }

                List<String> parts = new ArrayList<>(4);
                if (value.isJsonArray()) {
                    for (JsonElement element : value.getAsJsonArray()) {
                        parts.add(element.getAsString());
                    }
                } else if (value.isJsonPrimitive()) {
                    for (String part : value.getAsString().trim().split("\\s+")) {
                        if (!part.isEmpty()) {
                            parts.add(part);
                        }
                    }
                } else {
                    throw new JsonParseException("unknown Voxy blend state: " + value);
                }

                if (parts.size() != 4) {
                    throw new JsonParseException(
                            "Voxy blend state for buffer " + buffer + " requires four factors");
                }
                result.put(buffer, new BlendState(
                        buffer,
                        false,
                        parseFactor(parts.get(0)),
                        parseFactor(parts.get(1)),
                        parseFactor(parts.get(2)),
                        parseFactor(parts.get(3))));
            }
            return result;
        }

        private static int parseFactor(String factor) {
            String normalized = factor.toUpperCase(Locale.ROOT);
            if (!normalized.startsWith("GL_")) {
                normalized = "GL_" + normalized;
            }
            return switch (normalized) {
                case "GL_ZERO" -> GL_ZERO;
                case "GL_ONE" -> GL_ONE;
                case "GL_SRC_COLOR" -> GL_SRC_COLOR;
                case "GL_ONE_MINUS_SRC_COLOR" -> GL_ONE_MINUS_SRC_COLOR;
                case "GL_SRC_ALPHA" -> GL_SRC_ALPHA;
                case "GL_ONE_MINUS_SRC_ALPHA" -> GL_ONE_MINUS_SRC_ALPHA;
                case "GL_DST_ALPHA" -> GL_DST_ALPHA;
                case "GL_ONE_MINUS_DST_ALPHA" -> GL_ONE_MINUS_DST_ALPHA;
                case "GL_DST_COLOR" -> GL_DST_COLOR;
                case "GL_ONE_MINUS_DST_COLOR" -> GL_ONE_MINUS_DST_COLOR;
                case "GL_SRC_ALPHA_SATURATE" -> GL_SRC_ALPHA_SATURATE;
                case "GL_SRC1_COLOR" -> GL_SRC1_COLOR;
                case "GL_ONE_MINUS_SRC1_COLOR" -> GL_ONE_MINUS_SRC1_COLOR;
                case "GL_ONE_MINUS_SRC1_ALPHA" -> GL_ONE_MINUS_SRC1_ALPHA;
                default -> throw new JsonParseException("unknown OpenGL blend factor " + factor);
            };
        }
    }

    private static final class PatchData {
        int version;
        int[] opaqueDrawBuffers;
        int[] translucentDrawBuffers;
        String[] uniforms;
        @JsonAdapter(SamplerDeserializer.class)
        Object2ObjectLinkedOpenHashMap<String, String> samplers;
        String opaquePatchData;
        String translucentPatchData;
        @JsonAdapter(SsboDeserializer.class)
        Int2ObjectOpenHashMap<String> ssbos;
        @JsonAdapter(BlendStateDeserializer.class)
        Int2ObjectOpenHashMap<BlendState> blending;
        String taaOffset;
        boolean excludeLodsFromVanillaDepth;
        float[] renderScale;
        boolean useViewportDims;
        boolean skipShaderDepthHackFix;

        String validate() {
            if (version != VERSION) {
                return "unsupported Voxy patch version " + version + "; expected " + VERSION;
            }
            if (opaquePatchData == null || opaquePatchData.isBlank()) {
                return "opaquePatchData is missing";
            }
            if (uniforms == null) {
                return "uniforms is missing";
            }
            if (opaqueDrawBuffers == null || opaqueDrawBuffers.length == 0) {
                return "opaqueDrawBuffers is missing";
            }
            if (translucentDrawBuffers == null || translucentDrawBuffers.length == 0) {
                return "translucentDrawBuffers is missing";
            }
            if (blending != null) {
                for (BlendState state : blending.values()) {
                    if (state == null) {
                        return "blending contains a null state";
                    }
                    if (state.buffer != -1
                            && (state.buffer < 0 || state.buffer >= translucentDrawBuffers.length)) {
                        return "blend target " + state.buffer
                                + " is outside translucentDrawBuffers["
                                + translucentDrawBuffers.length + "]";
                    }
                }
            }
            return null;
        }
    }

    public boolean useViewportDims() {
        return patchData.useViewportDims;
    }

    public boolean skipShaderDepthHackFix() {
        return patchData.skipShaderDepthHackFix;
    }

    public Int2ObjectMap<String> getSSBOs() {
        return new Int2ObjectLinkedOpenHashMap<>(ssbos);
    }

    public String getPatchOpaqueSource() {
        return patchData.opaquePatchData;
    }

    public String getPatchTranslucentSource() {
        return patchData.translucentPatchData;
    }

    public String getTAAShift() {
        return patchData.taaOffset;
    }

    public String[] getUniformList() {
        return patchData.uniforms.clone();
    }

    public Object2ObjectLinkedOpenHashMap<String, String> getSamplerSet() {
        return patchData.samplers == null
                ? null
                : new Object2ObjectLinkedOpenHashMap<>(patchData.samplers);
    }

    public int[] getOpaqueTargets() {
        return patchData.opaqueDrawBuffers.clone();
    }

    /** Kept for source compatibility with the selected Voxy baseline. */
    public int[] getOpqaueTargets() {
        return getOpaqueTargets();
    }

    public int[] getTranslucentTargets() {
        return patchData.translucentDrawBuffers.clone();
    }

    public boolean emitToVanillaDepth() {
        return !patchData.excludeLodsFromVanillaDepth;
    }

    public float[] getRenderScale() {
        if (patchData.renderScale == null || patchData.renderScale.length == 0) {
            return new float[]{1.0f, 1.0f};
        }
        if (patchData.renderScale.length == 1) {
            float scale = Math.max(0.01f, patchData.renderScale[0]);
            return new float[]{scale, scale};
        }
        return new float[]{
                Math.max(0.01f, patchData.renderScale[0]),
                Math.max(0.01f, patchData.renderScale[1])};
    }

    public boolean deferedTranslucentRendering() {
        return false;
    }

    public Runnable createBlendSetup() {
        if (patchData.blending == null || patchData.blending.isEmpty()) {
            return () -> { };
        }
        return () -> {
            BlendState global = patchData.blending.get(-1);
            if (global != null) {
                if (global.off) {
                    glDisable(GL_BLEND);
                } else {
                    glEnable(GL_BLEND);
                    glBlendFuncSeparate(global.sourceRgb, global.destinationRgb,
                            global.sourceAlpha, global.destinationAlpha);
                }
            }

            for (var entry : patchData.blending.int2ObjectEntrySet()) {
                if (entry.getIntKey() == -1) {
                    continue;
                }
                BlendState state = entry.getValue();
                if (state.off) {
                    glDisablei(GL_BLEND, state.buffer);
                } else {
                    glEnablei(GL_BLEND, state.buffer);
                    ARBDrawBuffersBlend.glBlendFuncSeparateiARB(
                            state.buffer,
                            state.sourceRgb,
                            state.destinationRgb,
                            state.sourceAlpha,
                            state.destinationAlpha);
                }
            }
        };
    }

    public static IrisShaderPatch makePatch(
            ShaderPack ignoredPack,
            AbsolutePackPath directory,
            Function<AbsolutePackPath, String> sourceProvider) {
        String json = sourceProvider.apply(directory.resolve("voxy.json"));
        if (json == null || json.isBlank()) {
            return null;
        }
        return parse(
                json,
                sourceProvider.apply(directory.resolve("voxy_opaque.glsl")),
                sourceProvider.apply(directory.resolve("voxy_translucent.glsl")),
                sourceProvider.apply(directory.resolve("voxy_taa.glsl")));
    }

    static IrisShaderPatch parseForTest(String preprocessedJson, String opaquePatch) {
        return parse(preprocessedJson, opaquePatch, null, null);
    }

    private static IrisShaderPatch parse(
            String preprocessedJson,
            String externalOpaque,
            String externalTranslucent,
            String externalTaa) {
        String normalized = normalizePreprocessedJson(preprocessedJson);
        try {
            PatchData data = GSON.fromJson(normalized, PatchData.class);
            if (data == null) {
                throw new JsonParseException("Voxy patch parsed to null");
            }

            if (externalOpaque != null && !externalOpaque.isBlank()) {
                Logger.info("External opaque Voxy shader patch applied");
                data.opaquePatchData = externalOpaque;
            }
            if (externalTranslucent != null && !externalTranslucent.isBlank()) {
                Logger.info("External translucent Voxy shader patch applied");
                data.translucentPatchData = externalTranslucent;
            }
            if (externalTaa != null && !externalTaa.isBlank()) {
                Logger.info("External Voxy TAA patch applied");
                data.taaOffset = externalTaa;
            }

            String invalidReason = data.validate();
            if (invalidReason != null) {
                throw new JsonParseException(invalidReason);
            }
            return new IrisShaderPatch(data);
        } catch (RuntimeException exception) {
            Logger.error("Failed to parse preprocessed Voxy shader-pack metadata", exception);
            try {
                Files.writeString(Path.of("voxy-shader-patch-failure.json"), normalized);
            } catch (IOException dumpFailure) {
                exception.addSuppressed(dumpFailure);
            }
            throw new ShaderLoadError("Failed to parse Voxy shader-pack metadata", exception);
        }
    }

    private static String normalizePreprocessedJson(String source) {
        // Oculus runs voxy.json through the same C preprocessor as GLSL. Escape
        // backslashes and quotation marks inside // comments so Gson's lenient
        // parser cannot mistake shader comments for JSON string delimiters.
        String escaped = source.replace("\\", "\\\\");
        StringBuilder builder = new StringBuilder(escaped.length());
        for (String line : escaped.split("\\n", -1)) {
            int comment = line.indexOf("//");
            if (comment >= 0) {
                builder.append(line, 0, comment)
                        .append(line.substring(comment).replace("\"", "\\\""));
            } else {
                builder.append(line);
            }
            builder.append('\n');
        }
        return builder.toString().replaceAll("void _cfi_ignoreMarker\\(\\) \\{\\}", "");
    }
}
