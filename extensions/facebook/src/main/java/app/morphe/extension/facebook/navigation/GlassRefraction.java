/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.navigation;

import android.graphics.Canvas;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.os.Build;

import androidx.annotation.Nullable;

import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * The bend in the see-through bars' liquid glass, on Android 13 and newer: the sharp backdrop a
 * bar's frost recorded before it was blurred, drawn through a small shader that magnifies it a
 * little in the middle and bends it more toward the edge, as glass would. It runs through the
 * shader into a render node of its own, so nothing is read back and no view is touched. Before
 * Android 13 there is no shader to run and the bars keep their plain frost. A failure turns the
 * bend off for that bar and the frost carries on.
 *
 * <p>Two shapes share the one shader: the pill's rim, the band at its edge mirrored the way the
 * edge of real glass bends what's behind it, and a bubble, the backdrop magnified inside a box, which
 * the rounded tab bar uses for the spot behind the selected tab. The top bar asks for its rim
 * with a box running past its other three edges, so only the bottom one bends, the way HushGram's
 * top bar borrows the pill's rim (#513 there).
 */
public final class GlassRefraction {
    /** How much the centre of a bubble magnifies, and how much more its rim bends the backdrop. */
    static final float CENTRE_MAGNIFY = 0.10f;
    static final float RIM_BEND = 0.14f;

    /**
     * The most the bend may pull the backdrop in toward its middle at the rim, centre and rim
     * together, so a strong bend magnifies without turning the picture inside out.
     */
    static final float MAX_PULL = 0.8f;

    /** How much the bubble's middle magnifies at [bend], the Glass bend slider's scale. */
    static float centre(float bend) {
        return Math.min(MAX_PULL, CENTRE_MAGNIFY * bend);
    }

    /** How much more the bubble's rim bends at [bend], within what {@link #centre} leaves of {@link #MAX_PULL}. */
    static float rimBend(float bend) {
        return Math.max(0f, Math.min(MAX_PULL - centre(bend), RIM_BEND * bend));
    }

    private static final String SHADER = ""
            + "uniform shader content;\n"
            + "uniform float4 box;\n"
            + "uniform float radius;\n"
            + "uniform float centre;\n"
            + "uniform float rim;\n"
            + "half4 main(float2 p) {\n"
            + "  float2 mid = (box.xy + box.zw) * 0.5;\n"
            + "  float2 hs = (box.zw - box.xy) * 0.5;\n"
            + "  float2 q = abs(p - mid) - (hs - float2(radius));\n"
            + "  float d = length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - radius;\n"
            + "  float inside = clamp(0.5 - d, 0.0, 1.0);\n"
            + "  if (inside <= 0.0) return half4(0.0);\n"
            + "  float depth = clamp(-d / max(1.0, min(hs.x, hs.y)), 0.0, 1.0);\n"
            + "  float bend = (1.0 - depth) * (1.0 - depth);\n"
            + "  float2 source = mid + (p - mid) * (1.0 - centre - rim * bend);\n"
            + "  return content.eval(source) * inside;\n"
            + "}\n";

    /**
     * The pill's rim: within {@link #RIM_BAND} of the edge the sharp backdrop is mirrored across
     * the band's inner line, as {@link #rimSource} works out, so what runs under the edge shows
     * upside down and moves the other way, as it does through the edge of real glass. Past the
     * band the bend draws nothing, so the frost underneath shows through.
     */
    private static final String RIM_SHADER = ""
            + "uniform shader content;\n"
            + "uniform float4 box;\n"
            + "uniform float radius;\n"
            + "uniform float band;\n"
            + "half4 main(float2 p) {\n"
            + "  float2 mid = (box.xy + box.zw) * 0.5;\n"
            + "  float2 hs = (box.zw - box.xy) * 0.5;\n"
            + "  float2 q = abs(p - mid) - (hs - float2(radius));\n"
            + "  float d = length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - radius;\n"
            + "  float inside = clamp(0.5 - d, 0.0, 1.0);\n"
            + "  if (inside <= 0.0) return half4(0.0);\n"
            + "  float2 side = sign(p - mid);\n"
            + "  float2 normal = q.x > 0.0 && q.y > 0.0 ? normalize(q) * side\n"
            + "      : (q.x > q.y ? float2(side.x, 0.0) : float2(0.0, side.y));\n"
            + "  float depth = max(0.0, -d);\n"
            + "  float mask = 1.0 - smoothstep(0.0, band, depth);\n"
            + "  float2 source = p - normal * (2.0 * (band - depth));\n"
            + "  return content.eval(source) * (inside * mask);\n"
            + "}\n";

    /** How deep the rim's mirrored band reaches into the glass, as a share of half its shorter side. */
    static final float RIM_BAND = 0.42f;

    /**
     * How far in from the edge the rim samples the backdrop for a point [depth] in from it, in a
     * band [band] deep: past the band, the point itself, and within it, the mirror image across
     * the band's inner line, so the edge shows what's 2 x [band] in and the order runs backwards.
     * The same sum as {@link #RIM_SHADER}.
     */
    static float rimSource(float depth, float band) {
        return depth < band ? 2f * band - depth : depth;
    }

    /** The deepest the rim's band may reach, as a share of half the glass's shorter side. */
    static final float MAX_RIM_BAND = 0.95f;

    /** The lowest Glass bend a person can pick, in percent: no bend at all. */
    public static final int MIN_BEND = 0;
    /** The highest, twice the standard bend. */
    public static final int MAX_BEND = 200;
    /** What's picked with no Glass bend saved, in percent. */
    public static final int DEFAULT_BEND = 100;

