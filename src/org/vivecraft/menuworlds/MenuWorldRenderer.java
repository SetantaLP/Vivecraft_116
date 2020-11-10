package org.vivecraft.menuworlds;

import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.vivecraft.reflection.MCReflection;
import org.vivecraft.render.GLUtils;
import org.vivecraft.settings.VRSettings;

import com.google.common.collect.ImmutableList;
import com.mojang.blaze3d.matrix.MatrixStack;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.serialization.MapCodec;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockRendererDispatcher;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.FluidBlockRenderer;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.RenderTypeLookup;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.AtlasTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.NativeImage;
import net.minecraft.client.renderer.texture.Texture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.renderer.vertex.VertexBuffer;
import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.vertex.VertexFormatElement;
import net.minecraft.client.settings.AmbientOcclusionStatus;
import net.minecraft.client.world.DimensionRenderInfo;
import net.minecraft.fluid.Fluid;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ITag;
import net.minecraft.util.CubicSampler;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.vector.Matrix4f;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.IWorldReader;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.Biomes;
import net.optifine.Config;
import net.optifine.shaders.Shaders;
import net.optifine.util.TextureUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.NVFogDistance;

public class MenuWorldRenderer {
	private static final ResourceLocation MOON_PHASES_TEXTURES = new ResourceLocation("textures/environment/moon_phases.png");
	private static final ResourceLocation SUN_TEXTURES = new ResourceLocation("textures/environment/sun.png");
	private static final ResourceLocation CLOUDS_TEXTURES = new ResourceLocation("textures/environment/clouds.png");
	private static final ResourceLocation END_SKY_TEXTURES = new ResourceLocation("textures/environment/end_sky.png");

	private Minecraft mc;
	private DimensionRenderInfo dimensionInfo;
	private FakeBlockAccess blockAccess;
	private final DynamicTexture lightmapTexture;
	private final NativeImage lightmapColors;
	private final ResourceLocation locationLightMap;
	private boolean lightmapUpdateNeeded;
	private float torchFlickerX;
	private float torchFlickerDX;
	private int counterInWater;
	public long time = 1000;
	private VertexBuffer[] vertexBuffers;
	private VertexFormat vertexBufferFormat;
	private VertexBuffer starVBO;
	private VertexBuffer skyVBO;
	private VertexBuffer sky2VBO;
	private int renderDistance;
	private int renderDistanceChunks;
	public MenuCloudRenderer cloudRenderer;
	public MenuFogRenderer fogRenderer;
	public Set<TextureAtlasSprite> visibleTextures = new HashSet<>();
	private Random rand;
	private boolean ready;
	private boolean lol;

	public MenuWorldRenderer() {
		this.mc = Minecraft.getInstance();
		this.lightmapTexture = new DynamicTexture(16, 16, false);
		this.locationLightMap = mc.getTextureManager().getDynamicTextureLocation("lightMap", this.lightmapTexture);
		this.lightmapColors = this.lightmapTexture.getTextureData();
		{
			ImmutableList.Builder<VertexFormatElement> builder = ImmutableList.builder();
			builder.add(new VertexFormatElement(0, VertexFormatElement.Type.FLOAT, VertexFormatElement.Usage.POSITION, 3));
			this.vertexBufferFormat = new VertexFormat(builder.build());
		}
		this.cloudRenderer = new MenuCloudRenderer(mc);
		this.fogRenderer = new MenuFogRenderer(this);
		this.rand = new Random();
		this.rand.nextInt(); // toss some bits in the bin
	}

	public void init() {
		if (mc.vrSettings.menuWorldSelection == VRSettings.MENU_WORLD_NONE) {
			System.out.println("Main menu worlds disabled.");
			return;
		}

		try {
			InputStream inputStream = MenuWorldDownloader.getRandomWorld();
			if (inputStream != null) {
				System.out.println("Initializing main menu world renderer...");
				loadRenderers();
				System.out.println("Loading world data...");
				setWorld(MenuWorldExporter.loadWorld(inputStream));
				System.out.println("Building geometry...");
				prepare();
				mc.gameRenderer.menuWorldFastTime = new Random().nextInt(10) == 0;
			} else {
				System.out.println("Failed to load any main menu world, falling back to old menu room");
			}
		} catch (Throwable e) { // Only effective way of preventing crash on poop computers with low heap size
			if (e instanceof OutOfMemoryError || e.getCause() instanceof OutOfMemoryError) {
				System.out.println("OutOfMemoryError while loading main menu world. Low heap size or 32-bit Java?");
			} else {
				System.out.println("Exception thrown when loading main menu world, falling back to old menu room");
			}
			e.printStackTrace();
			destroy();
			setWorld(null);
		}
	}


	public void render() {
		prepare();
		RenderSystem.shadeModel(GL11.GL_SMOOTH); // holy shit make AO work
		GL11.glPushClientAttrib(GL11.GL_CLIENT_VERTEX_ARRAY_BIT);
		enableLightmap();
		RenderSystem.pushMatrix();

		Texture blocksTexture = this.mc.getTextureManager().getTexture(AtlasTexture.LOCATION_BLOCKS_TEXTURE);
		mc.getTextureManager().bindTexture(AtlasTexture.LOCATION_BLOCKS_TEXTURE);

		int ground = blockAccess.getGround();
		if (lol) ground += 100;
		RenderSystem.translatef(-blockAccess.getXSize() / 2, -ground, -blockAccess.getZSize() / 2);

		Matrix4f matrix = GLUtils.getViewModelMatrix();

		GlStateManager.disableBlend();
		RenderSystem.disableAlphaTest();
		drawBlockLayer(RenderType.getSolid(), matrix);
		RenderSystem.enableAlphaTest();

		blocksTexture.setBlurMipmapDirect(false, this.mc.gameSettings.mipmapLevels > 0);
		drawBlockLayer(RenderType.getCutoutMipped(), matrix);
		blocksTexture.restoreLastBlurMipmap();

		blocksTexture.setBlurMipmapDirect(false, false);
		drawBlockLayer(RenderType.getCutout(), matrix);
		blocksTexture.restoreLastBlurMipmap();

		GlStateManager.enableBlend();
		RenderSystem.depthMask(false);
		drawBlockLayer(RenderType.getTranslucent(), matrix);
		drawBlockLayer(RenderType.getTripwire(), matrix); // tripwire
		RenderSystem.depthMask(true);

		DefaultVertexFormats.BLOCK_VANILLA.clearBufferState();
		RenderSystem.popMatrix();
		disableLightmap();
		GL11.glPopClientAttrib();
	}

	private void drawBlockLayer(RenderType layer, Matrix4f matrix) {
		VertexBuffer vertexBuffer = vertexBuffers[layer.ordinal()];
		vertexBuffer.bindBuffer();
		DefaultVertexFormats.BLOCK_VANILLA.setupBufferState(0);
		vertexBuffer.draw(matrix, GL11.GL_QUADS);
	}

	public void prepare() {
		if (vertexBuffers == null) {
			AmbientOcclusionStatus ao = mc.gameSettings.ambientOcclusionStatus;
			mc.gameSettings.ambientOcclusionStatus = AmbientOcclusionStatus.MAX;
			boolean shaders = Shaders.shaderPackLoaded;
			Shaders.shaderPackLoaded = false;
			FluidBlockRenderer.skipStupidGoddamnChunkBoundaryClipping = true;
			DefaultVertexFormats.updateVertexFormats();
			RenderTypeLookup.setFancyGraphics(true);
			TextureUtils.resourcesReloaded(Config.getResourceManager());
			visibleTextures.clear();
			lol = rand.nextInt(1000) == 0;

			try {
				List<RenderType> layers = RenderType.getBlockRenderTypes();
				vertexBuffers = new VertexBuffer[layers.size()];

				Random random = new Random();
				BlockRendererDispatcher blockRenderer = mc.getBlockRendererDispatcher();
				MatrixStack matrixStack = new MatrixStack();
				for (int i = 0; i < vertexBuffers.length; i++) {
					RenderType layer = layers.get(i);
					System.out.println("Layer: " + layer.getName());
					BufferBuilder vertBuffer = new BufferBuilder(20 * 2097152);
					vertBuffer.begin(GL11.GL_QUADS, DefaultVertexFormats.BLOCK_VANILLA);
					vertBuffer.setBlockLayer(layer);

					int c = 0;
					for (int x = 0; x < blockAccess.getXSize(); x++) {
						for (int y = 0; y < blockAccess.getYSize(); y++) {
							for (int z = 0; z < blockAccess.getZSize(); z++) {
								BlockPos pos = new BlockPos(x, y, z);
								BlockState state = blockAccess.getBlockState(pos);
								if (state != null) {
									FluidState fluidState = state.getFluidState();
									if (!fluidState.isEmpty() && RenderTypeLookup.getRenderType(fluidState) == layer) {
										if (blockRenderer.renderFluid(pos, blockAccess, vertBuffer, new FluidStateWrapper(fluidState)))
											c++;
									}
									if (state.getRenderType() != BlockRenderType.INVISIBLE && RenderTypeLookup.getChunkRenderType(state) == layer) {
										matrixStack.push();
										matrixStack.translate(pos.getX(), pos.getY(), pos.getZ());
										if (blockRenderer.renderModel(state, pos, blockAccess, matrixStack, vertBuffer, true, random))
											c++;
										matrixStack.pop();
									}
								}
							}
						}
					}

					System.out.println("Built " + c + " blocks.");
					if (layer.isNeedsSorting())
						vertBuffer.sortVertexData(blockAccess.getXSize() / 2, blockAccess.getGround(), blockAccess.getXSize() / 2);
					vertBuffer.finishDrawing();
					vertexBuffers[i] = new VertexBuffer(vertBuffer.getVertexFormat());
					vertexBuffers[i].upload(vertBuffer);
				}

				copyVisibleTextures();
				ready = true;
			} finally {
				mc.gameSettings.ambientOcclusionStatus = ao;
				FluidBlockRenderer.skipStupidGoddamnChunkBoundaryClipping = false;
				if (shaders) {
					Shaders.shaderPackLoaded = shaders;
					TextureUtils.resourcesReloaded(Config.getResourceManager());
				}
				DefaultVertexFormats.updateVertexFormats();
			}
		}
	}

