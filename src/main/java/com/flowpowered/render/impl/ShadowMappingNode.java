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
import java.util.Collection;
import java.util.Collections;
import java.util.Random;

import com.flowpowered.commons.ViewFrustum;
import com.flowpowered.math.TrigMath;
import com.flowpowered.math.imaginary.Quaternionf;
import com.flowpowered.math.matrix.Matrix3f;
import com.flowpowered.math.matrix.Matrix4f;
import com.flowpowered.math.vector.Vector2f;
import com.flowpowered.math.vector.Vector2i;
import com.flowpowered.math.vector.Vector3f;
import com.flowpowered.render.GraphNode;
import com.flowpowered.render.RenderGraph;
import com.flowpowered.render.RenderUtil;

import com.flowpowered.caustic.api.Action;
import com.flowpowered.caustic.api.Camera;
import com.flowpowered.caustic.api.Material;
import com.flowpowered.caustic.api.Pipeline;
import com.flowpowered.caustic.api.Pipeline.PipelineBuilder;
import com.flowpowered.caustic.api.data.Uniform.FloatUniform;
import com.flowpowered.caustic.api.data.Uniform.IntUniform;
import com.flowpowered.caustic.api.data.Uniform.Matrix4Uniform;
import com.flowpowered.caustic.api.data.Uniform.Vector2ArrayUniform;
import com.flowpowered.caustic.api.data.Uniform.Vector2Uniform;
import com.flowpowered.caustic.api.data.Uniform.Vector3Uniform;
import com.flowpowered.caustic.api.data.UniformHolder;
import com.flowpowered.caustic.api.gl.Context;
import com.flowpowered.caustic.api.gl.FrameBuffer;
import com.flowpowered.caustic.api.gl.FrameBuffer.AttachmentPoint;
import com.flowpowered.caustic.api.gl.Program;
import com.flowpowered.caustic.api.gl.Texture;
import com.flowpowered.caustic.api.gl.Texture.CompareMode;
import com.flowpowered.caustic.api.gl.Texture.FilterMode;
import com.flowpowered.caustic.api.gl.Texture.InternalFormat;
import com.flowpowered.caustic.api.gl.Texture.WrapMode;
import com.flowpowered.caustic.api.model.Model;
import com.flowpowered.caustic.api.util.CausticUtil;
import com.flowpowered.caustic.api.util.Rectangle;

public class ShadowMappingNode extends GraphNode {
    protected final Material material;
    protected final Texture lightDepthsTexture;
    protected final Texture noiseTexture;
    protected final FrameBuffer depthFrameBuffer;
    protected final FrameBuffer frameBuffer;
    private final Texture shadowsOutput;
    private final Matrix4Uniform inverseViewMatrixUniform = new Matrix4Uniform("inverseViewMatrix", new Matrix4f());
    protected final Matrix4Uniform lightViewMatrixUniform = new Matrix4Uniform("lightViewMatrix", new Matrix4f());
    protected final Matrix4Uniform lightProjectionMatrixUniform = new Matrix4Uniform("lightProjectionMatrix", new Matrix4f());
    protected final Camera camera = Camera.createOrthographic(50, -50, 50, -50, -50, 50);
    protected final Rectangle shadowMapSize = new Rectangle(1, 1);
    protected final Rectangle outputSize = new Rectangle();
    protected final RenderShadowModelsAction renderModelsAction = new RenderShadowModelsAction(null);
    protected Pipeline pipeline;
    private final Vector2Uniform projectionUniform = new Vector2Uniform("projection", Vector2f.ZERO);
    private final Matrix4Uniform viewMatrixUniform = new Matrix4Uniform("viewMatrix", Matrix4f.IDENTITY);
    private final FloatUniform aspectRatioUniform = new FloatUniform("aspectRatio", 1);
    private final FloatUniform tanHalfFOVUniform = new FloatUniform("tanHalfFOV", 1);
    protected final Vector3Uniform lightDirectionUniform = new Vector3Uniform("lightDirection", LightingNode.DEFAULT_LIGHT_DIRECTION);
    private final IntUniform kernelSizeUniform = new IntUniform("kernelSize", 0);
    private final Vector2ArrayUniform kernelUniform = new Vector2ArrayUniform("kernel", new Vector2f[]{});
    private final Vector2Uniform noiseScaleUniform = new Vector2Uniform("noiseScale", Vector2f.ONE);
    private final FloatUniform biasUniform = new FloatUniform("bias", 0.005f);
    private final FloatUniform radiusUniform = new FloatUniform("radius", 0.0004f);
    protected final ViewFrustum frustum = new ViewFrustum();

