package me.cortex.voxy.client.iris;

import com.google.common.collect.ImmutableSet;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import kroppeb.stareval.function.FunctionReturn;
import kroppeb.stareval.function.Type;
import me.cortex.voxy.client.core.IrisVoxyRenderPipeline;
import me.cortex.voxy.client.core.rendering.util.LightMapHelper;
import me.cortex.voxy.client.mixin.iris.CustomUniformsAccessor;
import me.cortex.voxy.client.mixin.iris.IrisRenderingPipelineAccessor;
import me.cortex.voxy.client.mixin.iris.ShaderStorageBufferHolderAccessor;
import me.cortex.voxy.common.Logger;
import net.coderbot.iris.gbuffer_overrides.matching.InputAvailability;
import net.coderbot.iris.gl.buffer.ShaderStorageBuffer;
import net.coderbot.iris.gl.buffer.ShaderStorageBufferHolder;
import net.coderbot.iris.gl.image.ImageHolder;
import net.coderbot.iris.gl.sampler.GlSampler;
import net.coderbot.iris.gl.sampler.SamplerHolder;
import net.coderbot.iris.gl.state.ValueUpdateNotifier;
import net.coderbot.iris.gl.texture.InternalTextureFormat;
import net.coderbot.iris.gl.texture.TextureType;
import net.coderbot.iris.gl.uniform.DynamicLocationalUniformHolder;
import net.coderbot.iris.gl.uniform.FloatSupplier;
import net.coderbot.iris.gl.uniform.LocationalUniformHolder;
import net.coderbot.iris.gl.uniform.Uniform;
import net.coderbot.iris.gl.uniform.UniformHolder;
import net.coderbot.iris.gl.uniform.UniformType;
import net.coderbot.iris.gl.uniform.UniformUpdateFrequency;
import net.coderbot.iris.pipeline.newshader.FogMode;
import net.coderbot.iris.pipeline.newshader.NewWorldRenderingPipeline;
import net.coderbot.iris.rendertarget.RenderTarget;
import net.coderbot.iris.rendertarget.RenderTargets;
import net.coderbot.iris.uniforms.CommonUniforms;
import net.coderbot.iris.uniforms.custom.CustomUniforms;
import net.coderbot.iris.uniforms.custom.cached.BooleanCachedUniform;
import net.coderbot.iris.uniforms.custom.cached.CachedUniform;
import net.coderbot.iris.uniforms.custom.cached.Float2VectorCachedUniform;
import net.coderbot.iris.uniforms.custom.cached.Float3VanillaVectorCachedUniform;
import net.coderbot.iris.uniforms.custom.cached.Float3VectorCachedUniform;
import net.coderbot.iris.uniforms.custom.cached.Float4MatrixCachedUniform;
import net.coderbot.iris.uniforms.custom.cached.Float4VectorCachedUniform;
import net.coderbot.iris.uniforms.custom.cached.FloatCachedUniform;
import net.coderbot.iris.uniforms.custom.cached.Int2VectorCachedUniform;
import net.coderbot.iris.uniforms.custom.cached.IntCachedUniform;
import net.coderbot.iris.vendored.joml.Matrix4f;
import net.coderbot.iris.vendored.joml.Vector2f;
import net.coderbot.iris.vendored.joml.Vector2i;
import net.coderbot.iris.vendored.joml.Vector3f;
import net.coderbot.iris.vendored.joml.Vector4f;
import net.coderbot.iris.vendored.joml.Vector4i;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.DoubleSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.LongConsumer;
import java.util.function.LongFunction;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.lwjgl.opengl.ARBDirectStateAccess.glBindTextureUnit;
import static org.lwjgl.opengl.GL30C.glBindBufferBase;
import static org.lwjgl.opengl.GL33C.glBindSampler;
import static org.lwjgl.opengl.GL43C.GL_SHADER_STORAGE_BUFFER;

/**
 * Immutable bridge between one Oculus shader pipeline and one Voxy render
 * pipeline. It captures shader-pack targets, uniforms, samplers, and SSBOs at
 * Oculus pipeline construction time.
 */
public final class IrisVoxyRenderPipelineData {
    public IrisVoxyRenderPipeline thePipeline;
    public final int[] opaqueDrawTargets;
    public final int[] translucentDrawTargets;
    public final boolean renderToVanillaDepth;
    public final float[] resolutionScale;
    public final String TAA;
    public final boolean useViewportDims;
    public final boolean deferTranslucency;
    public final boolean skipShaderDepthHackFix;