	public void destroy() {
		if (vertexBuffers != null) {
			for (VertexBuffer vertexBuffer : vertexBuffers) {
				if (vertexBuffer != null) vertexBuffer.close();
			}
			vertexBuffers = null;
		}
		ready = false;
	}

	public void tick() {
		this.updateTorchFlicker();

		if (this.areEyesInFluid(FluidTags.WATER))
		{
			int i = 1; //this.isSpectator() ? 10 : 1;
			this.counterInWater = MathHelper.clamp(this.counterInWater + i, 0, 600);
		}
		else if (this.counterInWater > 0)
		{
			this.areEyesInFluid(FluidTags.WATER);
			this.counterInWater = MathHelper.clamp(this.counterInWater - 10, 0, 600);
		}
	}

	public FakeBlockAccess getWorld() {
		return blockAccess;
	}

	public void setWorld(FakeBlockAccess blockAccess) {
		this.blockAccess = blockAccess;
		if (blockAccess != null) {
			this.dimensionInfo = blockAccess.getDimensionReaderInfo();
			this.lightmapUpdateNeeded = true;
			this.renderDistance = blockAccess.getXSize() / 2;
			this.renderDistanceChunks = this.renderDistance / 16;
		}
	}

	public void loadRenderers() throws Exception {
		this.generateSky();
		this.generateSky2();
		this.generateStars();
	}

	public boolean isReady() {
		return ready;
	}

	// VanillaFix support
	@SuppressWarnings("unchecked")
	private void copyVisibleTextures() {
		/*if (Reflector.VFTemporaryStorage.exists()) {
			if (Reflector.VFTemporaryStorage_texturesUsed.exists()) {
				visibleTextures.addAll((Collection<TextureAtlasSprite>)Reflector.getFieldValue(Reflector.VFTemporaryStorage_texturesUsed));
			} else if (Reflector.VFTextureAtlasSprite_needsAnimationUpdate.exists()) {
				for (TextureAtlasSprite texture : (Collection<TextureAtlasSprite>)MCReflection.TextureMap_listAnimatedSprites.get(mc.getTextureMapBlocks())) {
					if (Reflector.callBoolean(texture, Reflector.VFTextureAtlasSprite_needsAnimationUpdate))
						visibleTextures.add(texture);
				}
			}
		}*/
	}

	@SuppressWarnings("unchecked")
	public void pushVisibleTextures() {
		/*if (Reflector.VFTemporaryStorage.exists()) {
			if (Reflector.VFTemporaryStorage_texturesUsed.exists()) {
				Collection<TextureAtlasSprite> coll = (Collection<TextureAtlasSprite>)Reflector.getFieldValue(Reflector.VFTemporaryStorage_texturesUsed);
				coll.addAll(visibleTextures);
			} else if (Reflector.VFTextureAtlasSprite_markNeedsAnimationUpdate.exists()) {
				for (TextureAtlasSprite texture : visibleTextures)
					Reflector.call(texture, Reflector.VFTextureAtlasSprite_markNeedsAnimationUpdate);
			}
		}*/
	}
	// End VanillaFix support

	public void renderSky(float x, float y, float z, int pass)
	{
		if (this.dimensionInfo.func_241683_c_() == DimensionRenderInfo.FogType.END)
		{
			this.renderSkyEnd();
		}
		else if (this.dimensionInfo.func_241683_c_() == DimensionRenderInfo.FogType.NORMAL)
		{
			GlStateManager.disableTexture();
			//boolean flag = Config.isShaders();

            /*if (flag)
            {
                Shaders.disableTexture();
            }*/

			Vector3d vec3d = this.getSkyColor(x, y, z);
			//vec3d = CustomColors.getSkyColor(vec3d, this.mc.world, this.mc.getRenderViewEntity().posX, this.mc.getRenderViewEntity().posY + 1.0D, this.mc.getRenderViewEntity().posZ);

            /*if (flag)
            {
                Shaders.setSkyColor(vec3d);
            }*/

			float f = (float)vec3d.x;
			float f1 = (float)vec3d.y;
			float f2 = (float)vec3d.z;
			RenderSystem.color3f(f, f1, f2);
			Tessellator tessellator = Tessellator.getInstance();
			BufferBuilder bufferbuilder = tessellator.getBuffer();
			GlStateManager.depthMask(false);
			GlStateManager.enableFog();

            /*if (flag)
            {
                Shaders.enableFog();
            }*/

			RenderSystem.color3f(f, f1, f2);

            /*if (flag)
            {
                Shaders.preSkyList();
            }*/

			if (Config.isSkyEnabled())
			{
				this.skyVBO.bindBuffer();
				GlStateManager.enableClientState(32884);
				GlStateManager.vertexPointer(3, 5126, 12, 0);
				this.skyVBO.draw(GLUtils.getViewModelMatrix(), 7);
				VertexBuffer.unbindBuffer();
				GlStateManager.disableClientState(32884);
			}

			GlStateManager.disableFog();

            /*if (flag)
            {
                Shaders.disableFog();
            }*/

			GlStateManager.disableAlphaTest();
			GlStateManager.enableBlend();
			RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
			RenderHelper.disableStandardItemLighting();
			float[] afloat = this.dimensionInfo.func_230492_a_(this.getCelestialAngle(), 0); // calcSunriseSunsetColors

			if (afloat != null && Config.isSunMoonEnabled())
			{
				GlStateManager.disableTexture();

                /*if (flag)
                {
                    Shaders.disableTexture();
                }*/

				GlStateManager.shadeModel(7425);
				GlStateManager.pushMatrix();
				GlStateManager.rotatef(90.0F, 1.0F, 0.0F, 0.0F);
				GlStateManager.rotatef(MathHelper.sin(this.getCelestialAngleRadians()) < 0.0F ? 180.0F : 0.0F, 0.0F, 0.0F, 1.0F);
				GlStateManager.rotatef(90.0F, 0.0F, 0.0F, 1.0F);
				float f3 = afloat[0];
				float f4 = afloat[1];
				float f5 = afloat[2];
				bufferbuilder.begin(6, DefaultVertexFormats.POSITION_COLOR);
				bufferbuilder.pos(0.0D, 100.0D, 0.0D).color(f3, f4, f5, afloat[3]).endVertex();
				int i = 16;

				for (int j = 0; j <= 16; ++j)
				{
					float f6 = (float)j * ((float)Math.PI * 2F) / 16.0F;
					float f7 = MathHelper.sin(f6);
					float f8 = MathHelper.cos(f6);
					bufferbuilder.pos((double)(f7 * 120.0F), (double)(f8 * 120.0F), (double)(-f8 * 40.0F * afloat[3])).color(afloat[0], afloat[1], afloat[2], 0.0F).endVertex();
				}

				tessellator.draw();
				GlStateManager.popMatrix();
				GlStateManager.shadeModel(7424);
			}

			GlStateManager.enableTexture();

            /*if (flag)
            {
                Shaders.enableTexture();
            }*/

			RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE, GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
			GlStateManager.pushMatrix();
			float f10 = 1.0F; //1.0F - this.world.getRainStrength(partialTicks);
			GlStateManager.color4f(1.0F, 1.0F, 1.0F, f10);
			GlStateManager.rotatef(-90.0F, 0.0F, 1.0F, 0.0F);
			//CustomSky.renderSky(this.world, this.textureManager, partialTicks);

            /*if (flag)
            {
                Shaders.preCelestialRotate();
            }*/

			GlStateManager.rotatef(this.getCelestialAngle() * 360.0F, 1.0F, 0.0F, 0.0F);

            /*if (flag)
            {
                Shaders.postCelestialRotate();
            }*/

			float f11 = 30.0F;

			if (Config.isSunTexture())
			{
				this.mc.getTextureManager().bindTexture(SUN_TEXTURES);
				bufferbuilder.begin(7, DefaultVertexFormats.POSITION_TEX);
				bufferbuilder.pos((double)(-f11), 100.0D, (double)(-f11)).tex(0.0F, 0.0F).endVertex();
				bufferbuilder.pos((double)f11, 100.0D, (double)(-f11)).tex(1.0F, 0.0F).endVertex();
				bufferbuilder.pos((double)f11, 100.0D, (double)f11).tex(1.0F, 1.0F).endVertex();
				bufferbuilder.pos((double)(-f11), 100.0D, (double)f11).tex(0.0F, 1.0F).endVertex();
				tessellator.draw();
			}

			f11 = 20.0F;

			if (Config.isMoonTexture())
			{
				this.mc.getTextureManager().bindTexture(MOON_PHASES_TEXTURES);
				int k = this.getMoonPhase();
				int l = k % 4;
				int i1 = k / 4 % 2;
				float f13 = (float)(l + 0) / 4.0F;
				float f14 = (float)(i1 + 0) / 2.0F;
				float f15 = (float)(l + 1) / 4.0F;
				float f9 = (float)(i1 + 1) / 2.0F;
				bufferbuilder.begin(7, DefaultVertexFormats.POSITION_TEX);
				bufferbuilder.pos((double)(-f11), -100.0D, (double)f11).tex(f15, f9).endVertex();
				bufferbuilder.pos((double)f11, -100.0D, (double)f11).tex(f13, f9).endVertex();
				bufferbuilder.pos((double)f11, -100.0D, (double)(-f11)).tex(f13, f14).endVertex();
				bufferbuilder.pos((double)(-f11), -100.0D, (double)(-f11)).tex(f15, f14).endVertex();
				tessellator.draw();
			}

			GlStateManager.disableTexture();

            /*if (flag)
            {
                Shaders.disableTexture();
            }*/

			float f12 = this.getStarBrightness() * f10;

			if (f12 > 0.0F && Config.isStarsEnabled() /*&& !CustomSky.hasSkyLayers(this.world)*/)
			{
				GlStateManager.color4f(f12, f12, f12, f12);

				this.starVBO.bindBuffer();
				GlStateManager.enableClientState(32884);
				GlStateManager.vertexPointer(3, 5126, 12, 0);
				this.starVBO.draw(GLUtils.getViewModelMatrix(), 7);
				VertexBuffer.unbindBuffer();
				GlStateManager.disableClientState(32884);
			}

			GlStateManager.color4f(1.0F, 1.0F, 1.0F, 1.0F);
			GlStateManager.disableBlend();
			GlStateManager.enableAlphaTest();
			GlStateManager.enableFog();

            /*if (flag)
            {
                Shaders.enableFog();
            }*/

			GlStateManager.popMatrix();
			GlStateManager.disableTexture();

            /*if (flag)
            {
                Shaders.disableTexture();
            }*/

			RenderSystem.color3f(0.0F, 0.0F, 0.0F);
			double d0 = y - this.blockAccess.getHorizon();

			if (d0 < 0.0D)
			{
				GlStateManager.pushMatrix();
				GlStateManager.translatef(0.0F, 12.0F, 0.0F);

				this.sky2VBO.bindBuffer();
				GlStateManager.enableClientState(32884);
				GlStateManager.vertexPointer(3, 5126, 12, 0);
				this.sky2VBO.draw(GLUtils.getViewModelMatrix(), 7);
				VertexBuffer.unbindBuffer();
				GlStateManager.disableClientState(32884);

				GlStateManager.popMatrix();
			}

			if (this.dimensionInfo.func_239216_b_()) // isSkyColored
			{
				RenderSystem.color3f(f * 0.2F + 0.04F, f1 * 0.2F + 0.04F, f2 * 0.6F + 0.1F);
			}
			else
			{
				RenderSystem.color3f(f, f1, f2);
			}

			if (this.renderDistanceChunks <= 4)
			{
				RenderSystem.color3f(this.fogRenderer.red, this.fogRenderer.green, this.fogRenderer.blue);
			}

			GlStateManager.pushMatrix();
			GlStateManager.translatef(0.0F, -((float)(d0 - 16.0D)), 0.0F);

			if (Config.isSkyEnabled())
			{
				this.sky2VBO.bindBuffer();
				GlStateManager.enableClientState(32884);
				GlStateManager.vertexPointer(3, 5126, 12, 0);
				this.sky2VBO.draw(GLUtils.getViewModelMatrix(), 7);
				VertexBuffer.unbindBuffer();
				GlStateManager.disableClientState(32884);
			}

			GlStateManager.popMatrix();
			GlStateManager.enableTexture();

            /*if (flag)
            {
                Shaders.enableTexture();
            }*/

			GlStateManager.depthMask(true);
		}
	}

