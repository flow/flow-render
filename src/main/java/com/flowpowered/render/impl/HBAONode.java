/*
 * This file is part of Flow Render, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2014 Spout LLC <http://www.spout.org/>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.flowpowered.render.impl;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;

import com.flowpowered.math.TrigMath;
import com.flowpowered.math.vector.Vector2f;
import com.flowpowered.math.vector.Vector2i;
import com.flowpowered.math.vector.Vector3f;
import com.flowpowered.render.GraphNode;
import com.flowpowered.render.RenderGraph;
import com.flowpowered.render.RenderUtil;

import org.spout.renderer.api.Camera;
import org.spout.renderer.api.Material;
import org.spout.renderer.api.Pipeline;
import org.spout.renderer.api.Pipeline.PipelineBuilder;
import org.spout.renderer.api.data.Uniform.FloatUniform;
import org.spout.renderer.api.data.Uniform.Vector2Uniform;
import org.spout.renderer.api.data.UniformHolder;
import org.spout.renderer.api.gl.Context;
import org.spout.renderer.api.gl.FrameBuffer;
import org.spout.renderer.api.gl.FrameBuffer.AttachmentPoint;
import org.spout.renderer.api.gl.Texture;
import org.spout.renderer.api.gl.Texture.FilterMode;
import org.spout.renderer.api.gl.Texture.InternalFormat;
import org.spout.renderer.api.model.Model;
import org.spout.renderer.api.util.CausticUtil;
import org.spout.renderer.api.util.Rectangle;

public class HBAONode extends GraphNode {
    private final Texture noiseTexture;
    private final FrameBuffer frameBuffer;
    private final Texture occlusionsOutput;
    private final Material material;
    private final Pipeline pipeline;
    private final Rectangle outputSize = new Rectangle();
    private final Vector2Uniform projectionUniform = new Vector2Uniform("projection", Vector2f.ZERO);
    private final FloatUniform aspectRatioUniform = new FloatUniform("aspectRatio", 1);
    private final FloatUniform tanHalfFOVUniform = new FloatUniform("tanHalfFOV", 1);

    public HBAONode(RenderGraph graph, String name) {
        super(graph, name);
        final Context context = graph.getContext();
        // Create the noise texture
        noiseTexture = context.newTexture();
        noiseTexture.create();
        noiseTexture.setFormat(InternalFormat.RGB8);
        noiseTexture.setFilters(FilterMode.NEAREST, FilterMode.NEAREST);
        // Create the occlusions texture
        occlusionsOutput = context.newTexture();
        occlusionsOutput.create();
        occlusionsOutput.setFormat(InternalFormat.R8);
        occlusionsOutput.setFilters(FilterMode.LINEAR, FilterMode.LINEAR);
        // Create the frame buffer
        frameBuffer = context.newFrameBuffer();
        frameBuffer.create();
        frameBuffer.attach(AttachmentPoint.COLOR0, occlusionsOutput);
        // Create the material
        material = new Material(graph.getProgram("hbao"));
        material.addTexture(1, noiseTexture);
        final UniformHolder uniforms = material.getUniforms();
        uniforms.add(projectionUniform);
        uniforms.add(tanHalfFOVUniform);
        uniforms.add(aspectRatioUniform);
        // Create the screen model
        final Model model = new Model(graph.getScreen(), material);
        // Create the pipeline
        pipeline = new PipelineBuilder().useViewPort(outputSize).bindFrameBuffer(frameBuffer).renderModels(Arrays.asList(model)).unbindFrameBuffer(frameBuffer).build();
    }

    @Override
    public void update() {
        updateCamera(this.<Camera>getAttribute("camera"));
        updateNoiseSize(getAttribute("noiseSize", 4));
        updateOutputSize(this.<Vector2i>getAttribute("outputSize"));
    }

    private void updateCamera(Camera camera) {
        // Update the field of view
        tanHalfFOVUniform.set(TrigMath.tan(RenderUtil.getFieldOfView(camera) / 2));
        // Update the planes
        projectionUniform.set(RenderUtil.computeProjection(RenderUtil.getPlanes(camera)));
    }

    private void updateNoiseSize(int noiseSize) {
        if (noiseSize == noiseTexture.getWidth()) {
            return;
        }
        // Generate the noise texture data
        final Random random = new Random();
        final int noiseTextureSize = noiseSize * noiseSize;
        final ByteBuffer noiseTextureBuffer = CausticUtil.createByteBuffer(noiseTextureSize * 3);
        for (int i = 0; i < noiseTextureSize; i++) {
            // Random unit vectors around the z axis
            Vector3f noise = new Vector3f(random.nextFloat() * 2 - 1, random.nextFloat() * 2 - 1, 0).normalize();
            // Encode to unsigned byte, and place in buffer
            noise = noise.mul(128).add(128, 128, 128);
            noiseTextureBuffer.put((byte) (noise.getFloorX() & 0xff));
            noiseTextureBuffer.put((byte) (noise.getFloorY() & 0xff));
            noiseTextureBuffer.put((byte) (noise.getFloorZ() & 0xff));
        }
        // Update the uniform
        //noiseScaleUniform.set(outputSize.getSize().toFloat().div(noiseSize));
        // Update the texture
        noiseTextureBuffer.flip();
        noiseTexture.setImageData(noiseTextureBuffer, noiseSize, noiseSize);
    }

    private void updateOutputSize(Vector2i size) {
        if (size.getX() == outputSize.getWidth() && size.getY() == outputSize.getHeight()) {
            return;
        }
        outputSize.setSize(size);
        occlusionsOutput.setImageData(null, size.getX(), size.getY());
        //noiseScaleUniform.set(size.toFloat().div(noiseTexture.getWidth()));
    }

    @Override
    protected void render() {
        final Texture depths = material.getTexture(1);
        aspectRatioUniform.set((float) depths.getWidth() / depths.getHeight());
        pipeline.run(graph.getContext());
    }

    @Override
    protected void destroy() {
        noiseTexture.destroy();
        frameBuffer.destroy();
        occlusionsOutput.destroy();
    }

    @Input("depths")
    public void setDepthsInput(Texture texture) {
        texture.checkCreated();
        material.addTexture(0, texture);
    }

    @Output("occlusions")
    public Texture getOcclusionsOutput() {
        return occlusionsOutput;
    }
}