    private final String opaquePatch;
    private final String translucentPatch;
    private final StructLayout uniforms;
    private final Runnable blendingSetup;
    private final ImageSet imageSet;
    private final SSBOSet ssboSet;

    private IrisVoxyRenderPipelineData(
            IrisShaderPatch patch,
            int[] opaqueDrawTargets,
            int[] translucentDrawTargets,
            StructLayout uniforms,
            Runnable blendingSetup,
            ImageSet imageSet,
            SSBOSet ssboSet) {
        this.opaqueDrawTargets = opaqueDrawTargets;
        this.translucentDrawTargets = translucentDrawTargets;
        this.opaquePatch = patch.getPatchOpaqueSource();
        this.translucentPatch = patch.getPatchTranslucentSource();
        this.uniforms = uniforms;
        this.blendingSetup = blendingSetup;
        this.imageSet = imageSet;
        this.ssboSet = ssboSet;
        this.renderToVanillaDepth = patch.emitToVanillaDepth();
        this.TAA = patch.getTAAShift();
        this.resolutionScale = patch.getRenderScale();
        this.useViewportDims = patch.useViewportDims();
        this.deferTranslucency = patch.deferedTranslucentRendering();
        this.skipShaderDepthHackFix = patch.skipShaderDepthHackFix();
    }

    public static IrisVoxyRenderPipelineData buildPipeline(
            NewWorldRenderingPipeline pipeline,
            IrisShaderPatch patch,
            CustomUniforms customUniforms,
            ShaderStorageBufferHolder ssboHolder) {
        IrisRenderingPipelineAccessor accessor = (IrisRenderingPipelineAccessor) pipeline;
        ImmutableSet<Integer> flipped = accessor.voxy$getFlippedAfterPrepare();
        RenderTargets targets = accessor.voxy$getRenderTargets();

        StructLayout uniforms = createUniformLayoutStructAndUpdater(
                createUniformSet(customUniforms, patch), patch.getUniformList());
        ImageSet images = createImageSet(pipeline, flipped, patch);
        SSBOSet ssbos = createSSBOLayouts(patch.getSSBOs(), ssboHolder);
        int[] opaqueTargets = getDrawBuffers(patch.getOpaqueTargets(), flipped, targets);
        int[] translucentTargets = getDrawBuffers(patch.getTranslucentTargets(), flipped, targets);

        return new IrisVoxyRenderPipelineData(
                patch,
                opaqueTargets,
                translucentTargets,
                uniforms,
                patch.createBlendSetup(),
                images,
                ssbos);
    }

    public SSBOSet getSsboSet() {
        return ssboSet;
    }

    public ImageSet getImageSet() {
        return imageSet;
    }

    public StructLayout getUniforms() {
        return uniforms;
    }

    public Runnable getBlender() {
        return blendingSetup;
    }

    public String opaqueFragPatch() {
        return opaquePatch;
    }

    public String translucentFragPatch() {
        return translucentPatch;
    }

    public boolean shouldDeferTranslucency() {
        return deferTranslucency;
    }

    private static int[] getDrawBuffers(
            int[] requestedTargets,
            ImmutableSet<Integer> stageWritesToAlt,
            RenderTargets renderTargets) {
        int[] textureIds = new int[requestedTargets.length];
        for (int index = 0; index < requestedTargets.length; index++) {
            int targetIndex = requestedTargets[index];
            if (targetIndex < 0 || targetIndex >= renderTargets.getRenderTargetCount()) {
                throw new ShaderLoadError(
                        "Voxy shader requested colortex" + targetIndex
                                + " but Oculus exposes only "
                                + renderTargets.getRenderTargetCount() + " render targets");
            }
            RenderTarget target = renderTargets.getOrCreate(targetIndex);
            textureIds[index] = stageWritesToAlt.contains(targetIndex)
                    ? target.getAltTexture()
                    : target.getMainTexture();
        }
        return textureIds;
    }

    public record StructLayout(int size, String layout, LongConsumer updater) {
    }

    private record UniformWritingHolder(
            String name,
            UniformType type,
            LongFunction<LongConsumer> writingFactory) {
    }