	private void renderSkyEnd()
	{
		if (Config.isSkyEnabled())
		{
			GlStateManager.disableFog();
			GlStateManager.disableAlphaTest();
			GlStateManager.enableBlend();
			RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
			RenderHelper.disableStandardItemLighting();
			GlStateManager.depthMask(false);
			mc.getTextureManager().bindTexture(END_SKY_TEXTURES);
			Tessellator tessellator = Tessellator.getInstance();
			BufferBuilder vertexbuffer = tessellator.getBuffer();

			for (int i = 0; i < 6; ++i)
			{
				GlStateManager.pushMatrix();

				if (i == 1)
				{
					GlStateManager.rotatef(90.0F, 1.0F, 0.0F, 0.0F);
				}

				if (i == 2)
				{
					GlStateManager.rotatef(-90.0F, 1.0F, 0.0F, 0.0F);
				}

				if (i == 3)
				{
					GlStateManager.rotatef(180.0F, 1.0F, 0.0F, 0.0F);
				}

				if (i == 4)
				{
					GlStateManager.rotatef(90.0F, 0.0F, 0.0F, 1.0F);
				}

				if (i == 5)
				{
					GlStateManager.rotatef(-90.0F, 0.0F, 0.0F, 1.0F);
				}

				vertexbuffer.begin(7, DefaultVertexFormats.POSITION_TEX_COLOR);
				int j = 40;
				int k = 40;
				int l = 40;

				if (Config.isCustomColors())
				{
					Vector3d vec3d = new Vector3d((double)j / 255.0D, (double)k / 255.0D, (double)l / 255.0D);
					//vec3d = CustomColors.getWorldSkyColor(vec3d, this.world, this.mc.getRenderViewEntity(), 0.0F);
					j = (int)(vec3d.x * 255.0D);
					k = (int)(vec3d.y * 255.0D);
					l = (int)(vec3d.z * 255.0D);
				}

				vertexbuffer.pos(-100.0D, -100.0D, -100.0D).tex(0.0F, 0.0F).color(j, k, l, 255).endVertex();
				vertexbuffer.pos(-100.0D, -100.0D, 100.0D).tex(0.0F, 16.0F).color(j, k, l, 255).endVertex();
				vertexbuffer.pos(100.0D, -100.0D, 100.0D).tex(16.0F, 16.0F).color(j, k, l, 255).endVertex();
				vertexbuffer.pos(100.0D, -100.0D, -100.0D).tex(16.0F, 0.0F).color(j, k, l, 255).endVertex();
				tessellator.draw();
				GlStateManager.popMatrix();
			}

			GlStateManager.depthMask(true);
			GlStateManager.enableTexture();
			GlStateManager.enableAlphaTest();
		}
	}

