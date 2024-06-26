package de.lemaik.chunky.denoiser;

import de.lemaik.chunky.denoiser.pfm.PortableFloatMap;
import se.llbit.chunky.renderer.DefaultRenderManager;
import se.llbit.chunky.renderer.scene.CanvasConfig;
import se.llbit.chunky.renderer.scene.RayTracer;
import se.llbit.chunky.renderer.scene.Scene;
import se.llbit.log.Log;
import se.llbit.util.TaskTracker;

import java.io.*;
import java.nio.ByteOrder;

public class DenoiserPassRenderer extends MultiPassRenderer {
    protected final DenoiserSettings settings;
    protected final Denoiser denoiser;

    protected final String id;
    protected final String name;
    protected final String description;

    protected final RayTracer albedoTracer = new AlbedoTracer();
    protected final RayTracer normalTracer;

    public DenoiserPassRenderer(DenoiserSettings settings, Denoiser denoiser,
                                String id, String name, String description) {
        this.settings = settings;
        this.denoiser = denoiser;

        this.normalTracer = new NormalTracer(this.settings);

        this.id = id;
        this.name = name;
        this.description = description;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public boolean autoPostProcess() {
        return false;
    }

    @Override
    public void render(DefaultRenderManager manager) throws InterruptedException {
        Scene scene = manager.bufferedScene;
        double[] sampleBuffer = scene.getSampleBuffer();
        boolean aborted = false;

        scene.setTargetSpp(Math.max(settings.albedoSpp.get(), settings.normalSpp.get()));

        RayTracer[] tracers = new RayTracer[] {albedoTracer, normalTracer};
        float[][] buffers = new float[][] {
                settings.renderAlbedo.get() ? new float[sampleBuffer.length] : null,
                settings.renderNormal.get() ? new float[sampleBuffer.length] : null,
        };
        boolean[] tracerMask = new boolean[2];
        scene.spp = 0;

        while (scene.spp < scene.getTargetSpp()) {
            tracerMask[0] = settings.renderAlbedo.get() && scene.spp < settings.albedoSpp.get();
            tracerMask[1] = settings.renderNormal.get() && scene.spp < settings.normalSpp.get();
            renderPass(manager, manager.context.sppPerPass(), tracers, buffers, tracerMask);
            if (scene.spp < scene.getTargetSpp() && postRender.getAsBoolean()) {
                aborted = true;
                break;
            }
        }

        if (!aborted && settings.saveBeauty.get()) {
            File out = manager.context.getSceneFile(scene.name + ".beauty.pfm");
//            scene.saveFrame(out, PortableFloatMap.getPfmExportFormat(),
//                    TaskTracker.NONE, manager.context.numRenderThreads());
            scene.saveFrame(out, TaskTracker.NONE);
        }

        if (!aborted && settings.saveAlbedo.get()) {
            File out = manager.context.getSceneFile(scene.name + ".albedo.pfm");
            try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
                PortableFloatMap.writeImage(buffers[0], CanvasConfig.MIN_CANVAS_WIDTH, CanvasConfig.MIN_CANVAS_HEIGHT, ByteOrder.LITTLE_ENDIAN, os);
            } catch (IOException e) {
                Log.error("Failed to save albedo pass", e);
            }
        }

        if (!aborted && settings.saveNormal.get()) {
            File out = manager.context.getSceneFile(scene.name + ".normal.pfm");
            try (OutputStream os = new BufferedOutputStream(new FileOutputStream(out))) {
                PortableFloatMap.writeImage(buffers[1], CanvasConfig.MIN_CANVAS_WIDTH, CanvasConfig.MIN_CANVAS_HEIGHT, ByteOrder.LITTLE_ENDIAN, os);
            } catch (IOException e) {
                Log.error("Failed to save normal pass", e);
            }
        }

        if (!aborted) {
            if (denoiser instanceof OidnBinaryDenoiser)
                ((OidnBinaryDenoiser) denoiser).loadPath();

            try {
                denoiser.denoiseDouble(CanvasConfig.MIN_CANVAS_WIDTH, CanvasConfig.MIN_CANVAS_HEIGHT, sampleBuffer,
                        buffers[0], buffers[1], sampleBuffer);

                scene.spp = scene.getTargetSpp();
                postRender.getAsBoolean();
            } catch (Denoiser.DenoisingFailedException e) {
                Log.error("Failed to denoise", e);
            }
        }
    }
}