    private static StructLayout createUniformLayoutStructAndUpdater(
            Map<String, UniformWritingHolder> available,
            String[] requestedNames) {
        if (requestedNames.length == 0) {
            return null;
        }

        List<UniformWritingHolder> ordered = new ArrayList<>(requestedNames.length);
        Set<String> missing = new LinkedHashSet<>();
        for (String name : requestedNames) {
            UniformWritingHolder holder = available.get(name);
            if (holder == null) {
                missing.add(name);
            } else {
                ordered.add(holder);
            }
        }
        if (!missing.isEmpty()) {
            throw new ShaderLoadError(
                    "Oculus 1.19.2 could not supply Voxy uniforms: "
                            + String.join(", ", missing));
        }

        Map<Integer, UniformWritingHolder> layout = new LinkedHashMap<>();
        int slot = 0;
        for (UniformWritingHolder uniform : ordered) {
            int packed = getSizeAndAlignment(uniform.type);
            int size = packed >>> 5;
            int alignment = packed & 31;
            slot = align(slot, alignment);
            layout.put(slot, uniform);
            slot += size;
        }
        int totalSlots = align(slot, 4);

        StringBuilder declaration = new StringBuilder("{\n");
        for (UniformWritingHolder uniform : ordered) {
            declaration.append("\t")
                    .append(convertToGlslType(uniform.type))
                    .append(' ')
                    .append(uniform.name)
                    .append(";\n");
        }
        declaration.append('}');

        LongConsumer[] writers = new LongConsumer[layout.size()];
        int writerIndex = 0;
        for (var entry : layout.entrySet()) {
            writers[writerIndex++] = entry.getValue().writingFactory.apply(entry.getKey() * 4L);
        }
        LongConsumer updater = pointer -> {
            for (LongConsumer writer : writers) {
                writer.accept(pointer);
            }
        };
        return new StructLayout(totalSlots * 4, declaration.toString(), updater);
    }

    private static int align(int value, int alignment) {
        return (value + alignment - 1) / alignment * alignment;
    }

    private static String convertToGlslType(UniformType type) {
        return switch (type) {
            case INT -> "int";
            case FLOAT -> "float";
            case MAT3 -> "mat3";
            case MAT4 -> "mat4";
            case VEC2 -> "vec2";
            case VEC2I -> "ivec2";
            case VEC3 -> "vec3";
            case VEC4 -> "vec4";
            case VEC4I -> "ivec4";
        };
    }

    private static int packedLayout(int size, int alignment) {
        return size << 5 | alignment;
    }

    private static int getSizeAndAlignment(UniformType type) {
        return switch (type) {
            case INT, FLOAT -> packedLayout(1, 1);
            case VEC2, VEC2I -> packedLayout(2, 2);
            case VEC3 -> packedLayout(3, 4);
            case VEC4, VEC4I -> packedLayout(4, 4);
            case MAT3 -> packedLayout(12, 4);
            case MAT4 -> packedLayout(16, 4);
        };
    }