	public void renderClouds(int pass, double x, double y, double z)
	{
		float cloudHeight = this.dimensionInfo.func_239213_a_();

		if (!Float.isNaN(cloudHeight)) {
			Vector3d vec3d = this.getCloudColour();
			this.cloudRenderer.prepareToRender(mc.getRenderPartialTicks(), vec3d);
			GlStateManager.pushMatrix();
			GlStateManager.disableCull();
			Tessellator tessellator = Tessellator.getInstance();
			BufferBuilder vertexbuffer = tessellator.getBuffer();
			float f = 12.0F;
			float f1 = 4.0F;
			double d0 = this.mc.tickCounter + mc.getRenderPartialTicks();
			double d1 = (x + d0 * 0.029999999329447746D) / 12.0D;
			double d2 = z / 12.0D + 0.33000001311302185D;
			float f2 = cloudHeight - (float)y + 0.33F;
			
			f2 = f2 + (float)mc.gameSettings.ofCloudsHeight * 128.0F;
			int i = MathHelper.floor(d1 / 2048.0D);
			int j = MathHelper.floor(d2 / 2048.0D);
			d1 = d1 - (double)(i * 2048);
			d2 = d2 - (double)(j * 2048);
			mc.getTextureManager().bindTexture(CLOUDS_TEXTURES);
			GlStateManager.enableBlend();
			RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
			float f3 = (float)vec3d.x;
			float f4 = (float)vec3d.y;
			float f5 = (float)vec3d.z;

			if (pass != 2)
			{
				float f6 = (f3 * 30.0F + f4 * 59.0F + f5 * 11.0F) / 100.0F;
				float f7 = (f3 * 30.0F + f4 * 70.0F) / 100.0F;
				float f8 = (f3 * 30.0F + f5 * 70.0F) / 100.0F;
				f3 = f6;
				f4 = f7;
				f5 = f8;
			}

			float f25 = f3 * 0.9F;
			float f26 = f4 * 0.9F;
			float f27 = f5 * 0.9F;
			float f9 = f3 * 0.7F;
			float f10 = f4 * 0.7F;
			float f11 = f5 * 0.7F;
			float f12 = f3 * 0.8F;
			float f13 = f4 * 0.8F;
			float f14 = f5 * 0.8F;
			float f15 = 0.00390625F;
			float f16 = (float) MathHelper.floor(d1) * 0.00390625F;
			float f17 = (float) MathHelper.floor(d2) * 0.00390625F;
			float f18 = (float)(d1 - (double) MathHelper.floor(d1));
			float f19 = (float)(d2 - (double) MathHelper.floor(d2));
			int k = 8;
			int l = 4;
			float f20 = 9.765625E-4F;
			GlStateManager.scalef(12.0F, 1.0F, 12.0F);

			for (int i1 = 0; i1 < 2; ++i1)
			{
				if (i1 == 0)
				{
					GlStateManager.colorMask(false, false, false, false);
				}
				else
				{
					switch (pass)
					{
						case 0:
							GlStateManager.colorMask(false, true, true, true);
							break;

						case 1:
							GlStateManager.colorMask(true, false, false, true);
							break;

						case 2:
							GlStateManager.colorMask(true, true, true, true);
					}
				}

				this.cloudRenderer.renderGlList((float)x, (float)y, (float)z);
			}

			if (this.cloudRenderer.shouldUpdateGlList((float)y))
			{
				this.cloudRenderer.startUpdateGlList();

				for (int l1 = -3; l1 <= 4; ++l1)
				{
					for (int j1 = -3; j1 <= 4; ++j1)
					{
						vertexbuffer.begin(7, DefaultVertexFormats.POSITION_TEX_COLOR_NORMAL);
						float f21 = (float)(l1 * 8);
						float f22 = (float)(j1 * 8);
						float f23 = f21 - f18;
						float f24 = f22 - f19;

						if (f2 > -5.0F)
						{
							vertexbuffer.pos((double)(f23 + 0.0F), (double)(f2 + 0.0F), (double)(f24 + 8.0F)).tex((f21 + 0.0F) * 0.00390625F + f16, (f22 + 8.0F) * 0.00390625F + f17).color(f9, f10, f11, 0.8F).normal(0.0F, -1.0F, 0.0F).endVertex();
							vertexbuffer.pos((double)(f23 + 8.0F), (double)(f2 + 0.0F), (double)(f24 + 8.0F)).tex((f21 + 8.0F) * 0.00390625F + f16, (f22 + 8.0F) * 0.00390625F + f17).color(f9, f10, f11, 0.8F).normal(0.0F, -1.0F, 0.0F).endVertex();
							vertexbuffer.pos((double)(f23 + 8.0F), (double)(f2 + 0.0F), (double)(f24 + 0.0F)).tex((f21 + 8.0F) * 0.00390625F + f16, (f22 + 0.0F) * 0.00390625F + f17).color(f9, f10, f11, 0.8F).normal(0.0F, -1.0F, 0.0F).endVertex();
							vertexbuffer.pos((double)(f23 + 0.0F), (double)(f2 + 0.0F), (double)(f24 + 0.0F)).tex((f21 + 0.0F) * 0.00390625F + f16, (f22 + 0.0F) * 0.00390625F + f17).color(f9, f10, f11, 0.8F).normal(0.0F, -1.0F, 0.0F).endVertex();
						}

						if (f2 <= 5.0F)
						{
							vertexbuffer.pos((double)(f23 + 0.0F), (double)(f2 + 4.0F - 9.765625E-4F), (double)(f24 + 8.0F)).tex((f21 + 0.0F) * 0.00390625F + f16, (f22 + 8.0F) * 0.00390625F + f17).color(f3, f4, f5, 0.8F).normal(0.0F, 1.0F, 0.0F).endVertex();
							vertexbuffer.pos((double)(f23 + 8.0F), (double)(f2 + 4.0F - 9.765625E-4F), (double)(f24 + 8.0F)).tex((f21 + 8.0F) * 0.00390625F + f16, (f22 + 8.0F) * 0.00390625F + f17).color(f3, f4, f5, 0.8F).normal(0.0F, 1.0F, 0.0F).endVertex();
							vertexbuffer.pos((double)(f23 + 8.0F), (double)(f2 + 4.0F - 9.765625E-4F), (double)(f24 + 0.0F)).tex((f21 + 8.0F) * 0.00390625F + f16, (f22 + 0.0F) * 0.00390625F + f17).color(f3, f4, f5, 0.8F).normal(0.0F, 1.0F, 0.0F).endVertex();
							vertexbuffer.pos((double)(f23 + 0.0F), (double)(f2 + 4.0F - 9.765625E-4F), (double)(f24 + 0.0F)).tex((f21 + 0.0F) * 0.00390625F + f16, (f22 + 0.0F) * 0.00390625F + f17).color(f3, f4, f5, 0.8F).normal(0.0F, 1.0F, 0.0F).endVertex();
						}

						if (l1 > -1)
						{
							for (int k1 = 0; k1 < 8; ++k1)
							{
								vertexbuffer.pos((double)(f23 + (float)k1 + 0.0F), (double)(f2 + 0.0F), (double)(f24 + 8.0F)).tex((f21 + (float)k1 + 0.5F) * 0.00390625F + f16, (f22 + 8.0F) * 0.00390625F + f17).color(f25, f26, f27, 0.8F).normal(-1.0F, 0.0F, 0.0F).endVertex();
								vertexbuffer.pos((double)(f23 + (float)k1 + 0.0F), (double)(f2 + 4.0F), (double)(f24 + 8.0F)).tex((f21 + (float)k1 + 0.5F) * 0.00390625F + f16, (f22 + 8.0F) * 0.00390625F + f17).color(f25, f26, f27, 0.8F).normal(-1.0F, 0.0F, 0.0F).endVertex();
								vertexbuffer.pos((double)(f23 + (float)k1 + 0.0F), (double)(f2 + 4.0F), (double)(f24 + 0.0F)).tex((f21 + (float)k1 + 0.5F) * 0.00390625F + f16, (f22 + 0.0F) * 0.00390625F + f17).color(f25, f26, f27, 0.8F).normal(-1.0F, 0.0F, 0.0F).endVertex();
								vertexbuffer.pos((double)(f23 + (float)k1 + 0.0F), (double)(f2 + 0.0F), (double)(f24 + 0.0F)).tex((f21 + (float)k1 + 0.5F) * 0.00390625F + f16, (f22 + 0.0F) * 0.00390625F + f17).color(f25, f26, f27, 0.8F).normal(-1.0F, 0.0F, 0.0F).endVertex();
							}
						}

						if (l1 <= 1)
						{
							for (int i2 = 0; i2 < 8; ++i2)
							{
								vertexbuffer.pos((double)(f23 + (float)i2 + 1.0F - 9.765625E-4F), (double)(f2 + 0.0F), (double)(f24 + 8.0F)).tex((f21 + (float)i2 + 0.5F) * 0.00390625F + f16, (f22 + 8.0F) * 0.00390625F + f17).color(f25, f26, f27, 0.8F).normal(1.0F, 0.0F, 0.0F).endVertex();
								vertexbuffer.pos((double)(f23 + (float)i2 + 1.0F - 9.765625E-4F), (double)(f2 + 4.0F), (double)(f24 + 8.0F)).tex((f21 + (float)i2 + 0.5F) * 0.00390625F + f16, (f22 + 8.0F) * 0.00390625F + f17).color(f25, f26, f27, 0.8F).normal(1.0F, 0.0F, 0.0F).endVertex();
								vertexbuffer.pos((double)(f23 + (float)i2 + 1.0F - 9.765625E-4F), (double)(f2 + 4.0F), (double)(f24 + 0.0F)).tex((f21 + (float)i2 + 0.5F) * 0.00390625F + f16, (f22 + 0.0F) * 0.00390625F + f17).color(f25, f26, f27, 0.8F).normal(1.0F, 0.0F, 0.0F).endVertex();
								vertexbuffer.pos((double)(f23 + (float)i2 + 1.0F - 9.765625E-4F), (double)(f2 + 0.0F), (double)(f24 + 0.0F)).tex((f21 + (float)i2 + 0.5F) * 0.00390625F + f16, (f22 + 0.0F) * 0.00390625F + f17).color(f25, f26, f27, 0.8F).normal(1.0F, 0.0F, 0.0F).endVertex();
							}
						}

						if (j1 > -1)
						{
							for (int j2 = 0; j2 < 8; ++j2)
							{
								vertexbuffer.pos((double)(f23 + 0.0F), (double)(f2 + 4.0F), (double)(f24 + (float)j2 + 0.0F)).tex((f21 + 0.0F) * 0.00390625F + f16, (f22 + (float)j2 + 0.5F) * 0.00390625F + f17).color(f12, f13, f14, 0.8F).normal(0.0F, 0.0F, -1.0F).endVertex();
								vertexbuffer.pos((double)(f23 + 8.0F), (double)(f2 + 4.0F), (double)(f24 + (float)j2 + 0.0F)).tex((f21 + 8.0F) * 0.00390625F + f16, (f22 + (float)j2 + 0.5F) * 0.00390625F + f17).color(f12, f13, f14, 0.8F).normal(0.0F, 0.0F, -1.0F).endVertex();
								vertexbuffer.pos((double)(f23 + 8.0F), (double)(f2 + 0.0F), (double)(f24 + (float)j2 + 0.0F)).tex((f21 + 8.0F) * 0.00390625F + f16, (f22 + (float)j2 + 0.5F) * 0.00390625F + f17).color(f12, f13, f14, 0.8F).normal(0.0F, 0.0F, -1.0F).endVertex();
								vertexbuffer.pos((double)(f23 + 0.0F), (double)(f2 + 0.0F), (double)(f24 + (float)j2 + 0.0F)).tex((f21 + 0.0F) * 0.00390625F + f16, (f22 + (float)j2 + 0.5F) * 0.00390625F + f17).color(f12, f13, f14, 0.8F).normal(0.0F, 0.0F, -1.0F).endVertex();
							}
						}

						if (j1 <= 1)
						{
							for (int k2 = 0; k2 < 8; ++k2)
							{
								vertexbuffer.pos((double)(f23 + 0.0F), (double)(f2 + 4.0F), (double)(f24 + (float)k2 + 1.0F - 9.765625E-4F)).tex((f21 + 0.0F) * 0.00390625F + f16, (f22 + (float)k2 + 0.5F) * 0.00390625F + f17).color(f12, f13, f14, 0.8F).normal(0.0F, 0.0F, 1.0F).endVertex();
								vertexbuffer.pos((double)(f23 + 8.0F), (double)(f2 + 4.0F), (double)(f24 + (float)k2 + 1.0F - 9.765625E-4F)).tex((f21 + 8.0F) * 0.00390625F + f16, (f22 + (float)k2 + 0.5F) * 0.00390625F + f17).color(f12, f13, f14, 0.8F).normal(0.0F, 0.0F, 1.0F).endVertex();
								vertexbuffer.pos((double)(f23 + 8.0F), (double)(f2 + 0.0F), (double)(f24 + (float)k2 + 1.0F - 9.765625E-4F)).tex((f21 + 8.0F) * 0.00390625F + f16, (f22 + (float)k2 + 0.5F) * 0.00390625F + f17).color(f12, f13, f14, 0.8F).normal(0.0F, 0.0F, 1.0F).endVertex();
								vertexbuffer.pos((double)(f23 + 0.0F), (double)(f2 + 0.0F), (double)(f24 + (float)k2 + 1.0F - 9.765625E-4F)).tex((f21 + 0.0F) * 0.00390625F + f16, (f22 + (float)k2 + 0.5F) * 0.00390625F + f17).color(f12, f13, f14, 0.8F).normal(0.0F, 0.0F, 1.0F).endVertex();
							}
						}

						tessellator.draw();
					}
				}

				this.cloudRenderer.endUpdateGlList((float)x, (float)y, (float)z);
			}

			GlStateManager.color4f(1.0F, 1.0F, 1.0F, 1.0F);
			GlStateManager.disableBlend();
			GlStateManager.enableCull();
			GlStateManager.popMatrix();
		}
	}

