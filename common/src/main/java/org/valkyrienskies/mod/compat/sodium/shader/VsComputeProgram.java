package org.valkyrienskies.mod.compat.sodium.shader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.joml.Matrix3fc;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL43;
import org.lwjgl.system.MemoryStack;

/**
 * Minimal compile/link/dispatch wrapper for one of VS's own compute shaders.
 *
 * <p>Deliberately does not go through Sodium's {@code GlProgram} / {@code ShaderLoader}: those live in
 * generation-specific packages (0.5 vs the 0.9 backport) and {@code ShaderLoader} resolves sources via
 * {@code ShaderLoader.class.getResourceAsStream}, which only sees Sodium's own jar. On Forge each mod
 * is its own ModLauncher module and that lookup misses. Sources are read through VS's class loader
 * instead, the same fix {@code compat/sodium09/SodiumCompat#loadVsShader} already applies.
 *
 * <p>{@code #include "name.glsl"} is expanded here (one directive per line, resolved against the same
 * directory) because compute shaders never see Sodium's {@code #import} preprocessor.
 */
public final class VsComputeProgram {
    /** Matches {@code local_size_x} in every compute shader; dispatch sizes are derived from it. */
    public static final int LOCAL_SIZE = 256;

    private static final String SHADER_DIR = "/assets/valkyrienskies/shaders/compute/";

    private final int program;
    private final Map<String, Integer> uniformLocations = new HashMap<>();

    private VsComputeProgram(final int program) {
        this.program = program;
    }

    public static VsComputeProgram load(final String fileName) {
        final String source = expandIncludes(readSource(fileName), 0);

        final int shader = GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) != GL20.GL_TRUE) {
            final String log = GL20.glGetShaderInfoLog(shader);
            GL20.glDeleteShader(shader);
            throw new IllegalStateException("Failed to compile compute shader " + fileName + ":\n" + log);
        }

        final int program = GL20.glCreateProgram();
        GL20.glAttachShader(program, shader);
        GL20.glLinkProgram(program);
        GL20.glDetachShader(program, shader);
        GL20.glDeleteShader(shader);
        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) != GL20.GL_TRUE) {
            final String log = GL20.glGetProgramInfoLog(program);
            GL20.glDeleteProgram(program);
            throw new IllegalStateException("Failed to link compute shader " + fileName + ":\n" + log);
        }
        return new VsComputeProgram(program);
    }

    private static String readSource(final String fileName) {
        final String resource = SHADER_DIR + fileName;
        try (InputStream in = VsComputeProgram.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Compute shader not found: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new IllegalStateException("Failed to read compute shader " + resource, e);
        }
    }

    private static String expandIncludes(final String source, final int depth) {
        if (depth > 4) {
            throw new IllegalStateException("Compute shader #include nested too deeply");
        }
        if (!source.contains("#include")) {
            return source;
        }
        final StringBuilder out = new StringBuilder(source.length() * 2);
        for (final String line : source.split("\n", -1)) {
            final String trimmed = line.trim();
            if (trimmed.startsWith("#include")) {
                final int first = trimmed.indexOf('"');
                final int last = trimmed.lastIndexOf('"');
                if (first < 0 || last <= first) {
                    throw new IllegalStateException("Malformed #include: " + trimmed);
                }
                out.append(expandIncludes(readSource(trimmed.substring(first + 1, last)), depth + 1));
                out.append('\n');
            } else {
                out.append(line).append('\n');
            }
        }
        return out.toString();
    }

    public void bind() {
        GL20.glUseProgram(program);
    }

    public static void unbind() {
        GL20.glUseProgram(0);
    }

    /**
     * Dispatch enough workgroups of {@link #LOCAL_SIZE} to cover {@code threadCount} threads. The
     * shader is responsible for discarding the tail past {@code threadCount}.
     */
    public void dispatch(final int threadCount) {
        if (threadCount <= 0) {
            return;
        }
        GL43.glDispatchCompute((threadCount + LOCAL_SIZE - 1) / LOCAL_SIZE, 1, 1);
    }

    public void set(final String name, final int value) {
        GL20.glUniform1i(location(name), value);
    }

    public void set(final String name, final float value) {
        GL20.glUniform1f(location(name), value);
    }

    public void set(final String name, final int x, final int y, final int z) {
        GL20.glUniform3i(location(name), x, y, z);
    }

    public void set(final String name, final float x, final float y, final float z) {
        GL20.glUniform3f(location(name), x, y, z);
    }

    public void setMatrix3(final String name, final Matrix3fc value) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            GL20.glUniformMatrix3fv(location(name), false, value.get(stack.mallocFloat(9)));
        }
    }

    /**
     * -1 (an inactive or optimised-out uniform) is a legal target for {@code glUniform*} and is
     * silently ignored, so callers never have to know which passes actually use which uniform.
     */
    private int location(final String name) {
        return uniformLocations.computeIfAbsent(name, n -> GL20.glGetUniformLocation(program, n));
    }

    public void delete() {
        if (program != 0) {
            GL20.glDeleteProgram(program);
        }
    }
}