    private static Map<String, UniformWritingHolder> createUniformSet(
            CustomUniforms customUniforms,
            IrisShaderPatch patch) {
        Map<String, UniformWritingHolder> uniforms = new LinkedHashMap<>();
        Set<String> requested = new HashSet<>(List.of(patch.getUniformList()));

        DynamicLocationalUniformHolder dynamic = new DynamicLocationalUniformHolder() {
            private void add(
                    String name,
                    UniformType type,
                    LongFunction<LongConsumer> writerFactory) {
                if (!requested.contains(name)) {
                    return;
                }
                UniformWritingHolder previous = uniforms.putIfAbsent(
                        name, new UniformWritingHolder(name, type, writerFactory));
                if (previous != null && previous.type != type) {
                    throw new ShaderLoadError(
                            "Oculus exposed Voxy uniform " + name + " with conflicting types");
                }
            }

            @Override
            public DynamicLocationalUniformHolder uniform1i(
                    String name,
                    IntSupplier value,
                    ValueUpdateNotifier notifier) {
                add(name, UniformType.INT, offset -> pointer ->
                        MemoryUtil.memPutInt(pointer + offset, value.getAsInt()));
                return this;
            }

            @Override
            public DynamicLocationalUniformHolder uniform1f(
                    String name,
                    FloatSupplier value,
                    ValueUpdateNotifier notifier) {
                add(name, UniformType.FLOAT, offset -> pointer ->
                        MemoryUtil.memPutFloat(pointer + offset, value.getAsFloat()));
                return this;
            }

            @Override
            public DynamicLocationalUniformHolder uniform1f(
                    String name,
                    IntSupplier value,
                    ValueUpdateNotifier notifier) {
                add(name, UniformType.FLOAT, offset -> pointer ->
                        MemoryUtil.memPutFloat(pointer + offset, value.getAsInt()));
                return this;
            }

            @Override
            public DynamicLocationalUniformHolder uniform1f(
                    String name,
                    DoubleSupplier value,
                    ValueUpdateNotifier notifier) {
                add(name, UniformType.FLOAT, offset -> pointer ->
                        MemoryUtil.memPutFloat(pointer + offset, (float) value.getAsDouble()));
                return this;
            }

            @Override
            public DynamicLocationalUniformHolder uniform2f(
                    String name,
                    Supplier<Vector2f> value,
                    ValueUpdateNotifier notifier) {
                add(name, UniformType.VEC2, offset -> pointer -> {
                    Vector2f vector = value.get();
                    MemoryUtil.memPutFloat(pointer + offset, vector.x);
                    MemoryUtil.memPutFloat(pointer + offset + 4, vector.y);
                });
                return this;
            }

            @Override
            public DynamicLocationalUniformHolder uniform2i(
                    String name,
                    Supplier<Vector2i> value,
                    ValueUpdateNotifier notifier) {
                add(name, UniformType.VEC2I, offset -> pointer -> {
                    Vector2i vector = value.get();
                    MemoryUtil.memPutInt(pointer + offset, vector.x);
                    MemoryUtil.memPutInt(pointer + offset + 4, vector.y);
                });
                return this;
            }

            @Override
            public DynamicLocationalUniformHolder uniform3f(
                    String name,
                    Supplier<Vector3f> value,
                    ValueUpdateNotifier notifier) {
                add(name, UniformType.VEC3, offset -> pointer -> {
                    Vector3f vector = value.get();
                    putVector3(pointer + offset, vector.x, vector.y, vector.z);
                });
                return this;
            }

            @Override
            public DynamicLocationalUniformHolder uniform4f(
                    String name,
                    Supplier<Vector4f> value,
                    ValueUpdateNotifier notifier) {
                add(name, UniformType.VEC4, offset -> pointer -> {
                    Vector4f vector = value.get();
                    putVector4(pointer + offset, vector.x, vector.y, vector.z, vector.w);
                });
                return this;
            }

            @Override
            public DynamicLocationalUniformHolder uniform4fArray(
                    String name,
                    Supplier<float[]> value,
                    ValueUpdateNotifier notifier) {
                add(name, UniformType.VEC4, offset -> pointer -> {
                    float[] vector = value.get();
                    putVector4(pointer + offset, vector[0], vector[1], vector[2], vector[3]);
                });
                return this;
            }

            @Override
            public DynamicLocationalUniformHolder uniform4i(
                    String name,
                    Supplier<Vector4i> value,
                    ValueUpdateNotifier notifier) {
                add(name, UniformType.VEC4I, offset -> pointer -> {
                    Vector4i vector = value.get();
                    MemoryUtil.memPutInt(pointer + offset, vector.x);
                    MemoryUtil.memPutInt(pointer + offset + 4, vector.y);
                    MemoryUtil.memPutInt(pointer + offset + 8, vector.z);
                    MemoryUtil.memPutInt(pointer + offset + 12, vector.w);
                });
                return this;
            }

            @Override
            public DynamicLocationalUniformHolder uniformMatrix(
                    String name,
                    Supplier<com.mojang.math.Matrix4f> value,
                    ValueUpdateNotifier notifier) {
                add(name, UniformType.MAT4, offset -> pointer ->
                        writeMojangMatrix(pointer + offset, value.get()));
                return this;
            }

            @Override
            public DynamicLocationalUniformHolder addDynamicUniform(
                    Uniform uniform,
                    ValueUpdateNotifier notifier) {
                return this;
            }

            @Override
            public LocationalUniformHolder addUniform(
                    UniformUpdateFrequency updateFrequency,
                    Uniform uniform) {
                return this;
            }

            @Override
            public OptionalInt location(String name, UniformType type) {
                return requested.contains(name)
                        ? OptionalInt.of(indexOf(patch.getUniformList(), name))
                        : OptionalInt.empty();
            }

            @Override
            public UniformHolder externallyManagedUniform(String name, UniformType type) {
                return this;
            }
        };

        CommonUniforms.addDynamicUniforms(dynamic, FogMode.PER_FRAGMENT);
        customUniforms.assignTo(dynamic);
        customUniforms.mapholderToPass(dynamic, patch);

        Map<Object, it.unimi.dsi.fastutil.objects.Object2IntMap<CachedUniform>> locationMap =
                ((CustomUniformsAccessor) customUniforms).voxy$getLocationMap();
        var cachedUniforms = locationMap.get(patch);
        if (cachedUniforms != null) {
            FunctionReturn cachedReturn = new FunctionReturn();
            cachedUniforms.object2IntEntrySet().forEach(entry -> {
                CachedUniform cached = entry.getKey();
                UniformType type = Type.convert(cached.getType());
                uniforms.put(cached.getName(), new UniformWritingHolder(
                        cached.getName(),
                        type,
                        offset -> createWriter(offset, cachedReturn, cached)));
            });
        }

        Set<String> missing = requested.stream()
                .filter(name -> !uniforms.containsKey(name))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!missing.isEmpty()) {
            Logger.error("The Oculus 1.19.2 pipeline did not expose: " + String.join(", ", missing));
        }
        return uniforms;
    }

    private static int indexOf(String[] names, String name) {
        for (int index = 0; index < names.length; index++) {
            if (names[index].equals(name)) {
                return index;
            }
        }
        return -1;
    }

    private static LongConsumer createWriter(
            long offset,
            FunctionReturn result,
            CachedUniform uniform) {
        if (uniform instanceof BooleanCachedUniform booleanUniform) {
            return pointer -> {
                booleanUniform.writeTo(result);
                MemoryUtil.memPutInt(pointer + offset, result.booleanReturn ? 1 : 0);
            };
        }
        if (uniform instanceof FloatCachedUniform floatUniform) {
            return pointer -> {
                floatUniform.writeTo(result);
                MemoryUtil.memPutFloat(pointer + offset, result.floatReturn);
            };
        }
        if (uniform instanceof IntCachedUniform intUniform) {
            return pointer -> {
                intUniform.writeTo(result);
                MemoryUtil.memPutInt(pointer + offset, result.intReturn);
            };
        }
        if (uniform instanceof Float2VectorCachedUniform vectorUniform) {
            return pointer -> {
                vectorUniform.writeTo(result);
                Vector2f vector = (Vector2f) result.objectReturn;
                MemoryUtil.memPutFloat(pointer + offset, vector.x);
                MemoryUtil.memPutFloat(pointer + offset + 4, vector.y);
            };
        }
        if (uniform instanceof Float3VectorCachedUniform vectorUniform) {
            return pointer -> {
                vectorUniform.writeTo(result);
                Vector3f vector = (Vector3f) result.objectReturn;
                putVector3(pointer + offset, vector.x, vector.y, vector.z);
            };
        }
        if (uniform instanceof Float3VanillaVectorCachedUniform vectorUniform) {
            return pointer -> {
                vectorUniform.writeTo(result);
                com.mojang.math.Vector3f vector = (com.mojang.math.Vector3f) result.objectReturn;
                putVector3(pointer + offset, vector.x(), vector.y(), vector.z());
            };
        }
        if (uniform instanceof Float4VectorCachedUniform vectorUniform) {
            return pointer -> {
                vectorUniform.writeTo(result);
                Vector4f vector = (Vector4f) result.objectReturn;
                putVector4(pointer + offset, vector.x, vector.y, vector.z, vector.w);
            };
        }
        if (uniform instanceof Int2VectorCachedUniform vectorUniform) {
            return pointer -> {
                vectorUniform.writeTo(result);
                Vector2i vector = (Vector2i) result.objectReturn;
                MemoryUtil.memPutInt(pointer + offset, vector.x);
                MemoryUtil.memPutInt(pointer + offset + 4, vector.y);
            };
        }
        if (uniform instanceof Float4MatrixCachedUniform matrixUniform) {
            return pointer -> {
                matrixUniform.writeTo(result);
                writeJomlMatrix(pointer + offset, (Matrix4f) result.objectReturn);
            };
        }
        throw new ShaderLoadError("Unsupported Oculus cached uniform " + uniform.getClass().getName());
    }

    private static void putVector3(long pointer, float x, float y, float z) {
        MemoryUtil.memPutFloat(pointer, x);
        MemoryUtil.memPutFloat(pointer + 4, y);
        MemoryUtil.memPutFloat(pointer + 8, z);
    }

    private static void putVector4(long pointer, float x, float y, float z, float w) {
        MemoryUtil.memPutFloat(pointer, x);
        MemoryUtil.memPutFloat(pointer + 4, y);
        MemoryUtil.memPutFloat(pointer + 8, z);
        MemoryUtil.memPutFloat(pointer + 12, w);
    }

    private static void writeJomlMatrix(long pointer, Matrix4f matrix) {
        MemoryUtil.memPutFloat(pointer, matrix.m00());
        MemoryUtil.memPutFloat(pointer + 4, matrix.m01());
        MemoryUtil.memPutFloat(pointer + 8, matrix.m02());
        MemoryUtil.memPutFloat(pointer + 12, matrix.m03());
        MemoryUtil.memPutFloat(pointer + 16, matrix.m10());
        MemoryUtil.memPutFloat(pointer + 20, matrix.m11());
        MemoryUtil.memPutFloat(pointer + 24, matrix.m12());
        MemoryUtil.memPutFloat(pointer + 28, matrix.m13());
        MemoryUtil.memPutFloat(pointer + 32, matrix.m20());
        MemoryUtil.memPutFloat(pointer + 36, matrix.m21());
        MemoryUtil.memPutFloat(pointer + 40, matrix.m22());
        MemoryUtil.memPutFloat(pointer + 44, matrix.m23());
        MemoryUtil.memPutFloat(pointer + 48, matrix.m30());
        MemoryUtil.memPutFloat(pointer + 52, matrix.m31());
        MemoryUtil.memPutFloat(pointer + 56, matrix.m32());
        MemoryUtil.memPutFloat(pointer + 60, matrix.m33());
    }

    private static void writeMojangMatrix(long pointer, com.mojang.math.Matrix4f matrix) {
        float[] values = new float[16];
        java.nio.FloatBuffer buffer = java.nio.FloatBuffer.wrap(values);
        matrix.store(buffer);
        for (int index = 0; index < values.length; index++) {
            MemoryUtil.memPutFloat(pointer + index * 4L, values[index]);
        }
    }

    private record TextureWithSampler(String name, IntSupplier texture, int sampler) {
    }

    public record ImageSet(String layout, IntConsumer bindingFunction) {
    }

    private static ImageSet createImageSet(
            NewWorldRenderingPipeline pipeline,
            ImmutableSet<Integer> flipped,
            IrisShaderPatch patch) {
        var samplerTypes = patch.getSamplerSet();
        if (samplerTypes == null || samplerTypes.isEmpty()) {
            return null;
        }

        Set<String> requestedNames = new LinkedHashSet<>(samplerTypes.keySet());
        Map<String, TextureWithSampler> found = new LinkedHashMap<>();
        Map<String, IntSupplier> externalTextures = new HashMap<>();
        externalTextures.put("lightmap", LightMapHelper::getLightmapTextureId);

        SamplerHolder samplerHolder = new SamplerHolder() {
            @Override
            public boolean hasSampler(String name) {
                return requestedNames.contains(name);
            }

            private String requested(String... names) {
                for (String name : names) {
                    if (requestedNames.contains(name)) {
                        return name;
                    }
                }
                return null;
            }

            @Override
            public boolean addDefaultSampler(
                    TextureType type,
                    IntSupplier texture,
                    ValueUpdateNotifier notifier,
                    GlSampler sampler,
                    String... names) {
                return addDynamicSampler(type, texture, notifier, sampler, names);
            }

            @Override
            public boolean addDynamicSampler(
                    TextureType type,
                    IntSupplier texture,
                    GlSampler sampler,
                    String... names) {
                return addDynamicSampler(type, texture, null, sampler, names);
            }

            @Override
            public boolean addDynamicSampler(
                    TextureType type,
                    IntSupplier texture,
                    ValueUpdateNotifier notifier,
                    GlSampler sampler,
                    String... names) {
                String name = requested(names);
                if (name == null) {
                    return false;
                }
                found.putIfAbsent(name, new TextureWithSampler(
                        name, texture, sampler == null ? 0 : sampler.getId()));
                return true;
            }

            @Override
            public void addExternalSampler(int textureUnit, String... names) {
                String name = requested(names);
                if (name == null) {
                    return;
                }
                IntSupplier supplier = externalTextures.getOrDefault(name, () -> textureUnit);
                found.putIfAbsent(name, new TextureWithSampler(name, supplier, 0));
            }
        };

        ImageHolder imageHolder = new ImageHolder() {
            @Override
            public boolean hasImage(String name) {
                return false;
            }

            @Override
            public void addTextureImage(
                    IntSupplier textureId,
                    InternalTextureFormat format,
                    String name) {
                // Voxy patch protocol version 1 exposes samplers and SSBOs, not images.
            }
        };

        pipeline.addGbufferOrShadowSamplers(
                samplerHolder,
                imageHolder,
                () -> flipped,
                false,
                new InputAvailability(true, true, false));

        Set<String> missing = requestedNames.stream()
                .filter(name -> !found.containsKey(name))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!missing.isEmpty()) {
            throw new ShaderLoadError(
                    "Oculus 1.19.2 could not supply Voxy samplers: "
                            + String.join(", ", missing));
        }

        List<TextureWithSampler> samplers = requestedNames.stream()
                .map(found::get)
                .toList();
        StringBuilder declarations = new StringBuilder();
        for (int index = 0; index < samplers.size(); index++) {
            TextureWithSampler sampler = samplers.get(index);
            declarations.append("layout(binding=(BASE_SAMPLER_BINDING_INDEX+")
                    .append(index)
                    .append(")) uniform ")
                    .append(samplerTypes.get(sampler.name))
                    .append(' ')
                    .append(sampler.name)
                    .append(";\n");
        }

        IntConsumer binder = base -> {
            for (int index = 0; index < samplers.size(); index++) {
                TextureWithSampler sampler = samplers.get(index);
                int unit = base + index;
                glBindTextureUnit(unit, sampler.texture.getAsInt());
                glBindSampler(unit, sampler.sampler);
            }
        };
        return new ImageSet(declarations.toString(), binder);
    }

    public record SSBOSet(String layout, IntConsumer bindingFunction) {
    }

    private record SSBOBinding(int originalIndex, int bindingOffset, int bufferId) {
    }

    private static SSBOSet createSSBOLayouts(
            Int2ObjectMap<String> requested,
            ShaderStorageBufferHolder holder) {
        if (requested == null || requested.isEmpty()) {
            return null;
        }
        if (holder == null) {
            throw new ShaderLoadError("Voxy shader requests SSBOs but Oculus did not create any");
        }

        String header = requested.remove(-1);
        StringBuilder declarations = new StringBuilder(header == null ? "" : header);
        declarations.append('\n');

        ShaderStorageBuffer[] buffers =
                ((ShaderStorageBufferHolderAccessor) holder).voxy$getBuffers();
        List<SSBOBinding> bindings = new ArrayList<>();
        int bindingOffset = 0;
        for (var entry : requested.int2ObjectEntrySet()) {
            int originalIndex = entry.getIntKey();
            if (originalIndex < 0 || originalIndex >= buffers.length
                    || buffers[originalIndex] == null) {
                throw new ShaderLoadError(
                        "Voxy shader requests missing Oculus SSBO " + originalIndex);
            }
            ShaderStorageBuffer buffer = buffers[originalIndex];
            bindings.add(new SSBOBinding(originalIndex, bindingOffset, buffer.getId()));
            declarations.append("layout(binding=(BUFFER_BINDING_INDEX_BASE+")
                    .append(bindingOffset)
                    .append(")) restrict buffer IrisBufferBinding")
                    .append(bindingOffset)
                    .append(' ')
                    .append(entry.getValue())
                    .append(";\n");
            bindingOffset++;
        }

        IntConsumer binder = base -> {
            for (SSBOBinding binding : bindings) {
                glBindBufferBase(
                        GL_SHADER_STORAGE_BUFFER,
                        base + binding.bindingOffset,
                        binding.bufferId);
            }
        };
        return new SSBOSet(declarations.toString(), binder);
    }
}