	public float getCelestialAngle()
	{
		return this.blockAccess.getDimensionType().getCelestrialAngleByTime(time);
	}

	public float getCelestialAngleRadians()
	{
		float f = this.getCelestialAngle();
		return f * ((float)Math.PI * 2F);
	}

	public int getMoonPhase()
	{
		return this.blockAccess.getDimensionType().getMoonPhase(time);
	}

	public float getSunBrightness() {
		float f = this.getCelestialAngle();
		float f1 = 1.0F - (MathHelper.cos(f * ((float)Math.PI * 2F)) * 2.0F + 0.2F);
		f1 = MathHelper.clamp(f1, 0.0F, 1.0F);
		f1 = 1.0F - f1;
		f1 = (float)((double)f1 * (1.0D - (double)(/*this.getRainStrength(partialTicks)*/ 0 * 5.0F) / 16.0D));
		f1 = (float)((double)f1 * (1.0D - (double)(/*this.getThunderStrength(partialTicks)*/ 0 * 5.0F) / 16.0D));
		return f1 * 0.8F + 0.2F;
	}

	public float getStarBrightness()
	{
		float f = this.getCelestialAngle();
		float f1 = 1.0F - (MathHelper.cos(f * ((float)Math.PI * 2F)) * 2.0F + 0.25F);
		f1 = MathHelper.clamp(f1, 0.0F, 1.0F);
		return f1 * f1 * 0.5F;
	}

	public Vector3d getSkyColor(float x, float y, float z)
	{
		float f = this.getCelestialAngle();
		float f1 = MathHelper.cos(f * ((float)Math.PI * 2F)) * 2.0F + 0.5F;
		f1 = MathHelper.clamp(f1, 0.0F, 1.0F);
		int i = MathHelper.floor(x);
		int j = MathHelper.floor(y);
		int k = MathHelper.floor(z);
		BlockPos blockpos = new BlockPos(i, j, k);
		Biome biome = this.blockAccess.getBiome(blockpos);
		//float f2 = biome.getTemperature(blockpos);
		int l = biome.getSkyColor();
		float f3 = (float)(l >> 16 & 255) / 255.0F;
		float f4 = (float)(l >> 8 & 255) / 255.0F;
		float f5 = (float)(l & 255) / 255.0F;
		f3 = f3 * f1;
		f4 = f4 * f1;
		f5 = f5 * f1;
        /*float f6 = this.getRainStrength(partialTicks);

        if (f6 > 0.0F)
        {
            float f7 = (f3 * 0.3F + f4 * 0.59F + f5 * 0.11F) * 0.6F;
            float f8 = 1.0F - f6 * 0.75F;
            f3 = f3 * f8 + f7 * (1.0F - f8);
            f4 = f4 * f8 + f7 * (1.0F - f8);
            f5 = f5 * f8 + f7 * (1.0F - f8);
        }

        float f10 = this.getThunderStrength(partialTicks);

        if (f10 > 0.0F)
        {
            float f11 = (f3 * 0.3F + f4 * 0.59F + f5 * 0.11F) * 0.2F;
            float f9 = 1.0F - f10 * 0.75F;
            f3 = f3 * f9 + f11 * (1.0F - f9);
            f4 = f4 * f9 + f11 * (1.0F - f9);
            f5 = f5 * f9 + f11 * (1.0F - f9);
        }

        if (this.lastLightningBolt > 0)
        {
            float f12 = (float)this.lastLightningBolt - partialTicks;

            if (f12 > 1.0F)
            {
                f12 = 1.0F;
            }

            f12 = f12 * 0.45F;
            f3 = f3 * (1.0F - f12) + 0.8F * f12;
            f4 = f4 * (1.0F - f12) + 0.8F * f12;
            f5 = f5 * (1.0F - f12) + 1.0F * f12;
        }*/

		return new Vector3d((double)f3, (double)f4, (double)f5);
	}

	public Vector3d getSkyColor(Vector3d pos) {
		return getSkyColor((float)pos.x, (float)pos.y, (float)pos.z);
	}

	public Vector3d getFogColor(Vector3d pos)
	{
		float f = MathHelper.clamp(MathHelper.cos(this.getCelestialAngle() * ((float)Math.PI * 2F)) * 2.0F + 0.5F, 0.0F, 1.0F);
		Vector3d scaledPos = pos.subtract(2.0D, 2.0D, 2.0D).scale(0.25D);
		return CubicSampler.func_240807_a_(scaledPos, (x, y, z) -> this.dimensionInfo.func_230494_a_(Vector3d.unpack(this.blockAccess.getBiomeManager().getBiomeAtPosition(x, y, z).getFogColor()), f));
	}

	public Vector3d getCloudColour()
	{
		float f = this.getCelestialAngle();
		float f1 = MathHelper.cos(f * ((float)Math.PI * 2F)) * 2.0F + 0.5F;
		f1 = MathHelper.clamp(f1, 0.0F, 1.0F);
		float f2 = 1.0F;
		float f3 = 1.0F;
		float f4 = 1.0F;
        /*float f5 = this.getRainStrength(partialTicks);

        if (f5 > 0.0F)
        {
            float f6 = (f2 * 0.3F + f3 * 0.59F + f4 * 0.11F) * 0.6F;
            float f7 = 1.0F - f5 * 0.95F;
            f2 = f2 * f7 + f6 * (1.0F - f7);
            f3 = f3 * f7 + f6 * (1.0F - f7);
            f4 = f4 * f7 + f6 * (1.0F - f7);
        }*/

		f2 = f2 * (f1 * 0.9F + 0.1F);
		f3 = f3 * (f1 * 0.9F + 0.1F);
		f4 = f4 * (f1 * 0.85F + 0.15F);
        /*float f9 = this.getThunderStrength(partialTicks);

        if (f9 > 0.0F)
        {
            float f10 = (f2 * 0.3F + f3 * 0.59F + f4 * 0.11F) * 0.2F;
            float f8 = 1.0F - f9 * 0.95F;
            f2 = f2 * f8 + f10 * (1.0F - f8);
            f3 = f3 * f8 + f10 * (1.0F - f8);
            f4 = f4 * f8 + f10 * (1.0F - f8);
        }*/

		return new Vector3d((double)f2, (double)f3, (double)f4);
	}

	private void generateSky() throws Exception
	{
		Tessellator tessellator = Tessellator.getInstance();
		BufferBuilder vertexbuffer = tessellator.getBuffer();

		if (this.skyVBO != null) {
			this.skyVBO.close();
		}

		this.skyVBO = new VertexBuffer(this.vertexBufferFormat);
		this.renderSky(vertexbuffer, 16.0F, false);
		vertexbuffer.finishDrawing();
		this.skyVBO.upload(vertexbuffer);
	}

	private void generateSky2() throws Exception
	{
		Tessellator tessellator = Tessellator.getInstance();
		BufferBuilder vertexbuffer = tessellator.getBuffer();

		if (this.sky2VBO != null) {
			this.sky2VBO.close();
		}
		this.sky2VBO = new VertexBuffer(this.vertexBufferFormat);
		this.renderSky(vertexbuffer, -16.0F, true);
		vertexbuffer.finishDrawing();
		this.sky2VBO.upload(vertexbuffer);
	}

	private void renderSky(BufferBuilder bufferBuilderIn, float posY, boolean reverseX)
	{
		int i = 64;
		int j = 6;
		bufferBuilderIn.begin(7, DefaultVertexFormats.POSITION);
		int k = (this.renderDistance / 64 + 1) * 64 + 64;

		for (int l = -k; l <= k; l += 64)
		{
			for (int i1 = -k; i1 <= k; i1 += 64)
			{
				float f = (float)l;
				float f1 = (float)(l + 64);

				if (reverseX)
				{
					f1 = (float)l;
					f = (float)(l + 64);
				}

				bufferBuilderIn.pos((double)f, (double)posY, (double)i1).endVertex();
				bufferBuilderIn.pos((double)f1, (double)posY, (double)i1).endVertex();
				bufferBuilderIn.pos((double)f1, (double)posY, (double)(i1 + 64)).endVertex();
				bufferBuilderIn.pos((double)f, (double)posY, (double)(i1 + 64)).endVertex();
			}
		}
	}

	private void generateStars() throws Exception
	{
		Tessellator tessellator = Tessellator.getInstance();
		BufferBuilder vertexbuffer = tessellator.getBuffer();

		if (this.starVBO != null)
		{
			this.starVBO.close();
		}

		this.starVBO = new VertexBuffer(this.vertexBufferFormat);
		this.renderStars(vertexbuffer);
		vertexbuffer.finishDrawing();
		this.starVBO.upload(vertexbuffer);
	}