    public ShadowMappingNode(RenderGraph graph, String name) {
        // Initialize a normal shadow mapping node
        this(graph, name, "shadow");
        // Create the screen model
        final Model model = new Model(graph.getScreen(), material);
        // Create the pipeline
        pipeline = new PipelineBuilder()
                .useViewPort(shadowMapSize).useCamera(camera).bindFrameBuffer(depthFrameBuffer).clearBuffer().doAction(renderModelsAction)
                .useViewPort(outputSize).bindFrameBuffer(frameBuffer).renderModels(Arrays.asList(model))
                .unbindFrameBuffer(frameBuffer).build();
    }

    protected ShadowMappingNode(RenderGraph graph, String name, String program) {
        // Initialize the base node
        super(graph, name);
        final Context context = graph.getContext();
        // Create the depth texture
        lightDepthsTexture = context.newTexture();
        lightDepthsTexture.create();
        lightDepthsTexture.setFormat(InternalFormat.DEPTH_COMPONENT16);
        lightDepthsTexture.setFilters(FilterMode.LINEAR, FilterMode.LINEAR);
        lightDepthsTexture.setWraps(WrapMode.CLAMP_TO_BORDER, WrapMode.CLAMP_TO_BORDER);
        lightDepthsTexture.setCompareMode(CompareMode.LESS);
        // Create the noise texture
        noiseTexture = context.newTexture();
        noiseTexture.create();
        noiseTexture.setFormat(InternalFormat.RG8);
        noiseTexture.setFilters(FilterMode.NEAREST, FilterMode.NEAREST);
        // Create the shadows texture
        shadowsOutput = context.newTexture();
        shadowsOutput.create();
        shadowsOutput.setFormat(InternalFormat.R8);
        shadowsOutput.setFilters(FilterMode.LINEAR, FilterMode.LINEAR);
        // Create the depth frame buffer
        depthFrameBuffer = context.newFrameBuffer();
        depthFrameBuffer.create();
        depthFrameBuffer.attach(AttachmentPoint.DEPTH, lightDepthsTexture);
        // Create the frame buffer
        frameBuffer = context.newFrameBuffer();
        frameBuffer.create();
        frameBuffer.attach(AttachmentPoint.COLOR0, shadowsOutput);
        // Create the material
        material = new Material(graph.getProgram(program));
        material.addTexture(2, lightDepthsTexture);
        material.addTexture(3, noiseTexture);
        final UniformHolder uniforms = material.getUniforms();
        uniforms.add(projectionUniform);
        uniforms.add(viewMatrixUniform);
        uniforms.add(tanHalfFOVUniform);
        uniforms.add(aspectRatioUniform);
        uniforms.add(lightDirectionUniform);
        uniforms.add(inverseViewMatrixUniform);
        uniforms.add(lightViewMatrixUniform);
        uniforms.add(lightProjectionMatrixUniform);
        uniforms.add(kernelSizeUniform);
        uniforms.add(kernelUniform);
        uniforms.add(noiseScaleUniform);
        uniforms.add(biasUniform);
        uniforms.add(radiusUniform);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void update() {
        updateCamera(this.<Camera>getAttribute("camera"));
        updateShadowMapSize(getAttribute("shadowMapSize", new Vector2i(1024, 1024)));
        updateKernelSize(getAttribute("kernelSize", 8));
        updateRadius(getAttribute("radius", 0.05f));
        updateBias(getAttribute("bias", 0.01f));
        updateNoiseSize(getAttribute("noiseSize", 2));
        updateOutputSize(this.<Vector2i>getAttribute("outputSize"));
        updateModels(getAttribute("models", (Collection<Model>) Collections.EMPTY_LIST));
    }

    private void updateCamera(Camera camera) {
        // Update the field of view
        tanHalfFOVUniform.set(TrigMath.tan(RenderUtil.getFieldOfView(camera) / 2));
        // Update the planes
        projectionUniform.set(RenderUtil.computeProjection(RenderUtil.getPlanes(camera)));
    }

    protected void updateShadowMapSize(Vector2i size) {
        if (size.getX() == shadowMapSize.getWidth() && size.getY() == shadowMapSize.getHeight()) {
            return;
        }
        lightDepthsTexture.setImageData(null, size.getX(), size.getY());
        shadowMapSize.setSize(size);
    }

    private void updateKernelSize(int kernelSize) {
        if (kernelSize == kernelSizeUniform.get()) {
            return;
        }
        // Generate the kernel
        final Vector2f[] kernel = new Vector2f[kernelSize];
        final Random random = new Random();
        for (int i = 0; i < kernelSize; i++) {
            // Create a set of random unit vectors
            kernel[i] = new Vector2f(random.nextFloat() * 2 - 1, random.nextFloat() * 2 - 1).normalize();
        }
        // Update the uniforms
        kernelSizeUniform.set(kernelSize);
        kernelUniform.set(kernel);
    }

    private void updateRadius(float radius) {
        radiusUniform.set(radius);
    }

    private void updateBias(float bias) {
        biasUniform.set(bias);
    }

    private void updateNoiseSize(int noiseSize) {
        if (noiseSize == noiseTexture.getWidth()) {
            return;
        }
        // Generate the noise texture data
        final Random random = new Random();
        final int noiseTextureSize = noiseSize * noiseSize;
        final ByteBuffer noiseTextureBuffer = CausticUtil.createByteBuffer(noiseTextureSize * 2);
        for (int i = 0; i < noiseTextureSize; i++) {
            // Random unit vectors around the z axis
            Vector2f noise = new Vector2f(random.nextFloat() * 2 - 1, random.nextFloat() * 2 - 1).normalize();
            // Encode to unsigned byte, and place in buffer
            noise = noise.mul(128).add(128, 128);
            noiseTextureBuffer.put((byte) (noise.getFloorX() & 0xff));
            noiseTextureBuffer.put((byte) (noise.getFloorY() & 0xff));
        }
        // Update the uniform
        noiseScaleUniform.set(outputSize.getSize().toFloat().div(noiseSize));
        // Update the texture
        noiseTextureBuffer.flip();
        noiseTexture.setImageData(noiseTextureBuffer, noiseSize, noiseSize);
    }

    private void updateOutputSize(Vector2i size) {
        if (size.getX() == outputSize.getWidth() && size.getY() == outputSize.getHeight()) {
            return;
        }
        shadowsOutput.setImageData(null, size.getX(), size.getY());
        outputSize.setSize(size);
        noiseScaleUniform.set(size.toFloat().div(noiseTexture.getWidth()));
    }

    private void updateModels(Collection<Model> models) {
        renderModelsAction.setModels(models);
    }

    @Override
    protected void render() {
        final Texture depths = material.getTexture(1);
        aspectRatioUniform.set((float) depths.getWidth() / depths.getHeight());
        final Camera camera = getAttribute("camera");
        updateLightDirection(getAttribute("lightDirection", LightingNode.DEFAULT_LIGHT_DIRECTION), camera);
        final Matrix4f viewMatrix = camera.getViewMatrix();
        viewMatrixUniform.set(viewMatrix);
        inverseViewMatrixUniform.set(viewMatrix.invert());
        pipeline.run(graph.getContext());
    }

    /**
     * Updates the light direction and camera bounds to ensure that shadows are casted inside the camera's frustum.
     *
     * @param direction The light direction
     * @param camera The camera in which to cast shadows
     */
    protected void updateLightDirection(Vector3f direction, Camera camera) {
        // Update the camera frustum
        frustum.update(camera.getProjectionMatrix(), camera.getViewMatrix());
        // Set the direction uniform
        lightDirectionUniform.set(direction);
        // Calculate the camera rotation from the direction and set
        final Quaternionf rotation = Quaternionf.fromRotationTo(Vector3f.FORWARD.negate(), direction);
        // Calculate the transformation from the camera bounds rotation to the identity rotation (its axis aligned space)
        final Matrix3f axisAlignTransform = Matrix3f.createRotation(rotation).invert();
        // Calculate the points of the box to completely include inside the camera bounds
        // Transform those points to the axis aligned space of the camera bounds
        Vector3f position = frustum.getPosition();
        final Vector3f[] vertices = frustum.getVertices();
        final Vector3f p0 = axisAlignTransform.transform(vertices[0].sub(position));
        final Vector3f p1 = axisAlignTransform.transform(vertices[1].sub(position));
        final Vector3f p2 = axisAlignTransform.transform(vertices[2].sub(position));
        final Vector3f p3 = axisAlignTransform.transform(vertices[3].sub(position));
        final Vector3f p4 = axisAlignTransform.transform(vertices[4].sub(position));
        final Vector3f p5 = axisAlignTransform.transform(vertices[5].sub(position));
        final Vector3f p6 = axisAlignTransform.transform(vertices[6].sub(position));
        final Vector3f p7 = axisAlignTransform.transform(vertices[7].sub(position));
        // Calculate the new camera bounds so that the box is fully included in those bounds
        final Vector3f low = p0.min(p1).min(p2).min(p3).min(p4).min(p5).min(p6).min(p7);
        final Vector3f high = p0.max(p1).max(p2).max(p3).max(p4).max(p5).max(p6).max(p7);
        // Calculate the size of the new camera bounds
        final Vector3f size = high.sub(low).div(2);
        final Vector3f mid = low.add(size);
        // Compute the camera position
        position = Matrix3f.createRotation(rotation).transform(mid).add(position);
        // Update the camera position
        this.camera.setPosition(position);
        // Update the camera rotation
        this.camera.setRotation(rotation);
        // Update the camera size
        this.camera.setProjection(Matrix4f.createOrthographic(size.getX(), -size.getX(), size.getY(), -size.getY(), -size.getZ(), size.getZ()));
        // Update the uniforms for the new light camera
        lightViewMatrixUniform.set(this.camera.getViewMatrix());
        lightProjectionMatrixUniform.set(this.camera.getProjectionMatrix());
    }

    @Override
    protected void destroy() {
        lightDepthsTexture.destroy();
        noiseTexture.destroy();
        depthFrameBuffer.destroy();
        frameBuffer.destroy();
        shadowsOutput.destroy();
    }

    @Input("normals")
    public void setNormalsInput(Texture texture) {
        texture.checkCreated();
        material.addTexture(0, texture);
    }

    @Input("depths")
    public void setDepthsInput(Texture texture) {
        texture.checkCreated();
        material.addTexture(1, texture);
    }

    @Output("shadows")
    public Texture getShadowsOutput() {
        return shadowsOutput;
    }

    protected class RenderShadowModelsAction extends Action {
        private final Material material;
        private Collection<Model> models;

        protected RenderShadowModelsAction(Collection<Model> models) {
            this.material = new Material(graph.getProgram("basic"));
            this.models = models;
        }

        public void setModels(Collection<Model> models) {
            this.models = models;
        }

        @Override
        public void execute(Context context) {
            final Program program = material.getProgram();
            // Bind the material
            material.bind();
            // Upload the camera matrices
            final Camera camera = context.getCamera();
            program.setUniform("projectionMatrix", camera.getProjectionMatrix());
            program.setUniform("viewMatrix", camera.getViewMatrix());
            for (Model model : models) {
                // Upload the model matrix
                program.setUniform("modelMatrix", model.getMatrix());
                // Render the model
                model.render();
            }
        }
    }
}