    /** [percent] as the multiplier the bend is scaled by, kept inside the row's range. */
    static float scale(int percent) {
        return Math.max(MIN_BEND, Math.min(MAX_BEND, percent)) / 100f;
    }

    /** The rim's band at [percent] of its standard depth, as a share of half the glass's shorter side. */
    static float bandShare(int percent) {
        return Math.min(MAX_RIM_BAND, RIM_BAND * scale(percent));
    }

    /**
     * The Glass bend [saved] picks, in percent, held to {@link #MIN_BEND} to {@link #MAX_BEND}, or
     * {@link #DEFAULT_BEND} for blank or not a number. Its own parse, so the bend doesn't load the
     * bars' drawables for a number.
     */
    public static int bendPercent(@Nullable String saved) {
        if (saved == null) return DEFAULT_BEND;
        String text = saved.trim();
        if (text.isEmpty() || text.length() > 4) return DEFAULT_BEND;
        try {
            return Math.max(MIN_BEND, Math.min(MAX_BEND, Integer.parseInt(text)));
        } catch (NumberFormatException notANumber) {
            return DEFAULT_BEND;
        }
    }

    /** Whether the bars bend light now: the switch on, the settings ready. */
    static boolean on() {
        return Utils.settingsReady() && Settings.LIQUID_GLASS_BARS.get();
    }

    /** Whether this Android can run the bend at all. */
    static boolean supported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
    }

    /** Whether this is the rim rather than a bubble. */
    private final boolean rim;
    @Nullable private RenderNode node;
    @Nullable private RuntimeShader shader;
    private boolean broken;
    /** How many times the bend was asked to paint, and how many it painted. Read by tests. */
    int asked;
    int painted;

    /** The pill's and the top bar's rim. */
    public GlassRefraction() {
        this(true);
    }

    /** The rim, or with [rim] false a bubble. */
    public GlassRefraction(boolean rim) {
        this.rim = rim;
    }

    /** The bend's strength in percent: the Glass bend row's, or the standard for none saved. */
    int percent() {
        return bendPercent(Settings.GLASS_BEND.get());
    }

    /**
     * Draws the bend over [box] ([radius] corners) into [canvas], sampling [backdrop], the frost's
     * blurred node drawn at [nodeLeft], [nodeTop] and [width] by [height] in the same coordinates.
     * The box is in the layer's own coordinates, where the backdrop's top left is (0, 0). Says
     * whether the bend drew.
     */
    public boolean paint(Canvas canvas, @Nullable RenderNode backdrop, float nodeLeft, float nodeTop,
            int width, int height, RectF box, float radius) {
        return paint(canvas, backdrop, nodeLeft, nodeTop, width, height, box, radius,
                Math.min(box.width(), box.height()));
    }

    /**
     * {@link #paint(Canvas, RenderNode, float, float, int, int, RectF, float)} with the rim's band
     * a share of half of [across] rather than of the box's shorter side, for a box that runs on
     * past the glass on its other edges.
     */
    public boolean paint(Canvas canvas, @Nullable RenderNode backdrop, float nodeLeft, float nodeTop,
            int width, int height, RectF box, float radius, float across) {
        return paint(canvas, backdrop, nodeLeft, nodeTop, width, height, box, radius, across, 1f);
    }

    /**
     * {@link #paint(Canvas, RenderNode, float, float, int, int, RectF, float, float)} at [alpha],
     * under 1 to lay the bend over the frost rather than replace it.
     */
    public boolean paint(Canvas canvas, @Nullable RenderNode backdrop, float nodeLeft, float nodeTop,
            int width, int height, RectF box, float radius, float across, float alpha) {
        asked++;
        if (broken || !supported() || backdrop == null || width <= 0 || height <= 0
                || !canvas.isHardwareAccelerated()) {
            return false;
        }
        try {
            RuntimeShader lens = shader;
            if (lens == null) {
                lens = new RuntimeShader(rim ? RIM_SHADER : SHADER);
                shader = lens;
            }
            RenderNode layer = node;
            if (layer == null) {
                layer = new RenderNode(rim ? "hushfacebook-glass-rim" : "hushfacebook-glass-lens");
                node = layer;
            }
            lens.setFloatUniform("box", box.left, box.top, box.right, box.bottom);
            lens.setFloatUniform("radius", Math.max(0f, radius));
            if (rim) {
                lens.setFloatUniform("band", bandShare(percent()) * across / 2f);
            } else {
                float bend = scale(percent());
                lens.setFloatUniform("centre", centre(bend));
                lens.setFloatUniform("rim", rimBend(bend));
            }
            layer.setPosition(Math.round(nodeLeft), Math.round(nodeTop),
                    Math.round(nodeLeft) + width, Math.round(nodeTop) + height);
            layer.setAlpha(Math.round(alpha * 255));
            layer.setRenderEffect(RenderEffect.createRuntimeShaderEffect(lens, "content"));
            Canvas recording = layer.beginRecording(width, height);
            try {
                recording.translate(-nodeLeft, -nodeTop);
                recording.drawRenderNode(backdrop);
            } finally {
                layer.endRecording();
            }
            canvas.drawRenderNode(layer);
            painted++;
            if (painted == 1) {
                Logger.printDebug(() -> "Liquid glass: bend painted ("
                        + (rim ? "rim" : "bubble") + ")");
            }
            return true;
        } catch (Throwable failure) {
            broken = true;
            Logger.printDebug(() -> "Liquid glass: the bend failed, the bars keep their frost");
            return false;
        }
    }

    /** Lets go of the bend's render node. */
    public void discard() {
        RenderNode layer = node;
        node = null;
        shader = null;
        if (layer != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) layer.discardDisplayList();
    }
}