	private void renderStars(BufferBuilder bufferBuilderIn)
	{
		Random random = new Random(10842L);
		bufferBuilderIn.begin(7, DefaultVertexFormats.POSITION);

		for (int i = 0; i < 1500; ++i)
		{
			double d0 = (double)(random.nextFloat() * 2.0F - 1.0F);
			double d1 = (double)(random.nextFloat() * 2.0F - 1.0F);
			double d2 = (double)(random.nextFloat() * 2.0F - 1.0F);
			double d3 = (double)(0.15F + random.nextFloat() * 0.1F);
			double d4 = d0 * d0 + d1 * d1 + d2 * d2;

			if (d4 < 1.0D && d4 > 0.01D)
			{
				d4 = 1.0D / Math.sqrt(d4);
				d0 = d0 * d4;
				d1 = d1 * d4;
				d2 = d2 * d4;
				double d5 = d0 * 100.0D;
				double d6 = d1 * 100.0D;
				double d7 = d2 * 100.0D;
				double d8 = Math.atan2(d0, d2);
				double d9 = Math.sin(d8);
				double d10 = Math.cos(d8);
				double d11 = Math.atan2(Math.sqrt(d0 * d0 + d2 * d2), d1);
				double d12 = Math.sin(d11);
				double d13 = Math.cos(d11);
				double d14 = random.nextDouble() * Math.PI * 2.0D;
				double d15 = Math.sin(d14);
				double d16 = Math.cos(d14);

				for (int j = 0; j < 4; ++j)
				{
					double d17 = 0.0D;
					double d18 = (double)((j & 2) - 1) * d3;
					double d19 = (double)((j + 1 & 2) - 1) * d3;
					double d20 = 0.0D;
					double d21 = d18 * d16 - d19 * d15;
					double d22 = d19 * d16 + d18 * d15;
					double d23 = d21 * d12 + 0.0D * d13;
					double d24 = 0.0D * d12 - d21 * d13;
					double d25 = d24 * d9 - d22 * d10;
					double d26 = d22 * d9 + d24 * d10;
					bufferBuilderIn.pos(d5 + d25, d6 + d23, d7 + d26).endVertex();
				}
			}
		}
	}

	public void disableLightmap()
	{
		GlStateManager.activeTexture(GlStateManager.GL_TEXTURE2);
		GlStateManager.disableTexture();
		GlStateManager.activeTexture(GlStateManager.GL_TEXTURE0);
	}

	public void enableLightmap()
	{
		GlStateManager.activeTexture(GlStateManager.GL_TEXTURE2);
		GlStateManager.matrixMode(GL11.GL_TEXTURE);
		GlStateManager.loadIdentity();
		float f = 0.00390625F;
		GlStateManager.scalef(f, f, f);
		GlStateManager.translatef(8.0F, 8.0F, 8.0F);
		GlStateManager.matrixMode(GL11.GL_MODELVIEW);
		mc.getTextureManager().bindTexture(this.locationLightMap);
		GlStateManager.texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
		GlStateManager.texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
		GlStateManager.texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
		GlStateManager.texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
		GlStateManager.color4f(1.0F, 1.0F, 1.0F, 1.0F);
		GlStateManager.enableTexture();
		GlStateManager.activeTexture(GlStateManager.GL_TEXTURE0);
	}

	public void updateTorchFlicker()
	{
		this.torchFlickerDX = (float)((double)this.torchFlickerDX + (Math.random() - Math.random()) * Math.random() * Math.random());
		this.torchFlickerDX = (float)((double)this.torchFlickerDX * 0.9D);
		this.torchFlickerX += this.torchFlickerDX - this.torchFlickerX;
		this.lightmapUpdateNeeded = true;
	}

	public void updateLightmap()
	{
		if (this.lightmapUpdateNeeded)
		{
			/*if (Config.isCustomColors())
			{
				boolean flag = this.client.player.isPotionActive(MobEffects.NIGHT_VISION) || this.client.player.isPotionActive(MobEffects.CONDUIT_POWER);

				if (CustomColors.updateLightmap(world, this.torchFlickerX, this.nativeImage, flag, partialTicks))
				{
					this.dynamicTexture.updateDynamicTexture();
					this.needsUpdate = false;
					this.client.profiler.endSection();
					return;
				}
			}*/

			float f16 = this.getSunBrightness();
			float f = f16 * 0.95F + 0.05F;
			//float f1 = this.client.player.getWaterBrightness();
			float f2 = 0.0F;

			/*if (this.client.player.isPotionActive(MobEffects.NIGHT_VISION))
			{
				f2 = this.gameRenderer.getNightVisionBrightness(this.client.player, partialTicks);
			}
			else if (f1 > 0.0F && this.client.player.isPotionActive(MobEffects.CONDUIT_POWER))
			{
				f2 = f1;
			}
			else
			{
				f2 = 0.0F;
			}*/

			for (int i = 0; i < 16; ++i)
			{
				for (int j = 0; j < 16; ++j)
				{
					float f3 = this.blockAccess.getDimensionType().getAmbientLight(i) * f;
					float f4 = this.blockAccess.getDimensionType().getAmbientLight(j) * (this.torchFlickerX * 0.1F + 1.5F);

					/*if (world.getLastLightningBolt() > 0)
					{
						f3 = world.dimension.getLightBrightnessTable()[i];
					}*/

					float f5 = f3 * (f16 * 0.65F + 0.35F);
					float f6 = f3 * (f16 * 0.65F + 0.35F);
					float f7 = f4 * ((f4 * 0.6F + 0.4F) * 0.6F + 0.4F);
					float f8 = f4 * (f4 * f4 * 0.6F + 0.4F);
					float f9 = f5 + f4;
					float f10 = f6 + f7;
					float f11 = f3 + f8;
					f9 = f9 * 0.96F + 0.03F;
					f10 = f10 * 0.96F + 0.03F;
					f11 = f11 * 0.96F + 0.03F;

					/*if (this.gameRenderer.getBossColorModifier(partialTicks) > 0.0F)
					{
						float f12 = this.gameRenderer.getBossColorModifier(partialTicks);
						f9 = f9 * (1.0F - f12) + f9 * 0.7F * f12;
						f10 = f10 * (1.0F - f12) + f10 * 0.6F * f12;
						f11 = f11 * (1.0F - f12) + f11 * 0.6F * f12;
					}*/

					if (this.dimensionInfo.func_241684_d_()) // isEnd I guess?
					{
						f9 = 0.22F + f4 * 0.75F;
						f10 = 0.28F + f7 * 0.75F;
						f11 = 0.25F + f8 * 0.75F;
					}

					if (f2 > 0.0F)
					{
						float f17 = 1.0F / f9;

						if (f17 > 1.0F / f10)
						{
							f17 = 1.0F / f10;
						}

						if (f17 > 1.0F / f11)
						{
							f17 = 1.0F / f11;
						}

						f9 = f9 * (1.0F - f2) + f9 * f17 * f2;
						f10 = f10 * (1.0F - f2) + f10 * f17 * f2;
						f11 = f11 * (1.0F - f2) + f11 * f17 * f2;
					}

					if (f9 > 1.0F)
					{
						f9 = 1.0F;
					}

					if (f10 > 1.0F)
					{
						f10 = 1.0F;
					}

					if (f11 > 1.0F)
					{
						f11 = 1.0F;
					}

					float f18 = (float)this.mc.gameSettings.gamma;
					float f13 = 1.0F - f9;
					float f14 = 1.0F - f10;
					float f15 = 1.0F - f11;
					f13 = 1.0F - f13 * f13 * f13 * f13;
					f14 = 1.0F - f14 * f14 * f14 * f14;
					f15 = 1.0F - f15 * f15 * f15 * f15;
					f9 = f9 * (1.0F - f18) + f13 * f18;
					f10 = f10 * (1.0F - f18) + f14 * f18;
					f11 = f11 * (1.0F - f18) + f15 * f18;
					f9 = f9 * 0.96F + 0.03F;
					f10 = f10 * 0.96F + 0.03F;
					f11 = f11 * 0.96F + 0.03F;

					if (f9 > 1.0F)
					{
						f9 = 1.0F;
					}

					if (f10 > 1.0F)
					{
						f10 = 1.0F;
					}

					if (f11 > 1.0F)
					{
						f11 = 1.0F;
					}

					if (f9 < 0.0F)
					{
						f9 = 0.0F;
					}

					if (f10 < 0.0F)
					{
						f10 = 0.0F;
					}

					if (f11 < 0.0F)
					{
						f11 = 0.0F;
					}

					int k = 255;
					int l = (int)(f9 * 255.0F);
					int i1 = (int)(f10 * 255.0F);
					int j1 = (int)(f11 * 255.0F);
					this.lightmapColors.setPixelRGBA(j, i, -16777216 | j1 << 16 | i1 << 8 | l);
				}
			}

			this.lightmapTexture.updateDynamicTexture();
			this.lightmapUpdateNeeded = false;
		}
	}

	public void setupFogColor(boolean black)
	{
		this.fogRenderer.applyFog(black);
	}

	public float getWaterBrightness()
	{
		if (!this.areEyesInFluid(FluidTags.WATER))
		{
			return 0.0F;
		}
		else
		{
			float f = 600.0F;
			float f1 = 100.0F;

			if ((float)this.counterInWater >= 600.0F)
			{
				return 1.0F;
			}
			else
			{
				float f2 = MathHelper.clamp((float)this.counterInWater / 100.0F, 0.0F, 1.0F);
				float f3 = (float)this.counterInWater < 100.0F ? 0.0F : MathHelper.clamp(((float)this.counterInWater - 100.0F) / 500.0F, 0.0F, 1.0F);
				return f2 * 0.6F + f3 * 0.39999998F;
			}
		}
	}

	public boolean areEyesInFluid(ITag<Fluid> tagIn)
	{
		if (blockAccess == null)
			return false;

		Vector3d pos = getEyePos();
		BlockPos blockpos = new BlockPos(pos);
		FluidState ifluidstate = this.blockAccess.getFluidState(blockpos);
		return isFluidTagged(ifluidstate, tagIn) && pos.y < (double)((float)blockpos.getY() + ifluidstate.getLevel() + 0.11111111F);
	}

	public Vector3d getEyePos() {
		Vector3d hmd = mc.vrPlayer.vrdata_room_post.hmd.getPosition();

		if (blockAccess == null)
			return hmd;
		return new Vector3d(hmd.x + blockAccess.getXSize() / 2, hmd.y + blockAccess.getGround(), hmd.z + blockAccess.getZSize() / 2);
	}

	private boolean isFluidTagged(Fluid fluid, ITag<Fluid> tag) {
		// Apparently fluid tags are server side, so we have to hard-code this shit.
		// Thanks Mojang.
		if (tag == FluidTags.WATER) {
			return fluid == Fluids.WATER || fluid == Fluids.FLOWING_WATER;
		} else if (tag == FluidTags.LAVA) {
			return fluid == Fluids.LAVA || fluid == Fluids.FLOWING_LAVA;
		}
		return false;
	}

	private boolean isFluidTagged(FluidState fluidState, ITag<Fluid> tag) {
		return isFluidTagged(fluidState.getFluid(), tag);
	}

	public static class MenuCloudRenderer
	{
		private Minecraft mc;
		private boolean updated = false;
		float partialTicks;
		private int glListClouds = -1;
		private int cloudTickCounterUpdate = 0;
		private double cloudPlayerX = 0.0D;
		private double cloudPlayerY = 0.0D;
		private double cloudPlayerZ = 0.0D;
		private Vector3d color;
		private Vector3d lastColor;

		public MenuCloudRenderer(Minecraft p_i23_1_)
		{
			this.mc = p_i23_1_;
			this.glListClouds = GLUtils.generateDisplayLists(1);
		}

		public void prepareToRender(float partialTicks, Vector3d color)
		{
			this.partialTicks = partialTicks;
			this.lastColor = this.color;
			this.color = color;
		}

		public boolean shouldUpdateGlList(float posY)
		{
			if (!this.updated)
			{
				return true;
			}
			else if (this.mc.tickCounter >= this.cloudTickCounterUpdate + 100)
			{
				return true;
			}
			else if (!this.color.equals(this.lastColor) && this.mc.tickCounter >= this.cloudTickCounterUpdate + 1)
			{
				return true;
			}
			else
			{
				boolean flag = this.cloudPlayerY < 128.0D + (double)(this.mc.gameSettings.ofCloudsHeight * 128.0F);
				boolean flag1 = posY < 128.0D + (double)(this.mc.gameSettings.ofCloudsHeight * 128.0F);
				return flag1 != flag;
			}
		}

		public void startUpdateGlList()
		{
			GL11.glNewList(this.glListClouds, GL11.GL_COMPILE);
		}

		public void endUpdateGlList(float x, float y, float z)
		{
			GL11.glEndList();
			this.cloudTickCounterUpdate = this.mc.tickCounter;
			this.cloudPlayerX = x;
			this.cloudPlayerY = y;
			this.cloudPlayerZ = z;
			this.updated = true;
			GlStateManager.clearCurrentColor();
		}

		public void renderGlList(float x, float y, float z)
		{
			double d3 = (this.mc.tickCounter - this.cloudTickCounterUpdate) + this.partialTicks;
			float f = (float)(x - this.cloudPlayerX + d3 * 0.03D);
			float f1 = (float)(y - this.cloudPlayerY);
			float f2 = (float)(z - this.cloudPlayerZ);
			GlStateManager.pushMatrix();

			GlStateManager.translatef(-f / 12.0F, -f1, -f2 / 12.0F);

			GL12.glCallList(this.glListClouds);
			GlStateManager.popMatrix();
			GlStateManager.clearCurrentColor();
		}

		public void reset()
		{
			this.updated = false;
		}
	}

	public static class MenuFogRenderer {
		private final float[] blackBuffer = new float[4];
		private final float[] buffer = new float[4];
		public float red;
		public float green;
		public float blue;
		private float lastRed = -1.0F;
		private float lastGreen = -1.0F;
		private float lastBlue = -1.0F;
		private int lastWaterFogColor = -1;
		private int waterFogColor = -1;
		private long waterFogUpdateTime = -1L;
		private MenuWorldRenderer menuWorldRenderer;
		private Minecraft mc;

		public MenuFogRenderer(MenuWorldRenderer menuWorldRenderer)
		{
			this.menuWorldRenderer = menuWorldRenderer;
			this.mc = Minecraft.getInstance();
			this.blackBuffer[0] = 0.0F;
			this.blackBuffer[1] = 0.0F;
			this.blackBuffer[2] = 0.0F;
			this.blackBuffer[3] = 1.0F;
		}

		public void updateFogColor()
		{
			//Entity entity = this.mc.getRenderViewEntity();
			//ActiveRenderInfo.getBlockStateAtEntityViewpoint(this.mc.world, entity, partialTicks);
			//IFluidState ifluidstate = ActiveRenderInfo.getFluidStateAtEntityViewpoint(this.mc.world, entity, partialTicks);
			Vector3d eyePos = this.menuWorldRenderer.getEyePos();

			if (this.menuWorldRenderer.areEyesInFluid(FluidTags.WATER))
			{
				this.updateWaterFog(this.menuWorldRenderer.getWorld());
			}
			else if (this.menuWorldRenderer.areEyesInFluid(FluidTags.LAVA))
			{
				this.red = 0.6F;
				this.green = 0.1F;
				this.blue = 0.0F;
				this.waterFogUpdateTime = -1L;
			}
			else
			{
				this.updateSurfaceFog();
				this.waterFogUpdateTime = -1L;
			}

			double d0 = eyePos.y * this.menuWorldRenderer.getWorld().getVoidFogYFactor();

			/*if (entity instanceof EntityLivingBase && ((EntityLivingBase)entity).isPotionActive(MobEffects.BLINDNESS))
			{
				int i = ((EntityLivingBase)entity).getActivePotionEffect(MobEffects.BLINDNESS).getDuration();

				if (i < 20)
				{
					d0 *= (double)(1.0F - (float)i / 20.0F);
				}
				else
				{
					d0 = 0.0D;
				}
			}*/

			if (d0 < 1.0D)
			{
				if (d0 < 0.0D)
				{
					d0 = 0.0D;
				}

				d0 = d0 * d0;
				this.red = (float)((double)this.red * d0);
				this.green = (float)((double)this.green * d0);
				this.blue = (float)((double)this.blue * d0);
			}

			/*if (this.gameRenderer.getBossColorModifier(partialTicks) > 0.0F)
			{
				float f = this.gameRenderer.getBossColorModifier(partialTicks);
				this.red = this.red * (1.0F - f) + this.red * 0.7F * f;
				this.green = this.green * (1.0F - f) + this.green * 0.6F * f;
				this.blue = this.blue * (1.0F - f) + this.blue * 0.6F * f;
			}*/

			if (this.menuWorldRenderer.areEyesInFluid(FluidTags.WATER))
			{
				float f1 = this.menuWorldRenderer.getWaterBrightness();

				float f3 = 1.0F / this.red;

				if (f3 > 1.0F / this.green)
				{
					f3 = 1.0F / this.green;
				}

				if (f3 > 1.0F / this.blue)
				{
					f3 = 1.0F / this.blue;
				}

				this.red = this.red * (1.0F - f1) + this.red * f3 * f1;
				this.green = this.green * (1.0F - f1) + this.green * f3 * f1;
				this.blue = this.blue * (1.0F - f1) + this.blue * f3 * f1;
			}
			/*else if (entity instanceof EntityLivingBase && ((EntityLivingBase)entity).isPotionActive(MobEffects.NIGHT_VISION))
			{
				float f2 = this.gameRenderer.getNightVisionBrightness((EntityLivingBase)entity, partialTicks);
				float f4 = 1.0F / this.red;

				if (f4 > 1.0F / this.green)
				{
					f4 = 1.0F / this.green;
				}

				if (f4 > 1.0F / this.blue)
				{
					f4 = 1.0F / this.blue;
				}

				this.red = this.red * (1.0F - f2) + this.red * f4 * f2;
				this.green = this.green * (1.0F - f2) + this.green * f4 * f2;
				this.blue = this.blue * (1.0F - f2) + this.blue * f4 * f2;
			}*/

			/*if (menuWorldRenderer.areEyesInFluid(FluidTags.WATER))
			{
				Vector3d vec3d = CustomColors.getUnderwaterColor(this.mc.world, this.mc.getRenderViewEntity().posX, this.mc.getRenderViewEntity().posY + 1.0D, this.mc.getRenderViewEntity().posZ);

				if (vec3d != null)
				{
					this.red = (float)vec3d.x;
					this.green = (float)vec3d.y;
					this.blue = (float)vec3d.z;
				}
			}
			else if (menuWorldRenderer.areEyesInFluid(FluidTags.LAVA))
			{
				Vector3d vec3d1 = CustomColors.getUnderlavaColor(this.mc.world, this.mc.getRenderViewEntity().posX, this.mc.getRenderViewEntity().posY + 1.0D, this.mc.getRenderViewEntity().posZ);

				if (vec3d1 != null)
				{
					this.red = (float)vec3d1.x;
					this.green = (float)vec3d1.y;
					this.blue = (float)vec3d1.z;
				}
			}*/

			GlStateManager.clearColor(this.red, this.green, this.blue, 0.0F);
		}

		private void updateSurfaceFog()
		{
			float f = 0.25F + 0.75F * (float)this.menuWorldRenderer.renderDistanceChunks / 32.0F;
			f = 1.0F - (float)Math.pow((double)f, 0.25D);
			Vector3d eyePos = this.menuWorldRenderer.getEyePos();
			Vector3d vec3d = this.menuWorldRenderer.getSkyColor(eyePos);
			//vec3d = CustomColors.getWorldSkyColor(vec3d, worldIn, this.mc.getRenderViewEntity(), partialTicks);
			float f1 = (float)vec3d.x;
			float f2 = (float)vec3d.y;
			float f3 = (float)vec3d.z;
			Vector3d vec3d1 = this.menuWorldRenderer.getFogColor(eyePos);
			//vec3d1 = CustomColors.getWorldFogColor(vec3d1, worldIn, this.mc.getRenderViewEntity(), partialTicks);
			this.red = (float)vec3d1.x;
			this.green = (float)vec3d1.y;
			this.blue = (float)vec3d1.z;

			if (this.menuWorldRenderer.renderDistanceChunks >= 4)
			{
				double d0 = MathHelper.sin(this.menuWorldRenderer.getCelestialAngleRadians()) > 0.0F ? -1.0D : 1.0D;
				Vector3d vec3d2 = new Vector3d(d0, 0.0D, 0.0D);
				float f5 = (float)mc.vrPlayer.vrdata_room_post.hmd.getDirection().dotProduct(vec3d2);

				if (f5 < 0.0F)
				{
					f5 = 0.0F;
				}

				if (f5 > 0.0F)
				{
					float[] afloat = this.menuWorldRenderer.dimensionInfo.func_230492_a_(this.menuWorldRenderer.getCelestialAngle(), 0);

					if (afloat != null)
					{
						f5 = f5 * afloat[3];
						this.red = this.red * (1.0F - f5) + afloat[0] * f5;
						this.green = this.green * (1.0F - f5) + afloat[1] * f5;
						this.blue = this.blue * (1.0F - f5) + afloat[2] * f5;
					}
				}
			}

			this.red += (f1 - this.red) * f;
			this.green += (f2 - this.green) * f;
			this.blue += (f3 - this.blue) * f;
			/*float f6 = worldIn.getRainStrength(partialTicks);

			if (f6 > 0.0F)
			{
				float f4 = 1.0F - f6 * 0.5F;
				float f8 = 1.0F - f6 * 0.4F;
				this.red *= f4;
				this.green *= f4;
				this.blue *= f8;
			}*/

			/*float f7 = worldIn.getThunderStrength(partialTicks);

			if (f7 > 0.0F)
			{
				float f9 = 1.0F - f7 * 0.5F;
				this.red *= f9;
				this.green *= f9;
				this.blue *= f9;
			}*/
		}

		private void updateWaterFog(IWorldReader worldIn)
		{
			long i = Util.milliTime();
			int j = worldIn.getBiome(new BlockPos(this.menuWorldRenderer.getEyePos())).getWaterFogColor();

			if (this.waterFogUpdateTime < 0L)
			{
				this.lastWaterFogColor = j;
				this.waterFogColor = j;
				this.waterFogUpdateTime = i;
			}

			int k = this.lastWaterFogColor >> 16 & 255;
			int l = this.lastWaterFogColor >> 8 & 255;
			int i1 = this.lastWaterFogColor & 255;
			int j1 = this.waterFogColor >> 16 & 255;
			int k1 = this.waterFogColor >> 8 & 255;
			int l1 = this.waterFogColor & 255;
			float f = MathHelper.clamp((float)(i - this.waterFogUpdateTime) / 5000.0F, 0.0F, 1.0F);
			float f1 = (float)j1 + (float)(k - j1) * f;
			float f2 = (float)k1 + (float)(l - k1) * f;
			float f3 = (float)l1 + (float)(i1 - l1) * f;
			this.red = f1 / 255.0F;
			this.green = f2 / 255.0F;
			this.blue = f3 / 255.0F;

			if (this.lastWaterFogColor != j)
			{
				this.lastWaterFogColor = j;
				this.waterFogColor = MathHelper.floor(f1) << 16 | MathHelper.floor(f2) << 8 | MathHelper.floor(f3);
				this.waterFogUpdateTime = i;
			}
		}

		public void setupFog(int startCoords) {
			//this.gameRenderer.fogStandard = false;
			//Entity entity = this.mc.getRenderViewEntity();
			this.applyFog(false);
			float farPlane = this.menuWorldRenderer.renderDistance;
			Vector3d eyePos = this.menuWorldRenderer.getEyePos();

			GlStateManager.normal3f(0.0F, -1.0F, 0.0F);
			GlStateManager.color4f(1.0F, 1.0F, 1.0F, 1.0F);
			//IFluidState ifluidstate = ActiveRenderInfo.getFluidStateAtEntityViewpoint(this.mc.world, entity, partialTicks);
			float f = -1.0F;

			/*if (Reflector.ForgeHooksClient_getFogDensity.exists())
			{
				f = Reflector.callFloat(Reflector.ForgeHooksClient_getFogDensity, this, entity, ifluidstate, partialTicks, 0.1F);
			}*/

			if (f >= 0.0F)
			{
				GlStateManager.fogDensity(f);
			}
			/*else if (entity instanceof EntityLivingBase && ((EntityLivingBase)entity).isPotionActive(MobEffects.BLINDNESS))
			{
				float f3 = 5.0F;
				int i = ((EntityLivingBase)entity).getActivePotionEffect(MobEffects.BLINDNESS).getDuration();

				if (i < 20)
				{
					f3 = 5.0F + (this.gameRenderer.getFarPlaneDistance() - 5.0F) * (1.0F - (float)i / 20.0F);
				}

				GlStateManager.fogMode(GlStateManager.FogMode.LINEAR);

				if (startCoords == -1)
				{
					GlStateManager.fogStart(0.0F);
					GlStateManager.fogEnd(f3 * 0.8F);
				}
				else
				{
					GlStateManager.fogStart(f3 * 0.25F);
					GlStateManager.fogEnd(f3);
				}

				if (GL.getCapabilities().GL_NV_fog_distance && Config.isFogFancy())
				{
					GlStateManager.fogi(34138, 34139);
				}
			}*/
			else if (this.menuWorldRenderer.areEyesInFluid(FluidTags.WATER))
			{
				RenderSystem.fogMode(GlStateManager.FogMode.EXP2);

				float f1 = 0.05F - this.menuWorldRenderer.getWaterBrightness() * this.menuWorldRenderer.getWaterBrightness() * 0.03F;
				Biome biome = this.menuWorldRenderer.getWorld().getBiome(new BlockPos(eyePos));

				if (biome.getCategory() == Biome.Category.SWAMP)
				{
					f1 += 0.005F;
				}

				GlStateManager.fogDensity(f1);
			}
			else if (this.menuWorldRenderer.areEyesInFluid(FluidTags.LAVA))
			{
				RenderSystem.fogMode(GlStateManager.FogMode.EXP);
				GlStateManager.fogDensity(2.0F);
			}
			else
			{
				float f2 = farPlane;
				//this.gameRenderer.fogStandard = true;
				RenderSystem.fogMode(GlStateManager.FogMode.LINEAR);

				if (startCoords == -1)
				{
					GlStateManager.fogStart(0.0F);
					GlStateManager.fogEnd(f2);
				}
				else
				{
					GlStateManager.fogStart(f2 * Config.getFogStart());
					GlStateManager.fogEnd(f2);
				}

				if (GL.getCapabilities().GL_NV_fog_distance)
				{
					/*if (Config.isFogFancy())
					{
						GlStateManager.fogi(NVFogDistance.GL_FOG_DISTANCE_MODE_NV, NVFogDistance.GL_EYE_RADIAL_NV);
					}

					if (Config.isFogFast())
					{
						GlStateManager.fogi(NVFogDistance.GL_FOG_DISTANCE_MODE_NV, NVFogDistance.GL_EYE_PLANE_ABSOLUTE_NV);
					}*/

					// Just do fancy fog, only works on NVIDIA though. Have to use shaders on other GPUs.
					GlStateManager.fogi(NVFogDistance.GL_FOG_DISTANCE_MODE_NV, NVFogDistance.GL_EYE_RADIAL_NV);
				}

				if (this.menuWorldRenderer.dimensionInfo.func_230493_a_((int)eyePos.x, (int)eyePos.z) /*|| this.mc.ingameGUI.getBossOverlay().shouldCreateFog()*/) // doesXZShowFog
				{
					GlStateManager.fogStart(f2 * 0.05F);
					GlStateManager.fogEnd(f2);
				}

			/*if (Reflector.ForgeHooksClient_onFogRender.exists())
			{
				Reflector.callVoid(Reflector.ForgeHooksClient_onFogRender, this, entity, ifluidstate, partialTicks, startCoords, f2);
			}*/
			}

			GlStateManager.enableColorMaterial();
			GlStateManager.enableFog();
			GlStateManager.colorMaterial(GL11.GL_FRONT, GL11.GL_AMBIENT);
		}

		public void applyFog(boolean blackIn)
		{
			if (blackIn)
			{
				GlStateManager.fog(GL11.GL_FOG_COLOR, this.blackBuffer);
			}
			else
			{
				GlStateManager.fog(GL11.GL_FOG_COLOR, this.getFogBuffer());
			}
		}

		private float[] getFogBuffer()
		{
			if (this.lastRed != this.red || this.lastGreen != this.green || this.lastBlue != this.blue)
			{
				this.buffer[0] = this.red;
				this.buffer[1] = this.green;
				this.buffer[2] = this.blue;
				this.buffer[3] = 1.0F;
				this.lastRed = this.red;
				this.lastGreen = this.green;
				this.lastBlue = this.blue;

				if (Config.isShaders())
				{
					Shaders.setFogColor(this.red, this.green, this.blue);
				}
			}

			return this.buffer;
		}
	}

	private static class FluidStateWrapper extends FluidState {
		private final FluidState fluidState;

		@SuppressWarnings("unchecked")
		public FluidStateWrapper(FluidState fluidState) {
			super(fluidState.getFluid(), fluidState.getValues(), (MapCodec<FluidState>)MCReflection.StateHolder_mapCodec.get(fluidState));

			this.fluidState = fluidState;
		}

		@Override
		public boolean isTagged(ITag<Fluid> tagIn) {
			// Yeah I know this is super dirty, blame Mojang for making FluidTags server-side
			if (tagIn == FluidTags.WATER) {
				return this.getFluid() == Fluids.WATER || this.getFluid() == Fluids.FLOWING_WATER;
			} else if (tagIn == FluidTags.LAVA) {
				return this.getFluid() == Fluids.LAVA || this.getFluid() == Fluids.FLOWING_LAVA;
			}
			return fluidState.isTagged(tagIn);
		}
	}
}
