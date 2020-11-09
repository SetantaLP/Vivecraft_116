package org.vivecraft.provider;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import net.minecraft.util.text.TranslationTextComponent;
import net.optifine.Lang;
import net.optifine.reflect.Reflector;
import org.apache.commons.lang3.ArrayUtils;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.openvr.*;

import static org.lwjgl.openvr.VR.*;
import static org.lwjgl.openvr.VRSystem.*;
import static org.lwjgl.openvr.VRCompositor.*;
import static org.lwjgl.openvr.VRInput.*;
import static org.lwjgl.openvr.VRApplications.*;
import static org.lwjgl.openvr.VRSettings.*;
import static org.lwjgl.openvr.VRRenderModels.*;
import static org.lwjgl.openvr.VRChaperone.*;
import static org.lwjgl.system.MemoryUtil.*;

import static org.vivecraft.utils.external.OpenComposite.*;
import static org.vivecraft.utils.external.VROCSystem.*;

import org.vivecraft.api.VRData;
import org.vivecraft.api.Vec3History;
import org.vivecraft.control.ControllerType;
import org.vivecraft.control.HandedKeyBinding;
import org.vivecraft.control.HapticScheduler;
import org.vivecraft.control.InputSimulator;
import org.vivecraft.control.TrackedController;
import org.vivecraft.control.TrackpadSwipeSampler;
import org.vivecraft.control.VRInputAction;
import org.vivecraft.control.VRInputActionSet;
import org.vivecraft.control.VivecraftMovementInput;
import org.vivecraft.gameplay.screenhandlers.GuiHandler;
import org.vivecraft.gameplay.screenhandlers.KeyboardHandler;
import org.vivecraft.gameplay.screenhandlers.RadialHandler;
import org.vivecraft.menuworlds.MenuWorldExporter;
import org.vivecraft.reflection.MCReflection;
import org.vivecraft.render.RenderPass;
import org.vivecraft.settings.VRHotkeys;
import org.vivecraft.settings.VRSettings;
import org.vivecraft.utils.LangHelper;
import org.vivecraft.utils.Utils;
import org.vivecraft.utils.external.*;
import org.vivecraft.utils.math.Angle;
import org.vivecraft.utils.math.Matrix4f;
import org.vivecraft.utils.math.Quaternion;
import org.vivecraft.utils.math.Vector2;
import org.vivecraft.utils.math.Vector3;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.jna.NativeLibrary;

import net.minecraft.block.TorchBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.WinGameScreen;
import net.minecraft.client.gui.screen.inventory.ContainerScreen;
import net.minecraft.client.main.Main;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.world.World;

public class MCOpenVR 
{
	static String initStatus;
	private static boolean initialized;
	private static boolean inputInitialized;

	static Minecraft mc;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private static IntBuffer hmdErrorStoreBuf;

	private static TrackedDevicePose.Buffer hmdTrackedDevicePoses;

	private static Matrix4f[] poseMatrices;
	private static Vector3d[] deviceVelocity;

//	private LongBuffer oHandle = BufferUtils.createLongBuffer(1);

	// position/orientation of headset and eye offsets
	private static final Matrix4f hmdPose = new Matrix4f();
	public static final Matrix4f hmdRotation = new Matrix4f();
	
	static Matrix4f hmdPoseLeftEye = new Matrix4f();
	static Matrix4f hmdPoseRightEye = new Matrix4f();
	static boolean initSuccess = false, flipEyes = false;

//	private static IntBuffer hmdDisplayFrequency;

	private static float vsyncToPhotons;
//	private static double timePerFrame, frameCountRun;
//	private static long frameCount;

	public static Vec3History hmdHistory = new Vec3History();
	public static Vec3History hmdPivotHistory = new Vec3History();
	public static Vec3History[] controllerHistory = new Vec3History[] { new Vec3History(), new Vec3History()};
	public static Vec3History[] controllerForwardHistory = new Vec3History[] { new Vec3History(), new Vec3History()};
	public static Vec3History[] controllerUpHistory = new Vec3History[] { new Vec3History(), new Vec3History()};

	//Covid-19 Quarantine Helper Code
	private static final Matrix4f Neutral_HMD = new Matrix4f(1,0,0,0f,
													 		0,1,0,1.62f,
														 	0,0,1,0f,
														 	0,0,0,1);
	
	private static final Matrix4f TPose_Left =  new Matrix4f(1,0,0,.25f,
													 	   	0,1,0,1.62f,
														 	0,0,1,.25f,
														 	0,0,0,1);
	
	private static final Matrix4f TPose_Right =  new Matrix4f(1,0,0,.75f,
													 		0,1,0,1.62f,
														 	0,0,1,.75f,
														 	0,0,0,1);
	private static boolean TPose = false;
	//
	
	
	/**
	 * Do not make this public and reference it! Call the {@link #getHardwareType()} method instead!
	 */
	private static HardwareType detectedHardware = HardwareType.VIVE;

	// TextureIDs of framebuffers for each eye
	private int LeftEyeTextureId;

	final static VRTextureBounds texBounds = VRTextureBounds.create();
	final static Texture texType0 = Texture.create();
	final static Texture texType1 = Texture.create();
	
	// aiming

	static Vector3d[] aimSource = new Vector3d[3];

	public static Vector3 offset=new Vector3(0,0,0);

	static boolean[] controllerTracking = new boolean[3];
	public static TrackedController[] controllers = new TrackedController[2];

	// Controllers
	public static final int RIGHT_CONTROLLER = 0;
	public static final int LEFT_CONTROLLER = 1;
	public static final int THIRD_CONTROLLER = 2;
	private static Matrix4f[] controllerPose = new Matrix4f[3];
	private static Matrix4f[] controllerRotation = new Matrix4f[3];
	private static Matrix4f[] handRotation = new Matrix4f[3];
	public static int[] controllerDeviceIndex = new int[3];

	private static Queue<VREvent> vrEvents = new LinkedList<>();

	public static boolean hudPopup = true;

	static boolean headIsTracking;

	private static int moveModeSwitchCount = 0;

	public static boolean isWalkingAbout;
	private static boolean isFreeRotate;
	private static ControllerType walkaboutController;
	private static ControllerType freeRotateController;
	private static float walkaboutYawStart;
	private static float hmdForwardYaw;
	public static boolean ignorePressesNextFrame = false;
	
	private static Map<String, VRInputAction> inputActions = new HashMap<>();
	private static Map<String, VRInputAction> inputActionsByKeyBinding = new HashMap<>();
	private static Map<VRInputActionSet, Long> actionSetHandles = new EnumMap<>(VRInputActionSet.class);

	private static long leftPoseHandle;
	private static long rightPoseHandle;
	private static long leftHapticHandle;
	private static long rightHapticHandle;
	private static long externalCameraPoseHandle;

	private static long leftControllerHandle;
	private static long rightControllerHandle;

	private static Map<String, TrackpadSwipeSampler> trackpadSwipeSamplers = new HashMap<>();
	private static Map<String, Boolean> axisUseTracker = new HashMap<>();

	private static InputPoseActionData poseData;
	private static InputOriginInfo originInfo;
	private static VRActiveActionSet.Buffer activeActionSetsReference;

	private static HapticScheduler hapticScheduler;

	public static boolean mrMovingCamActive;
	public static Vector3d mrControllerPos = Vector3d.ZERO;
	public static float mrControllerPitch;
	public static float mrControllerYaw;
	public static float mrControllerRoll;

	private static Set<KeyBinding> keyBindingSet;
	// Vivecraft bindings included
	private static Set<KeyBinding> vanillaBindingSet;
	
	//hmd sampling
	public static int hmdAvgLength = 90;
	public static LinkedList<Vector3d> hmdPosSamples = new LinkedList<Vector3d>();
	public static LinkedList<Float> hmdYawSamples = new LinkedList<Float>();
	private static float hmdYawTotal;
	private static float hmdYawLast;
	private static boolean trigger;
	//
	
	public String getName() {
		return "OpenVR";
	}

	public String getID() {
		return "openvr";
	}

	public static final KeyBinding keyHotbarNext = new KeyBinding("vivecraft.key.hotbarNext", GLFW.GLFW_KEY_PAGE_UP, "key.categories.inventory");
	public static final KeyBinding keyHotbarPrev = new KeyBinding("vivecraft.key.hotbarPrev", GLFW.GLFW_KEY_PAGE_DOWN, "key.categories.inventory");
	public static final KeyBinding keyHotbarScroll = new KeyBinding("vivecraft.key.hotbarScroll", GLFW.GLFW_KEY_UNKNOWN, "key.categories.inventory"); // dummy binding
	public static final KeyBinding keyHotbarSwipeX = new KeyBinding("vivecraft.key.hotbarSwipeX", GLFW.GLFW_KEY_UNKNOWN, "key.categories.inventory"); // dummy binding
	public static final KeyBinding keyHotbarSwipeY = new KeyBinding("vivecraft.key.hotbarSwipeY", GLFW.GLFW_KEY_UNKNOWN, "key.categories.inventory"); // dummy binding
	public static final KeyBinding keyRotateLeft = new KeyBinding("vivecraft.key.rotateLeft", GLFW.GLFW_KEY_LEFT, "key.categories.movement");
	public static final KeyBinding keyRotateRight = new KeyBinding("vivecraft.key.rotateRight", GLFW.GLFW_KEY_RIGHT, "key.categories.movement");
	public static final KeyBinding keyRotateAxis = new KeyBinding("vivecraft.key.rotateAxis", GLFW.GLFW_KEY_UNKNOWN, "key.categories.movement"); // dummy binding
	public static final KeyBinding keyWalkabout = new KeyBinding("vivecraft.key.walkabout", GLFW.GLFW_KEY_END, "key.categories.movement");
	public static final KeyBinding keyRotateFree = new KeyBinding("vivecraft.key.rotateFree", GLFW.GLFW_KEY_HOME, "key.categories.movement");
	public static final KeyBinding keyTeleport = new KeyBinding("vivecraft.key.teleport", GLFW.GLFW_KEY_UNKNOWN, "key.categories.movement");
	public static final KeyBinding keyTeleportFallback = new KeyBinding("vivecraft.key.teleportFallback", GLFW.GLFW_KEY_UNKNOWN, "key.categories.movement");
	public static final KeyBinding keyFreeMoveRotate = new KeyBinding("vivecraft.key.freeMoveRotate", GLFW.GLFW_KEY_UNKNOWN, "key.categories.movement"); // dummy binding
	public static final KeyBinding keyFreeMoveStrafe = new KeyBinding("vivecraft.key.freeMoveStrafe", GLFW.GLFW_KEY_UNKNOWN, "key.categories.movement"); // dummy binding
	public static final KeyBinding keyToggleMovement = new KeyBinding("vivecraft.key.toggleMovement", GLFW.GLFW_KEY_UNKNOWN, "key.categories.movement");
	public static final KeyBinding keyQuickTorch = new KeyBinding("vivecraft.key.quickTorch", GLFW.GLFW_KEY_INSERT, "key.categories.gameplay");
	public static final KeyBinding keyMenuButton = new KeyBinding("vivecraft.key.ingameMenuButton", GLFW.GLFW_KEY_UNKNOWN, "key.categories.ui");
	public static final KeyBinding keyExportWorld = new KeyBinding("vivecraft.key.exportWorld", GLFW.GLFW_KEY_UNKNOWN, "key.categories.misc");
	public static final KeyBinding keyRadialMenu = new KeyBinding("vivecraft.key.radialMenu", GLFW.GLFW_KEY_UNKNOWN, "key.categories.ui");
	public static final KeyBinding keySwapMirrorView = new KeyBinding("vivecraft.key.swapMirrorView", GLFW.GLFW_KEY_UNKNOWN, "key.categories.misc");
	public static final KeyBinding keyToggleKeyboard = new KeyBinding("vivecraft.key.toggleKeyboard", GLFW.GLFW_KEY_UNKNOWN, "key.categories.ui");
	public static final KeyBinding keyMoveThirdPersonCam = new KeyBinding("vivecraft.key.moveThirdPersonCam", GLFW.GLFW_KEY_UNKNOWN, "key.categories.misc");
	public static final KeyBinding keyTogglePlayerList = new KeyBinding("vivecraft.key.togglePlayerList", GLFW.GLFW_KEY_UNKNOWN, "key.categories.multiplayer");
	public static final KeyBinding keyToggleHandheldCam = new KeyBinding("vivecraft.key.toggleHandheldCam", GLFW.GLFW_KEY_UNKNOWN, "key.categories.misc");
	public static final HandedKeyBinding keyTrackpadTouch = new HandedKeyBinding("vivecraft.key.trackpadTouch", GLFW.GLFW_KEY_UNKNOWN, "key.categories.misc"); // used for swipe sampler
	public static final HandedKeyBinding keyVRInteract = new HandedKeyBinding("vivecraft.key.vrInteract", GLFW.GLFW_KEY_UNKNOWN,"key.categories.gameplay");
	public static final HandedKeyBinding keyClimbeyGrab = new HandedKeyBinding("vivecraft.key.climbeyGrab", GLFW.GLFW_KEY_UNKNOWN,"vivecraft.key.category.climbey");
	public static final HandedKeyBinding keyClimbeyJump = new HandedKeyBinding("vivecraft.key.climbeyJump", GLFW.GLFW_KEY_UNKNOWN,"vivecraft.key.category.climbey");


	public MCOpenVR()
	{
		super();

		for (int c=0;c<3;c++)
		{
			aimSource[c] = new Vector3d(0.0D, 0.0D, 0.0D);
			controllerPose[c] = new Matrix4f();
			controllerRotation[c] = new Matrix4f();
			handRotation[c] = new Matrix4f();
			controllerDeviceIndex[c] = -1;
			
			poseData = InputPoseActionData.create();

			originInfo = InputOriginInfo.create();
		}
	}

	private static boolean tried;

	//probably not necessary, since LWJGL handles the OpenVR natives
//	private static void unpackPlatformNatives() {
//		String osname = System.getProperty("os.name").toLowerCase();
//		String osarch = System.getProperty("os.arch").toLowerCase();
//
//		String osFolder = "win";
//
//		if (osname.contains("linux")) {
//			osFolder = "linux";
//		} else if (osname.contains("mac")) {
//			osFolder = "osx";
//		}
//
//		if (!osname.contains("mac")) {
//			if (osarch.contains("64")) {
//				osFolder += "64";
//			} else {
//				osFolder += "32";
//			}
//		}
//
//		try {
//			Utils.unpackNatives(osFolder);
//		} catch (Exception e) {
//			System.out.println("Native path not found");
//			return;
//		}
//
//		String openVRPath = new File("openvr/" + osFolder).getAbsolutePath();
//		System.out.println("Adding OpenVR search path: " + openVRPath);
//		NativeLibrary.addSearchPath("openvr_api", openVRPath);
//	}
	
	public static boolean init()  throws Exception
	{
		if ( initialized )
			return true;

		if ( tried )
			return initialized;

		tried = true;

		mc = Minecraft.getInstance();

		//TODO: most likely not necessary, since lwjgl handles the natives
		//unpackPlatformNatives();
		
		if(!VR_IsHmdPresent()){
			initStatus =  "vivecraft.messages.nosteamvr";
			return false;
		}

		try {
			initializeJOpenVR();
			initOpenVRCompositor() ;
			initOpenVRSettings();
			initOpenVRRenderModels();
			initOpenVRChaperone();
			initOpenVRApplications();
			initOpenVRInput();
			initOpenComposite();
		} catch (Exception e) {
			e.printStackTrace();
			initSuccess = false;
			initStatus = e.getLocalizedMessage();
			return false;
		}
		
		if (OpenVR.VRInput == null) {
			System.out.println("Controller input not available. Forcing seated mode.");
			mc.vrSettings.seated = true;
		}
		
		System.out.println( "OpenVR initialized & VR connected." );

		controllers[RIGHT_CONTROLLER] = new TrackedController(ControllerType.RIGHT);
		controllers[LEFT_CONTROLLER] = new TrackedController(ControllerType.LEFT);

		deviceVelocity = new Vector3d[k_unMaxTrackedDeviceCount];

		for(int i=0;i<poseMatrices.length;i++)
		{
			poseMatrices[i] = new Matrix4f();
			deviceVelocity[i] = new Vector3d(0,0,0);
		}

		hapticScheduler = new HapticScheduler();

		initialized = true;

		if(Main.katvr){
			try {
				System.out.println( "Waiting for KATVR...." );
				Utils.unpackNatives("katvr");
				NativeLibrary.addSearchPath(jkatvr.KATVR_LIBRARY_NAME, new File("openvr/katvr").getAbsolutePath());
				jkatvr.Init(1);
				jkatvr.Launch();
				if(jkatvr.CheckForLaunch()){
					System.out.println( "KATVR Loaded" );
				}else {
					System.out.println( "KATVR Failed to load" );
				}

			} catch (Exception e) {
				System.out.println( "KATVR crashed: " + e.getMessage() );
			}
		}

		if(Main.infinadeck){
			try {
				System.out.println( "Waiting for Infinadeck...." );
				Utils.unpackNatives("infinadeck");
				NativeLibrary.addSearchPath(jinfinadeck.INFINADECK_LIBRARY_NAME, new File("openvr/infinadeck").getAbsolutePath());

				if(jinfinadeck.InitConnection()){
					jinfinadeck.CheckConnection();
					System.out.println( "Infinadeck Loaded" );
				}else {
					System.out.println( "Infinadeck Failed to load" );
				}
				
			} catch (Exception e) {
				System.out.println( "Infinadeck crashed: " + e.getMessage() );
			}
		}
		return true;
	}

	public static boolean isError(){
		return hmdErrorStoreBuf.get(0) != 0;
	}

	public static int getError(){
		return hmdErrorStoreBuf.get(0);
	}

	public static Set<KeyBinding> getKeyBindings() {
		if (keyBindingSet == null) {
			keyBindingSet = new LinkedHashSet<>();
			keyBindingSet.add(keyRotateLeft);
			keyBindingSet.add(keyRotateRight);
			keyBindingSet.add(keyRotateAxis);
			keyBindingSet.add(keyRotateFree);
			keyBindingSet.add(keyWalkabout);
			keyBindingSet.add(keyTeleport);
			keyBindingSet.add(keyTeleportFallback);
			keyBindingSet.add(keyFreeMoveRotate);
			keyBindingSet.add(keyFreeMoveStrafe);
			keyBindingSet.add(keyToggleMovement);
			keyBindingSet.add(keyQuickTorch);
			keyBindingSet.add(keyHotbarNext);
			keyBindingSet.add(keyHotbarPrev);
			keyBindingSet.add(keyHotbarScroll);
			keyBindingSet.add(keyHotbarSwipeX);
			keyBindingSet.add(keyHotbarSwipeY);
			keyBindingSet.add(keyMenuButton);
			keyBindingSet.add(keyRadialMenu);
			keyBindingSet.add(keyVRInteract);
			keyBindingSet.add(keySwapMirrorView);
			keyBindingSet.add(keyExportWorld);
			keyBindingSet.add(keyToggleKeyboard);
			keyBindingSet.add(keyMoveThirdPersonCam);
			keyBindingSet.add(keyTogglePlayerList);
			keyBindingSet.add(keyToggleHandheldCam);
			keyBindingSet.add(keyTrackpadTouch);
			keyBindingSet.add(GuiHandler.keyLeftClick);
			keyBindingSet.add(GuiHandler.keyRightClick);
			keyBindingSet.add(GuiHandler.keyMiddleClick);
			keyBindingSet.add(GuiHandler.keyShift);
			keyBindingSet.add(GuiHandler.keyCtrl);
			keyBindingSet.add(GuiHandler.keyAlt);
			keyBindingSet.add(GuiHandler.keyScrollUp);
			keyBindingSet.add(GuiHandler.keyScrollDown);
			keyBindingSet.add(GuiHandler.keyScrollAxis);
			keyBindingSet.add(GuiHandler.keyKeyboardClick);
			keyBindingSet.add(GuiHandler.keyKeyboardShift);
			keyBindingSet.add(keyClimbeyGrab);
			keyBindingSet.add(keyClimbeyJump);
		}

		return keyBindingSet;
	}

	@SuppressWarnings("unchecked")
	public static KeyBinding[] initializeBindings(KeyBinding[] keyBindings) {
		for (KeyBinding keyBinding : getKeyBindings())
			keyBindings = ArrayUtils.add(keyBindings, keyBinding);

		// Copy the bindings array here so we know which ones are from mods
		setVanillaBindings(keyBindings);

		Map<String, Integer> co = (Map<String, Integer>)MCReflection.KeyBinding_CATEGORY_ORDER.get(null);
		co.put("vivecraft.key.category.gui", 8);
		co.put("vivecraft.key.category.climbey", 9);
		co.put("vivecraft.key.category.keyboard", 10);

		return keyBindings;
	}

	private static void installApplicationManifest(boolean force) {
		File file = new File("openvr/vivecraft.vrmanifest");
		Utils.loadAssetToFile("vivecraft.vrmanifest", file, true);

		File customFile = new File("openvr/custom.vrmanifest");
		if (customFile.exists())
			file = customFile;

		if (OpenVR.VRApplications != null) {
			String appKey;
			try {
				Map map = new Gson().fromJson(new FileReader(file), Map.class);
				appKey = ((Map)((List)map.get("applications")).get(0)).get("app_key").toString();
			} catch (Exception e) {
				System.out.println("Error reading appkey from manifest");
				e.printStackTrace();
				return;
			}

			System.out.println("Appkey: " + appKey);

			if (!force && VRApplications_IsApplicationInstalled(appKey)) {
				System.out.println("Application manifest already installed");
			} else {
				// 0 = Permanent manifest which will show up in the library
				// 1 = Temporary manifest which will only show up in bindings until SteamVR is restarted
				int error = VRApplications_AddApplicationManifest(file.getAbsolutePath(), true);
				if (error != 0) {
					System.out.println("Failed to install application manifest: " + VRApplications_GetApplicationsErrorNameFromEnum(error));
					return;
				} else {
					System.out.println("Application manifest installed successfully");
				}
			}

			// OpenVR doc says pid = 0 will use the calling process, but it actually doesn't, so we
			// have to use this dumb hack that *probably* works on all relevant platforms.
			// TODO: When Minecraft one day requires Java 9+, we can use ProcessHandle.current().pid()
			int pid;
			try {
				String runtimeName = ManagementFactory.getRuntimeMXBean().getName();
				pid = Integer.parseInt(runtimeName.split("@")[0]);
			} catch (Exception e) {
				System.out.println("Error getting process id");
				e.printStackTrace();
				return;
			}

			int error = VRApplications_IdentifyApplication(pid, appKey);
			if (error != 0) {
				System.out.println("Failed to identify application: " + VRApplications_GetApplicationsErrorNameFromEnum(error));
			} else {
				System.out.println("Application identified successfully");
			}
		}
	}

	private static class ActionParams {
		final String requirement;
		final String type;
		final VRInputActionSet actionSetOverride;

		ActionParams(String requirement, String type, VRInputActionSet actionSetOverride) {
			this.requirement = requirement;
			this.type = type;
			this.actionSetOverride = actionSetOverride;
		}
	}

	public static void initInputAndApplication() {
		populateInputActions();
		if (OpenVR.VRInput == null) return;
		generateActionManifest();
		loadActionManifest();
		loadActionHandles();
		installApplicationManifest(false);
		inputInitialized = true;
	}

	private static void populateInputActions() {
		Map<String, ActionParams> actionParams = getSpecialActionParams();
		for (final KeyBinding keyBinding : mc.gameSettings.keyBindings) {
			ActionParams params = actionParams.getOrDefault(keyBinding.getKeyDescription(), new ActionParams("optional", "boolean", null));
			VRInputAction action = new VRInputAction(keyBinding, params.requirement, params.type, params.actionSetOverride);
			inputActions.put(action.name, action);
		}
		for (VRInputAction action : inputActions.values()) {
			inputActionsByKeyBinding.put(action.keyBinding.getKeyDescription(), action);
		}

		getInputAction(MCOpenVR.keyVRInteract).setPriority(5).setEnabled(false);
		getInputAction(MCOpenVR.keyClimbeyGrab).setPriority(10).setEnabled(false);
		//getInputAction(MCOpenVR.keyClimbeyJump).setPriority(10).setEnabled(false);
		getInputAction(MCOpenVR.keyClimbeyJump).setEnabled(false);
		getInputAction(GuiHandler.keyKeyboardClick).setPriority(50);
		getInputAction(GuiHandler.keyKeyboardShift).setPriority(50);
	}

	// This is for bindings with specific requirement/type params, anything not listed will default to optional and boolean
	// See OpenVR docs for valid values: https://github.com/ValveSoftware/openvr/wiki/Action-manifest#actions
	private static Map<String, ActionParams> getSpecialActionParams() {
		Map<String, ActionParams> map = new HashMap<>();

		addActionParams(map, mc.gameSettings.keyBindForward, "optional", "vector1", null);
		addActionParams(map, mc.gameSettings.keyBindBack, "optional", "vector1", null);
		addActionParams(map, mc.gameSettings.keyBindLeft, "optional", "vector1", null);
		addActionParams(map, mc.gameSettings.keyBindRight, "optional", "vector1", null);
		addActionParams(map, mc.gameSettings.keyBindInventory, "suggested", "boolean", VRInputActionSet.GLOBAL);
		addActionParams(map, mc.gameSettings.keyBindAttack, "suggested", "boolean", null);
		addActionParams(map, mc.gameSettings.keyBindUseItem, "suggested", "boolean", null);
		addActionParams(map, mc.gameSettings.keyBindChat, "optional", "boolean", VRInputActionSet.GLOBAL);
		addActionParams(map, MCOpenVR.keyHotbarScroll, "optional", "vector2", null);
		addActionParams(map, MCOpenVR.keyHotbarSwipeX, "optional", "vector2", null);
		addActionParams(map, MCOpenVR.keyHotbarSwipeY, "optional", "vector2", null);
		addActionParams(map, MCOpenVR.keyMenuButton, "suggested", "boolean", VRInputActionSet.GLOBAL);
		addActionParams(map, MCOpenVR.keyTeleportFallback, "suggested", "vector1", null);
		addActionParams(map, MCOpenVR.keyFreeMoveRotate, "optional", "vector2", null);
		addActionParams(map, MCOpenVR.keyFreeMoveStrafe, "optional", "vector2", null);
		addActionParams(map, MCOpenVR.keyRotateLeft, "optional", "vector1", null);
		addActionParams(map, MCOpenVR.keyRotateRight, "optional", "vector1", null);
		addActionParams(map, MCOpenVR.keyRotateAxis, "optional", "vector2", null);
		addActionParams(map, MCOpenVR.keyRadialMenu, "suggested", "boolean", null);
		addActionParams(map, MCOpenVR.keySwapMirrorView, "optional", "boolean", VRInputActionSet.GLOBAL);
		addActionParams(map, MCOpenVR.keyToggleKeyboard, "optional", "boolean", VRInputActionSet.GLOBAL);
		addActionParams(map, MCOpenVR.keyMoveThirdPersonCam, "optional", "boolean", VRInputActionSet.GLOBAL);
		addActionParams(map, MCOpenVR.keyToggleHandheldCam, "optional", "boolean", VRInputActionSet.GLOBAL);
		addActionParams(map, MCOpenVR.keyTrackpadTouch, "optional", "boolean", VRInputActionSet.TECHNICAL);
		addActionParams(map, MCOpenVR.keyVRInteract, "suggested", "boolean", VRInputActionSet.CONTEXTUAL);
		addActionParams(map, MCOpenVR.keyClimbeyGrab, "suggested", "boolean", null);
		addActionParams(map, MCOpenVR.keyClimbeyJump, "suggested", "boolean", null);
		addActionParams(map, GuiHandler.keyLeftClick, "suggested", "boolean", null);
		addActionParams(map, GuiHandler.keyScrollAxis, "optional", "vector2", null);
		addActionParams(map, GuiHandler.keyRightClick, "suggested", "boolean", null);
		addActionParams(map, GuiHandler.keyShift, "suggested", "boolean", null);
		addActionParams(map, GuiHandler.keyKeyboardClick, "suggested", "boolean", null);
		addActionParams(map, GuiHandler.keyKeyboardShift, "suggested", "boolean", null);

		File file = new File("customactionsets.txt");
		if (file.exists()) {
			System.out.println("Loading custom action set definitions...");
			try (BufferedReader br = new BufferedReader(new FileReader(file))) {
				String line;
				while ((line = br.readLine()) != null) {
					String[] tokens = line.split(":", 2);
					if (tokens.length < 2) {
						System.out.println("Invalid tokens: " + line);
						continue;
					}

					KeyBinding keyBinding = findKeyBinding(tokens[0]);
					if (keyBinding == null) {
						System.out.println("Unknown key binding: " + tokens[0]);
						continue;
					}
					if (getKeyBindings().contains(keyBinding)) {
						System.out.println("NO! Don't touch Vivecraft bindings!");
						continue;
					}

					VRInputActionSet actionSet = null;
					switch (tokens[1].toLowerCase()) {
						case "ingame":
							actionSet = VRInputActionSet.INGAME;
							break;
						case "gui":
							actionSet = VRInputActionSet.GUI;
							break;
						case "global":
							actionSet = VRInputActionSet.GLOBAL;
							break;
					}
					if (actionSet == null) {
						System.out.println("Unknown action set: " + tokens[1]);
						continue;
					}

					addActionParams(map, keyBinding, "optional", "boolean", actionSet);
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

		return map;
	}

	private static void addActionParams(Map<String, ActionParams> map, KeyBinding keyBinding, String requirement, String type, VRInputActionSet actionSetOverride) {
		ActionParams params = new ActionParams(requirement, type, actionSetOverride);
		map.put(keyBinding.getKeyDescription(), params);
	}

	private static KeyBinding findKeyBinding(String name) {
		return Arrays.stream(mc.gameSettings.keyBindings).filter(kb -> name.equals(kb.getKeyDescription())).findFirst().orElse(null);
	}

	public static final String ACTION_LEFT_HAND = "/actions/global/in/lefthand";
	public static final String ACTION_RIGHT_HAND = "/actions/global/in/righthand";
	public static final String ACTION_LEFT_HAPTIC = "/actions/global/out/lefthaptic";
	public static final String ACTION_RIGHT_HAPTIC = "/actions/global/out/righthaptic";
	public static final String ACTION_EXTERNAL_CAMERA = "/actions/mixedreality/in/externalcamera";

	private static void generateActionManifest() {
		Map<String, Object> jsonMap = new HashMap<>();

		List<Map<String, Object>> actionSets = new ArrayList<>();
		for (VRInputActionSet actionSet : VRInputActionSet.values()) {
			if (actionSet == VRInputActionSet.MOD && !Reflector.ClientModLoader.exists())
				continue;
			String usage = actionSet.usage;
			if (actionSet.advanced && !mc.vrSettings.allowAdvancedBindings)
				usage = "hidden";
			actionSets.add(ImmutableMap.<String, Object>builder().put("name", actionSet.name).put("usage", usage).build());
		}
		jsonMap.put("action_sets", actionSets);

		// Sort the bindings so they're easy to look through in SteamVR
		List<VRInputAction> sortedActions = new ArrayList<>(inputActions.values());
		sortedActions.sort(Comparator.comparing(action -> action.keyBinding));

		List<Map<String, Object>> actions = new ArrayList<>();
		for (VRInputAction action : sortedActions) {
			actions.add(ImmutableMap.<String, Object>builder().put("name", action.name).put("requirement", action.requirement).put("type", action.type).build());
		}
		// Bunch of hard-coded bullshit
		actions.add(ImmutableMap.<String, Object>builder().put("name", ACTION_LEFT_HAND).put("requirement", "suggested").put("type", "pose").build());
		actions.add(ImmutableMap.<String, Object>builder().put("name", ACTION_RIGHT_HAND).put("requirement", "suggested").put("type", "pose").build());
		actions.add(ImmutableMap.<String, Object>builder().put("name", ACTION_EXTERNAL_CAMERA).put("requirement", "optional").put("type", "pose").build());
		actions.add(ImmutableMap.<String, Object>builder().put("name", ACTION_LEFT_HAPTIC).put("requirement", "suggested").put("type", "vibration").build());
		actions.add(ImmutableMap.<String, Object>builder().put("name", ACTION_RIGHT_HAPTIC).put("requirement", "suggested").put("type", "vibration").build());
		jsonMap.put("actions", actions);

		Map<String, Object> localization = new HashMap<>();
		for (VRInputAction action : sortedActions) {
			localization.put(action.name, I18n.format(action.keyBinding.getKeyCategory()) + " - " + I18n.format(action.keyBinding.getKeyDescription()));
		}
		for (VRInputActionSet actionSet : VRInputActionSet.values()) {
			localization.put(actionSet.name, I18n.format(actionSet.localizedName));
		}
		// More hard-coded bullshit
		localization.put(ACTION_LEFT_HAND, "Left Hand Pose");
		localization.put(ACTION_RIGHT_HAND, "Right Hand Pose");
		localization.put(ACTION_EXTERNAL_CAMERA, "External Camera");
		localization.put(ACTION_LEFT_HAPTIC, "Left Hand Haptic");
		localization.put(ACTION_RIGHT_HAPTIC, "Right Hand Haptic");
		localization.put("language_tag", "en_US");
		jsonMap.put("localization", ImmutableList.<Map<String, Object>>builder().add(localization).build());

		List<Map<String, Object>> defaultBindings = new ArrayList<>();
		defaultBindings.add(ImmutableMap.<String, Object>builder().put("controller_type", "vive_controller").put("binding_url", "vive_defaults.json").build());
		defaultBindings.add(ImmutableMap.<String, Object>builder().put("controller_type", "oculus_touch").put("binding_url", "oculus_defaults.json").build());
		defaultBindings.add(ImmutableMap.<String, Object>builder().put("controller_type", "holographic_controller").put("binding_url", "wmr_defaults.json").build());
		defaultBindings.add(ImmutableMap.<String, Object>builder().put("controller_type", "knuckles").put("binding_url", "knuckles_defaults.json").build());
		defaultBindings.add(ImmutableMap.<String, Object>builder().put("controller_type", "vive_cosmos_controller").put("binding_url", "cosmos_defaults.json").build());
		defaultBindings.add(ImmutableMap.<String, Object>builder().put("controller_type", "vive_tracker_camera").put("binding_url", "tracker_defaults.json").build());
		jsonMap.put("default_bindings", defaultBindings);

		try {			
			new File("openvr/input").mkdirs();
			try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream("openvr/input/action_manifest.json"), StandardCharsets.UTF_8)) {
				GSON.toJson(jsonMap, writer);
			}
		} catch (Exception e) {
			throw new RuntimeException("Failed to write action manifest", e);
		}

		String rev = mc.vrSettings.vrReverseHands ? "_reversed" : "";
		Utils.loadAssetToFile("input/vive_defaults" + rev + ".json", new File("openvr/input/vive_defaults.json"), false);
		Utils.loadAssetToFile("input/oculus_defaults" + rev + ".json", new File("openvr/input/oculus_defaults.json"), false);
		Utils.loadAssetToFile("input/wmr_defaults" + rev + ".json", new File("openvr/input/wmr_defaults.json"), false);
		Utils.loadAssetToFile("input/knuckles_defaults" + rev + ".json", new File("openvr/input/knuckles_defaults.json"), false);
		Utils.loadAssetToFile("input/cosmos_defaults" + rev + ".json", new File("openvr/input/cosmos_defaults.json"), false);
		Utils.loadAssetToFile("input/tracker_defaults.json", new File("openvr/input/tracker_defaults.json"), false);
	}

	private static void loadActionManifest() {
		int error = VRInput_SetActionManifestPath(new File("openvr/input/action_manifest.json").getAbsolutePath());
		if (error != 0) {
			throw new RuntimeException("Failed to load action manifest: " + getInputError(error));
		}
	}

	private static void loadActionHandles() {
		LongBuffer longBuffer = BufferUtils.createLongBuffer(1);

		for (VRInputAction action : inputActions.values()) {
			int error = VRInput_GetActionHandle(action.name, longBuffer);
			if (error != 0)
				throw new RuntimeException("Error getting action handle for '" + action.name + "': " + getInputError(error));
			action.setHandle(longBuffer.get(0));
			System.out.println("Setting Action Handle for " + action.name);
		}

		leftPoseHandle = getActionHandle(ACTION_LEFT_HAND);
		rightPoseHandle = getActionHandle(ACTION_RIGHT_HAND);
		leftHapticHandle = getActionHandle(ACTION_LEFT_HAPTIC);
		rightHapticHandle = getActionHandle(ACTION_RIGHT_HAPTIC);
		externalCameraPoseHandle = getActionHandle(ACTION_EXTERNAL_CAMERA);

		for (VRInputActionSet actionSet : VRInputActionSet.values()) {
			int error = VRInput_GetActionSetHandle(actionSet.name, longBuffer);
			if (error != 0)
				throw new RuntimeException("Error getting action set handle for '" + actionSet.name + "': " + getInputError(error));
			actionSetHandles.put(actionSet, longBuffer.get(0));
		}

		leftControllerHandle = getInputSourceHandle("/user/hand/left");
		rightControllerHandle = getInputSourceHandle("/user/hand/right");
	}

	private static long getActionHandle(String name) {
		LongBuffer actionHandle = BufferUtils.createLongBuffer(1);
		int error = VRInput_GetActionHandle(name, actionHandle);
		if (error != 0)
			throw new RuntimeException("Error getting action handle for '" + name + "': " + getInputError(error));
		return actionHandle.get(0);
	}

	private static VRActiveActionSet.Buffer getActiveActionSets() {
		ArrayList<VRInputActionSet> list = new ArrayList<>();
		list.add(VRInputActionSet.GLOBAL);
		if (Reflector.ClientModLoader.exists())
			list.add(VRInputActionSet.MOD);
		list.add(VRInputActionSet.MIXED_REALITY);
		list.add(VRInputActionSet.TECHNICAL);
		if (mc.currentScreen == null) {
			list.add(VRInputActionSet.INGAME);
		//	if (getInputActionsInSet(VRInputActionSet.CONTEXTUAL).stream().anyMatch(VRInputAction::isEnabledRaw))
			list.add(VRInputActionSet.CONTEXTUAL);
		} else {
			list.add(VRInputActionSet.GUI);
		}
		if (KeyboardHandler.Showing || RadialHandler.isShowing())
			list.add(VRInputActionSet.KEYBOARD);
		
		activeActionSetsReference = VRActiveActionSet.create(list.size());

		for (int i = 0; i < list.size(); i++) {
			VRInputActionSet actionSet = list.get(i);
			activeActionSetsReference.get(i).ulActionSet(getActionSetHandle(actionSet));
			activeActionSetsReference.get(i).ulRestrictedToDevice(k_ulInvalidInputValueHandle);
			activeActionSetsReference.get(i).nPriority(0);
		}

		return activeActionSetsReference;
	}

	private static void initializeJOpenVR() {
		hmdErrorStoreBuf = BufferUtils.createIntBuffer(1);

		int token = VR_InitInternal(hmdErrorStoreBuf, EVRApplicationType_VRApplication_Scene);

		if (hmdErrorStoreBuf.get(0) != EVRInitError_VRInitError_None) {
			throw new RuntimeException(VR_GetVRInitErrorAsEnglishDescription(hmdErrorStoreBuf.get(0)));
		} else {
			OpenVR.create(token);
			System.out.println("OpenVR System Initialized OK.");
			
			//hmdDisplayFrequency = BufferUtils.createIntBuffer(1);
			//hmdDisplayFrequency.put(ETrackedDeviceProperty_Prop_DisplayFrequency_Float);

			hmdTrackedDevicePoses = TrackedDevicePose.create(k_unMaxTrackedDeviceCount);
			poseMatrices = new Matrix4f[k_unMaxTrackedDeviceCount];
			for(int i=0;i<poseMatrices.length;i++) poseMatrices[i] = new Matrix4f();

			//doesn't seem to be used, and also doesn't really calculate the time per frame 
			//because hmdDisplayFrequency is just the enum value used to query the data
			//timePerFrame = 1.0 / hmdDisplayFrequency.get(0);

			initSuccess = true;
		}
	}
	
	static float getSuperSampling(){
		if (OpenVR.VRSettings == null)
			return -1;
		return 
				VRSettings_GetFloat("steamvr","supersampleScale", hmdErrorStoreBuf);
	}


	static void debugOut(int deviceindex){
		System.out.println("******************* VR DEVICE: " + deviceindex + " *************************");
		for(Field i :VR.class.getDeclaredFields()){
			//necessary since VR.class includes a lot of "enums"
			if(i.getName().startsWith("ETrackedDeviceProperty_Prop_")) {
				try {
					String[] ts = i.getName().split("_");
					String Type = ts[ts.length - 1];
					String out = "";
					if (Type.equals("Float")) {
						out += i.getName() + " " + VRSystem_GetFloatTrackedDeviceProperty(deviceindex, i.getInt(null), hmdErrorStoreBuf);
					} else if (Type.equals("String")) {
						out += i.getName() + " " + VRSystem_GetStringTrackedDeviceProperty(deviceindex, i.getInt(null), hmdErrorStoreBuf);
					} else if (Type.equals("Bool")) {
						out += i.getName() + " " + VRSystem_GetBoolTrackedDeviceProperty(deviceindex, i.getInt(null), hmdErrorStoreBuf);
					} else if (Type.equals("Int32")) {
						out += i.getName() + " " + VRSystem_GetInt32TrackedDeviceProperty(deviceindex, i.getInt(null), hmdErrorStoreBuf);
					} else if (Type.equals("Uint64")) {
						out += i.getName() + " " + VRSystem_GetUint64TrackedDeviceProperty(deviceindex, i.getInt(null), hmdErrorStoreBuf);
					}else {
						out += i.getName() + " (skipped)" ; 
					}
					System.out.println(out.replace("ETrackedDeviceProperty_Prop_", ""));
				}catch (IllegalAccessException e){
					e.printStackTrace();
				}				
			}

		}
		System.out.println("******************* END VR DEVICE: " + deviceindex + " *************************");

	}

	static void initOpenVRApplications() {
		VR_GetGenericInterface(IVRApplications_Version, hmdErrorStoreBuf);
		if(OpenVR.VRApplications != null) {//check for null here to make sure that we don't report success if the initialization via OpenVR.create() failed
			System.out.println("OpenVR Applications initialized OK");
		} else {
			System.out.println("VRApplications init failed: " + VR_GetVRInitErrorAsEnglishDescription(getError()));
		}
	}

	public static void initOpenVRSettings()
	{
		VR_GetGenericInterface(IVRSettings_Version, hmdErrorStoreBuf);
		if(OpenVR.VRSettings != null) {//check for null here to make sure that we don't report success if the initialization via OpenVR.create() failed
			System.out.println("OpenVR Settings initialized OK");
		} else {
			System.out.println("VRSettings init failed: " + VR_GetVRInitErrorAsEnglishDescription(getError()));
		}
	}


	public static void initOpenVRRenderModels()
	{
		VR_GetGenericInterface(IVRRenderModels_Version, hmdErrorStoreBuf);
		if(OpenVR.VRRenderModels != null) {//check for null here to make sure that we don't report success if the initialization via OpenVR.create() failed
			System.out.println("OpenVR RenderModels initialized OK");
		} else {
			System.out.println("VRRenderModels init failed: " + VR_GetVRInitErrorAsEnglishDescription(getError()));
		}
	}

	private static void initOpenVRChaperone() {
		VR_GetGenericInterface(IVRRenderModels_Version, hmdErrorStoreBuf);
		if(OpenVR.VRChaperone != null) {//check for null here to make sure that we don't report success if the initialization via OpenVR.create() failed
			System.out.println("OpenVR chaperone initialized.");
		} else {
			System.out.println("VRChaperone init failed: " + VR_GetVRInitErrorAsEnglishDescription(getError()));
		}
	}

	private static void initOpenVRInput() {
		VR_GetGenericInterface(IVRRenderModels_Version, hmdErrorStoreBuf);
		if(OpenVR.VRInput != null) {//check for null here to make sure that we don't report success if the initialization via OpenVR.create() failed
			System.out.println("OpenVR Input initialized OK");
		} else {
			System.out.println("VRInput init failed: " + VR_GetVRInitErrorAsEnglishDescription(getError()));
		}
	}
	private static void initOpenComposite() {
		VR_GetGenericInterface(IVROCSystem_Version, hmdErrorStoreBuf);
		if (OpenComposite.VROCSystem != null) {
			System.out.println("OpenComposite initialized.");
		} else {
			System.out.println("OpenComposite not found: " + VR_GetVRInitErrorAsEnglishDescription(getError()));
		}
	}

	private static boolean getXforms = true;

	private static Map<String, Matrix4f[]> controllerComponentTransforms;
	private static Map<Long, String> controllerComponentNames;

	private static void getTransforms(){
		if (OpenVR.VRRenderModels == null) return;

		if(getXforms == true) {
			controllerComponentTransforms = new HashMap<String, Matrix4f[]>();
		}

		if(controllerComponentNames == null) {
			controllerComponentNames = new HashMap<Long, String>();
		}

		int count = VRRenderModels_GetRenderModelCount();

		List<String> componentNames = new ArrayList<String>(); //TODO get the controller-specific list

		componentNames.add("tip");
		//wmr doesnt define these...
		//componentNames.add("base"); 
		//componentNames.add("status");
		//
		componentNames.add("handgrip");
		boolean failed = false;
		


		for (String comp : componentNames) {
			controllerComponentTransforms.put(comp, new Matrix4f[2]); 			

			for (int i = 0; i < 2; i++) {
		
				if (controllerDeviceIndex[i] == k_unTrackedDeviceIndexInvalid) {
					failed = true;
					continue;
				}
				
				String renderModel = VRSystem_GetStringTrackedDeviceProperty(controllerDeviceIndex[i], ETrackedDeviceProperty_Prop_RenderModelName_String, hmdErrorStoreBuf);
				String compString = comp;
				
				
				String path = VRSystem_GetStringTrackedDeviceProperty(controllerDeviceIndex[i], ETrackedDeviceProperty_Prop_InputProfilePath_String, k_unMaxPropertyStringSize, hmdErrorStoreBuf);
				boolean isWMR = path.contains("holographic");
				
				if(isWMR && comp.equals("handgrip")) {// i have no idea, Microsoft, none.
				//	System.out.println("Apply WMR override " + i);
					compString = "body";

				}
				
				//doing this next bit for each controller because pointer
				long button = VRRenderModels_GetComponentButtonMask(renderModel, compString);   		
				if(button > 0){ //see now... wtf openvr, '0' is the system button, it cant also be the error value! (hint: it's a mask, not an index)
					controllerComponentNames.put(button, comp); //u get 1 button per component, nothing more
				}
				//
				long sourceHandle = i == RIGHT_CONTROLLER ? rightControllerHandle : leftControllerHandle;

				if (sourceHandle == k_ulInvalidInputValueHandle) {
					failed = true;
					continue;
				}
				//

				RenderModelControllerModeState modeState = RenderModelControllerModeState.create();
				RenderModelComponentState componentState = RenderModelComponentState.create();
				boolean ret = VRRenderModels_GetComponentStateForDevicePath(renderModel, compString, sourceHandle, modeState, componentState);
				if(ret == false) {
				//	System.out.println("Failed getting transform: " + comp + " controller " + i);
					failed = true; // Oculus does not seem to raise ANY trackedDevice events. So just keep trying...
					continue;
				}
				Matrix4f xform = new Matrix4f();
				OpenVRUtil.convertSteamVRMatrix3ToMatrix4f(componentState.mTrackingToComponentLocal(), xform);
				controllerComponentTransforms.get(comp)[i] = xform;
			//	System.out.println("Transform: " + comp + " controller: " + i + "model " + renderModel + " button: " + button + "\r" + Utils.convertOVRMatrix(xform).toString());

				if (!failed && i == 0) {
					try {

						Matrix4f tip = getControllerComponentTransform(0,"tip");
						Matrix4f hand = getControllerComponentTransform(0,"handgrip");

						Vector3 tipvec = tip.transform(forward);
						Vector3 handvec = hand.transform(forward);

						double dot = Math.abs(tipvec.normalized().dot(handvec.normalized()));
						
						double anglerad = Math.acos(dot);
						double angledeg = Math.toDegrees(anglerad);

						double angletestrad = Math.acos(tipvec.normalized().dot(forward.normalized()));
						double angletestdeg = Math.toDegrees(angletestrad);

					//	System.out.println("gun angle " + angledeg + " default angle " + angletestdeg);
						
						gunStyle = angledeg > 10;
 						gunAngle = angledeg;
					} catch (Exception e) {
						failed = true;
					}
				}
			}
		}
		
		getXforms = failed;
	}

	public static Matrix4f getControllerComponentTransform(int controllerIndex, String componenetName){
		if(controllerComponentTransforms == null || !controllerComponentTransforms.containsKey(componenetName)  || controllerComponentTransforms.get(componenetName)[controllerIndex] == null)
			return OpenVRUtil.Matrix4fSetIdentity(new Matrix4f());
		return controllerComponentTransforms.get(componenetName)[controllerIndex];
	}

	public static Matrix4f getControllerComponentTransformFromButton(int controllerIndex, long button){
		if (controllerComponentNames == null || !controllerComponentNames.containsKey(button))
			return new Matrix4f();

		return getControllerComponentTransform(controllerIndex, controllerComponentNames.get(button));
	}

	public static boolean hasOpenComposite() {
		return OpenComposite.VROCSystem != null;
	}

	public static void initOpenVRCompositor() throws Exception
	{
		if(OpenVR.VRSystem != null ) {
			VR_GetGenericInterface(IVRCompositor_Version, hmdErrorStoreBuf);
			if(OpenVR.VRCompositor != null && !isError()){//check for null here to make sure that we don't report success if the initialization via OpenVR.create() failed
				System.out.println("OpenVR Compositor initialized OK.");
				VRCompositor_SetTrackingSpace(ETrackingUniverseOrigin_TrackingUniverseStanding);

				System.out.println("TrackingSpace: "+ VRCompositor_GetTrackingSpace());

				String id= VRSystem_GetStringTrackedDeviceProperty(k_unTrackedDeviceIndex_Hmd, ETrackedDeviceProperty_Prop_ManufacturerName_String, hmdErrorStoreBuf);
				System.out.println("Device manufacturer is: "+id);

				detectedHardware = HardwareType.fromManufacturer(id);
				mc.vrSettings.loadOptions();
				VRHotkeys.loadExternalCameraConfig();

			} else {
				throw new Exception(VR_GetVRInitErrorAsEnglishDescription(getError()));			 
			}
		}
		if( OpenVR.VRCompositor == null ) {
			System.out.println("Skipping VR Compositor...");
			if( OpenVR.VRSystem != null ) {
				vsyncToPhotons = VRSystem_GetFloatTrackedDeviceProperty(k_unTrackedDeviceIndex_Hmd, ETrackedDeviceProperty_Prop_SecondsFromVsyncToPhotons_Float, hmdErrorStoreBuf);
			} else {
				vsyncToPhotons = 0f;
			}
		}

		// left eye
		texBounds.uMax(1f);
		texBounds.uMin(0f);
		texBounds.vMax(1f);
		texBounds.vMin(0f);

		// texture type
		texType0.eColorSpace(EColorSpace_ColorSpace_Gamma);
		texType0.eType(ETextureType_TextureType_OpenGL);
		texType0.handle(-1);
	//	VRTextureDepthInfo info = VRTextureDepthInfo.create();
	//	HmdVector2 vec0 = HmdVector2.create();
	//	vec0.v(FloatBuffer.wrap(new float[]{0,1}));
	//	info.vRange(vec0);
	//	texType0.depth(info);

		// texture type
		texType1.eColorSpace(EColorSpace_ColorSpace_Gamma);
		texType1.eType(ETextureType_TextureType_OpenGL);
		texType1.handle(-1);
	//	VRTextureDepthInfo info2 = VRTextureDepthInfo.create();
	//	HmdVector2 vec1 = HmdVector2.create();
	//	vec1.v(FloatBuffer.wrap(new float[]{0,1}));
		//info2.vRange(vec0);
	//	texType1.depth = info2;

		System.out.println("OpenVR Compositor initialized OK.");

	}

	public boolean initOpenVRControlPanel()
	{
		return true;
		//		VR_GetGenericInterface(IVRControlPanel_Version, hmdErrorStoreBuf);
		//		if (OpenVR.VRControlPanel != null && !isError()) {
		//			System.out.println("OpenVR Control Panel initialized OK.");
		//			return true;
		//		} else {
		//			initStatus = "OpenVR Control Panel error: " + VR_GetVRInitErrorAsEnglishDescription(getError());
		//			return false;
		//		}
	}

	private String lasttyped = "";

	public static boolean paused =false; 

	public static void poll(long frameIndex)
	{
		if (!initialized) return;
	
		paused = VRSystem_ShouldApplicationPause();

		mc.getProfiler().startSection("events");
		pollVREvents();

		if(!mc.vrSettings.seated){
			mc.getProfiler().endStartSection("controllers");

			// GUI controls

			mc.getProfiler().startSection("gui");

			if(mc.currentScreen == null && mc.vrSettings.vrTouchHotbar && mc.vrSettings.vrHudLockMode != mc.vrSettings.HUD_LOCK_HEAD && hudPopup){
				processHotbar();
			}

			mc.getProfiler().endSection();
		}

		mc.getProfiler().endStartSection("processEvents");
		processVREvents();

		mc.getProfiler().endStartSection("updatePose/Vsync");
		updatePose();

		mc.getProfiler().endStartSection("processInputs");
		processInputs();

		mc.getProfiler().endStartSection("hmdSampling");
		hmdSampling();
		
		mc.getProfiler().endSection();
	}
	
	private static void hmdSampling() {
    	if (hmdPosSamples.size() == hmdAvgLength)
    		hmdPosSamples.removeFirst();
    	if (hmdYawSamples.size() == hmdAvgLength)
    		hmdYawSamples.removeFirst();

    	float yaw = mc.vrPlayer.vrdata_room_pre.hmd.getYaw();
    	if (yaw < 0)
    		yaw += 360;
    	hmdYawTotal += angleDiff(yaw, hmdYawLast);
    	hmdYawLast = yaw;
    	if (Math.abs(angleNormalize(hmdYawTotal) - hmdYawLast) > 1 || hmdYawTotal > 100000) {
    		hmdYawTotal = hmdYawLast;
    		System.out.println("HMD yaw desync/overflow corrected");
    	}
    	hmdPosSamples.add(mc.vrPlayer.vrdata_room_pre.hmd.getPosition());
    	float yawAvg = 0;
    	if (hmdYawSamples.size() > 0) {
    		for (float f : hmdYawSamples) {
    			yawAvg += f;
    		}
    		yawAvg /= hmdYawSamples.size();
    	}
    	if (Math.abs((hmdYawTotal - yawAvg)) > 20)
    		trigger = true;
    	if (Math.abs((hmdYawTotal - yawAvg)) < 1)
    		trigger = false;
    	if (trigger || hmdYawSamples.isEmpty())
    		hmdYawSamples.add(hmdYawTotal);
	}
	
	private static float angleNormalize(float angle) {
		angle %= 360;
		if (angle < 0)
			angle += 360;
		return angle;
	}

	private static float angleDiff(float a, float b) {
		float d = Math.abs(a - b) % 360;
		float r = d > 180 ? 360 - d : d;
		int sign = (a - b >= 0 && a - b <= 180) || (a - b <= -180 && a - b >= -360) ? 1 : -1;
		return r * sign;
	}
	
	private static int quickTorchPreviousSlot;

	private static void processHotbar() {
		mc.interactTracker.hotbar = -1;
		if(mc.player == null) return;
		if(mc.player.inventory == null) return;
		
		if(mc.climbTracker.isGrabbingLadder() && 
				mc.climbTracker.isClaws(mc.player.getHeldItemMainhand())) return;
		if(!mc.interactTracker.isActive(mc.player)) return;
		Vector3d main = getAimSource(0);
		Vector3d off = getAimSource(1);

		Vector3d barStartos = null,barEndos = null;

		int i = 1;
		if(mc.vrSettings.vrReverseHands) i = -1;

		if (mc.vrSettings.vrHudLockMode == VRSettings.HUD_LOCK_WRIST){
			barStartos =  getAimRotation(1).transform(new Vector3(i*0.02f,0.05f,0.26f)).toVector3d();
			barEndos =  getAimRotation(1).transform(new Vector3(i*0.02f,0.05f,0.01f)).toVector3d();
		} else if (mc.vrSettings.vrHudLockMode == VRSettings.HUD_LOCK_HAND){
			barStartos =  getAimRotation(1).transform(new Vector3(i*-.18f,0.08f,-0.01f)).toVector3d();
			barEndos =  getAimRotation(1).transform(new Vector3(i*0.19f,0.04f,-0.08f)).toVector3d();
		} else return; //how did u get here


		Vector3d barStart = off.add(barStartos.x, barStartos.y, barStartos.z);	
		Vector3d barEnd = off.add(barEndos.x, barEndos.y, barEndos.z);

		Vector3d u = barStart.subtract(barEnd);
		Vector3d pq = barStart.subtract(main);
		float dist = (float) (pq.crossProduct(u).length() / u.length());

		if(dist > 0.06) return;

		float fact = (float) (pq.dotProduct(u) / (u.x*u.x + u.y*u.y + u.z*u.z));

		if(fact < -1) return;
		
		Vector3d w2 = u.scale(fact).subtract(pq);

		Vector3d point = main.subtract(w2);
		float linelen = (float) u.length();
		float ilen = (float) barStart.subtract(point).length();
		if(fact < 0) ilen *= -1;
		float pos = ilen / linelen * 9; 

		if(mc.vrSettings.vrReverseHands) pos = 9 - pos;

		int box = (int) Math.floor(pos);

		if(box > 8) return;
		if(box < 0) {
			if(pos <= -0.5 && pos >= -1.5) //TODO fix reversed hands situation.
				box = 9;
			else
				return;
		}
		//all that maths for this.
		mc.interactTracker.hotbar = box;
		if(box != mc.interactTracker.hotbar){
			triggerHapticPulse(0, 750);
		}
	}

	

	public static void destroy()
	{
		if (initialized)
		{
			try {
				VR_ShutdownInternal();
				OpenVR.destroy();
				OpenComposite.destroy();
				initialized = false;
				if(Main.katvr)
					jkatvr.Halt();
				if(Main.infinadeck)
					jinfinadeck.Destroy();
			} catch (Throwable e) { // wtf valve
				e.printStackTrace();
			}

		}
	}

	//	public HmdParameters getHMDInfo()
	//	{
	//		HmdParameters hmd = new HmdParameters();
	//		if ( isInitialized() )
	//		{
	//			IntBuffer rtx = IntBuffer.allocate(1);
	//			IntBuffer rty = IntBuffer.allocate(1);
	//			vrsystem.GetRecommendedRenderTargetSize.apply(rtx, rty);
	//
	//			hmd.Type = HmdType.ovrHmd_Other;
	//			hmd.ProductName = "OpenVR";
	//			hmd.Manufacturer = "Unknown";
	//			hmd.AvailableHmdCaps = 0;
	//			hmd.DefaultHmdCaps = 0;
	//			hmd.AvailableTrackingCaps = HmdParameters.ovrTrackingCap_Orientation | HmdParameters.ovrTrackingCap_Position;
	//			hmd.DefaultTrackingCaps = HmdParameters.ovrTrackingCap_Orientation | HmdParameters.ovrTrackingCap_Position;
	//			hmd.Resolution = new Sizei( rtx.get(0) * 2, rty.get(0) );
	//
	//			float topFOV = vrsystem.GetFloatTrackedDeviceProperty.apply(JOpenVRLibrary.k_unTrackedDeviceIndex_Hmd, JOpenVRLibrary.ETrackedDeviceProperty.ETrackedDeviceProperty_Prop_FieldOfViewTopDegrees_Float, hmdErrorStore);
	//			float bottomFOV = vrsystem.GetFloatTrackedDeviceProperty.apply(JOpenVRLibrary.k_unTrackedDeviceIndex_Hmd, JOpenVRLibrary.ETrackedDeviceProperty.ETrackedDeviceProperty_Prop_FieldOfViewBottomDegrees_Float, hmdErrorStore);
	//			float leftFOV = vrsystem.GetFloatTrackedDeviceProperty.apply(JOpenVRLibrary.k_unTrackedDeviceIndex_Hmd, JOpenVRLibrary.ETrackedDeviceProperty.ETrackedDeviceProperty_Prop_FieldOfViewLeftDegrees_Float, hmdErrorStore);
	//			float rightFOV = vrsystem.GetFloatTrackedDeviceProperty.apply(JOpenVRLibrary.k_unTrackedDeviceIndex_Hmd, JOpenVRLibrary.ETrackedDeviceProperty.ETrackedDeviceProperty_Prop_FieldOfViewRightDegrees_Float, hmdErrorStore);
	//
	//			hmd.DefaultEyeFov[0] = new FovPort((float)Math.tan(topFOV),(float)Math.tan(bottomFOV),(float)Math.tan(leftFOV),(float)Math.tan(rightFOV));
	//			hmd.DefaultEyeFov[1] = new FovPort((float)Math.tan(topFOV),(float)Math.tan(bottomFOV),(float)Math.tan(leftFOV),(float)Math.tan(rightFOV));
	//			hmd.MaxEyeFov[0] = new FovPort((float)Math.tan(topFOV),(float)Math.tan(bottomFOV),(float)Math.tan(leftFOV),(float)Math.tan(rightFOV));
	//			hmd.MaxEyeFov[1] = new FovPort((float)Math.tan(topFOV),(float)Math.tan(bottomFOV),(float)Math.tan(leftFOV),(float)Math.tan(rightFOV));
	//			hmd.DisplayRefreshRate = 90.0f;
	//		}
	//
	//		return hmd;
	//	}



	private static void processScrollInput(KeyBinding keyBinding, Runnable upCallback, Runnable downCallback) {
		VRInputAction action = getInputAction(keyBinding);
		if (action.isEnabled() && action.getLastOrigin() != k_ulInvalidInputValueHandle && action.getAxis2D(true).getY() != 0) {
			float value = action.getAxis2D(false).getY();
			if (value > 0)
				upCallback.run();
			else if (value < 0)
				downCallback.run();
		}
	}

	private static void processSwipeInput(KeyBinding keyBinding, Runnable leftCallback, Runnable rightCallback, Runnable upCallback, Runnable downCallback) {
		VRInputAction action = getInputAction(keyBinding);
		if (action.isEnabled() && action.getLastOrigin() != k_ulInvalidInputValueHandle) {
			ControllerType controller = findActiveBindingControllerType(keyBinding);
			if (controller != null) {
				if (!trackpadSwipeSamplers.containsKey(keyBinding.getKeyDescription()))
					trackpadSwipeSamplers.put(keyBinding.getKeyDescription(), new TrackpadSwipeSampler());
				TrackpadSwipeSampler sampler = trackpadSwipeSamplers.get(keyBinding.getKeyDescription());
				sampler.update(controller, action.getAxis2D(false));

				if (sampler.isSwipedUp() && upCallback != null) {
					triggerHapticPulse(controller, 0.001f, 400, 0.5f);
					upCallback.run();
				}
				if (sampler.isSwipedDown() && downCallback != null) {
					triggerHapticPulse(controller, 0.001f, 400, 0.5f);
					downCallback.run();
				}
				if (sampler.isSwipedLeft() && leftCallback != null) {
					triggerHapticPulse(controller, 0.001f, 400, 0.5f);
					leftCallback.run();
				}
				if (sampler.isSwipedRight() && rightCallback != null) {
					triggerHapticPulse(controller, 0.001f, 400, 0.5f);
					rightCallback.run();
				}
			}
		}
	}

	private static void processInputAction(VRInputAction action) {
		if (!action.isActive() || !action.isEnabledRaw()) {
			action.unpressBinding();
		} else {
			if (action.isButtonChanged()) {
				if (action.isButtonPressed() && action.isEnabled()) {
					// We do this so shit like closing a GUI by clicking a button won't
					// also click in the world immediately after.
					if (!ignorePressesNextFrame)
						action.pressBinding();
				} else {
					action.unpressBinding();
				}
			}
		}
	}

	public static void processInputs() {
		if (mc.vrSettings.seated || Main.viewonly || !inputInitialized) return;

		for (VRInputAction action : inputActions.values()) {
			if (action.isHanded()) {
				for (ControllerType hand : ControllerType.values()) {
					action.setCurrentHand(hand);
					processInputAction(action);
				}
			} else {
				processInputAction(action);
			}
		}

		processScrollInput(GuiHandler.keyScrollAxis, () -> InputSimulator.scrollMouse(0, 1), () -> InputSimulator.scrollMouse(0, -1));
		processScrollInput(keyHotbarScroll, () -> changeHotbar(-1), () -> changeHotbar(1));
		processSwipeInput(keyHotbarSwipeX, () -> changeHotbar(1), () -> changeHotbar(-1), null, null);
		processSwipeInput(keyHotbarSwipeY, null, null, () -> changeHotbar(-1), () -> changeHotbar(1));

		// Reset this flag
		ignorePressesNextFrame = false;
	}

	public static void processBindings() {
		//VIVE SPECIFIC FUNCTIONALITY
		//TODO: Find a better home for these. (uh?)
		if (inputActions.isEmpty()) return;
		boolean sleeping = (mc.world !=null && mc.player != null && mc.player.isSleeping());
		boolean gui = mc.currentScreen != null;

		boolean toggleMovementPressed = keyToggleMovement.isPressed();
		if (mc.gameSettings.keyBindPickBlock.isKeyDown() || toggleMovementPressed) {
			if (++moveModeSwitchCount == 20 * 4 || toggleMovementPressed) {
				if (mc.vrSettings.seated) {
					mc.vrSettings.seatedFreeMove = !mc.vrSettings.seatedFreeMove;
					mc.ingameGUI.getChatGUI().printChatMessage(new TranslationTextComponent("vivecraft.messages.movementmodeswitch", mc.vrSettings.seatedFreeMove ? Lang.get("vivecraft.options.freemove") : Lang.get("vivecraft.options.teleport")));
				} else 
				{
					if (mc.vrPlayer.isTeleportSupported()) {
						mc.vrSettings.forceStandingFreeMove = !mc.vrSettings.forceStandingFreeMove;
						mc.ingameGUI.getChatGUI().printChatMessage(new TranslationTextComponent("vivecraft.messages.movementmodeswitch", mc.vrSettings.seatedFreeMove ? Lang.get("vivecraft.options.freemove") : Lang.get("vivecraft.options.teleport")));
					} else {
						if (mc.vrPlayer.isTeleportOverridden()) {
							mc.vrPlayer.setTeleportOverride(false);
							mc.ingameGUI.getChatGUI().printChatMessage(new TranslationTextComponent("vivecraft.messages.teleportdisabled"));
						} else {
							mc.vrPlayer.setTeleportOverride(true);
							mc.ingameGUI.getChatGUI().printChatMessage(new TranslationTextComponent("vivecraft.messages.teleportenabled"));
						}
					}
				}
			}
		} else {
			moveModeSwitchCount = 0;
		}

		Vector3d main = getAimVector(0);
		Vector3d off = getAimVector(1);

		float myaw = (float) Math.toDegrees(Math.atan2(-main.x, main.z));
		float oyaw= (float) Math.toDegrees(Math.atan2(-off.x, off.z));;

		if(!gui){
			if(keyWalkabout.isKeyDown()){
				float yaw = myaw;

				//oh this is ugly. TODO: cache which hand when binding button.
				TrackedController controller = findActiveBindingController(keyWalkabout);
				if (controller != null && controller.getType() == ControllerType.LEFT) {
					yaw = oyaw;
				}

				if (!isWalkingAbout){
					isWalkingAbout = true;
					walkaboutYawStart = mc.vrSettings.vrWorldRotation - yaw;  
				}
				else {
					mc.vrSettings.vrWorldRotation = walkaboutYawStart + yaw;
					mc.vrSettings.vrWorldRotation %= 360; // Prevent stupidly large values (can they even happen here?)
					//	mc.vrPlayer.checkandUpdateRotateScale(true);
				}
			} else {
				isWalkingAbout = false;
			}

			if(keyRotateFree.isKeyDown()){
				float yaw = myaw;

				//oh this is ugly. TODO: cache which hand when binding button.
				TrackedController controller = findActiveBindingController(keyRotateFree);
				if (controller != null && controller.getType() == ControllerType.LEFT) {
					yaw = oyaw;
				}

				if (!isFreeRotate){
					isFreeRotate = true;
					walkaboutYawStart = mc.vrSettings.vrWorldRotation + yaw;  
				}
				else {
					mc.vrSettings.vrWorldRotation = walkaboutYawStart - yaw;
					//	mc.vrPlayer.checkandUpdateRotateScale(true,0);
				}
			} else {
				isFreeRotate = false;
			}
		}


		if(keyHotbarNext.isPressed()) {
			changeHotbar(-1);
			MCOpenVR.triggerBindingHapticPulse(keyHotbarNext, 250);
		}

		if(keyHotbarPrev.isPressed()){
			changeHotbar(1);
			MCOpenVR.triggerBindingHapticPulse(keyHotbarPrev, 250);
		}

		if(keyQuickTorch.isPressed() && mc.player != null){
			for (int slot=0;slot<9;slot++)
			{  
				ItemStack itemStack = mc.player.inventory.getStackInSlot(slot);
				if (itemStack.getItem() instanceof BlockItem && ((BlockItem)itemStack.getItem()).getBlock() instanceof TorchBlock  && mc.currentScreen == null)
				{
					quickTorchPreviousSlot = mc.player.inventory.currentItem;
					mc.player.inventory.currentItem = slot;
					mc.rightClickMouse();
					// switch back immediately
					mc.player.inventory.currentItem = quickTorchPreviousSlot;
					quickTorchPreviousSlot = -1;
					break;
				}
			}
		}

		// if you start teleporting, close any UI
		if (gui && !sleeping && mc.gameSettings.keyBindForward.isKeyDown() && !(mc.currentScreen instanceof WinGameScreen))
		{
			if(mc.player !=null) mc.player.closeScreen();
		}

		//GuiContainer.java only listens directly to the keyboard to close.
		if (mc.currentScreen instanceof ContainerScreen && mc.gameSettings.keyBindInventory.isPressed()) {
			if (mc.player != null)
				mc.player.closeScreen();
		}

		// allow toggling chat window with chat keybind
		if (mc.currentScreen instanceof ChatScreen && mc.gameSettings.keyBindChat.isPressed()) {
			mc.displayGuiScreen(null);
		}

		if(mc.vrSettings.vrWorldRotationIncrement == 0){
			float ax = getAxis2D(getInputAction(keyRotateAxis)).getX();
			if (ax == 0) ax = getAxis2D(getInputAction(keyFreeMoveRotate)).getX();
			if (ax != 0) {
				float analogRotSpeed = 10 * ax;
				mc.vrSettings.vrWorldRotation -= analogRotSpeed;
				mc.vrSettings.vrWorldRotation = mc.vrSettings.vrWorldRotation % 360;
			}
		} else {
			if (keyRotateAxis.isPressed() || keyFreeMoveRotate.isPressed()) {
				float ax = getInputAction(keyRotateAxis).getAxis2D(false).getX();
				if (ax == 0) ax = getInputAction(keyFreeMoveRotate).getAxis2D(false).getX();
				if (Math.abs(ax) > 0.5f) {
					mc.vrSettings.vrWorldRotation -= mc.vrSettings.vrWorldRotationIncrement * Math.signum(ax);
					mc.vrSettings.vrWorldRotation = mc.vrSettings.vrWorldRotation % 360;
				}
			}
		}

		if(mc.vrSettings.vrWorldRotationIncrement == 0){
			float ax = VivecraftMovementInput.getMovementAxisValue(keyRotateLeft);
			if(ax > 0){
				float analogRotSpeed = 5;
				if(ax > 0)	analogRotSpeed= 10 * ax;
				mc.vrSettings.vrWorldRotation+=analogRotSpeed;
				mc.vrSettings.vrWorldRotation = mc.vrSettings.vrWorldRotation % 360;
			}
		}else{
			if(keyRotateLeft.isPressed()){
				mc.vrSettings.vrWorldRotation+=mc.vrSettings.vrWorldRotationIncrement;
				mc.vrSettings.vrWorldRotation = mc.vrSettings.vrWorldRotation % 360;
			}
		}

		if(mc.vrSettings.vrWorldRotationIncrement == 0){
			float ax = VivecraftMovementInput.getMovementAxisValue(keyRotateRight);
			if(ax > 0){
				float analogRotSpeed = 5;
				if(ax > 0)	analogRotSpeed = 10 * ax;
				mc.vrSettings.vrWorldRotation-=analogRotSpeed;
				mc.vrSettings.vrWorldRotation = mc.vrSettings.vrWorldRotation % 360;
			}
		}else{
			if(keyRotateRight.isPressed()){
				mc.vrSettings.vrWorldRotation-=mc.vrSettings.vrWorldRotationIncrement;
				mc.vrSettings.vrWorldRotation = mc.vrSettings.vrWorldRotation % 360;
			}
		}

		seatedRot = mc.vrSettings.vrWorldRotation;

		if(keyRadialMenu.isPressed() && !gui) {
			TrackedController controller = findActiveBindingController(keyRadialMenu);
			if (controller != null)
				RadialHandler.setOverlayShowing(!RadialHandler.isShowing(), controller.getType());
		}

		if (keySwapMirrorView.isPressed()) {
			if (mc.vrSettings.displayMirrorMode == VRSettings.MIRROR_THIRD_PERSON)
				mc.vrSettings.displayMirrorMode = VRSettings.MIRROR_FIRST_PERSON;
			else if (mc.vrSettings.displayMirrorMode == VRSettings.MIRROR_FIRST_PERSON)
				mc.vrSettings.displayMirrorMode = VRSettings.MIRROR_THIRD_PERSON;
			mc.stereoProvider.reinitFrameBuffers("Mirror Setting Changed");
		}

		if (keyToggleKeyboard.isPressed()) {
			KeyboardHandler.setOverlayShowing(!KeyboardHandler.Showing);
		}

		if (keyMoveThirdPersonCam.isPressed() && !Main.kiosk && !mc.vrSettings.seated && (mc.vrSettings.displayMirrorMode == VRSettings.MIRROR_MIXED_REALITY || mc.vrSettings.displayMirrorMode == VRSettings.MIRROR_THIRD_PERSON)) {
			TrackedController controller = MCOpenVR.findActiveBindingController(keyMoveThirdPersonCam);
			if (controller != null)
				VRHotkeys.startMovingThirdPersonCam(controller.getType().ordinal(), VRHotkeys.Triggerer.BINDING);
		}
		if (!keyMoveThirdPersonCam.isKeyDown() && VRHotkeys.isMovingThirdPersonCam() && VRHotkeys.getMovingThirdPersonCamTriggerer() == VRHotkeys.Triggerer.BINDING) {
			VRHotkeys.stopMovingThirdPersonCam();
			mc.vrSettings.saveOptions();
		}

		if (VRHotkeys.isMovingThirdPersonCam() && VRHotkeys.getMovingThirdPersonCamTriggerer() == VRHotkeys.Triggerer.MENUBUTTON && keyMenuButton.isPressed()) { //super special case.
			VRHotkeys.stopMovingThirdPersonCam();
			mc.vrSettings.saveOptions();
		}

		if(KeyboardHandler.Showing && mc.currentScreen == null && keyMenuButton.isPressed()) { //super special case.
			KeyboardHandler.setOverlayShowing(false);
		}

		if(RadialHandler.isShowing() && keyMenuButton.isPressed()) { //super special case.
			RadialHandler.setOverlayShowing(false, null);
		}

		if(keyMenuButton.isPressed()) { //handle menu directly
			if(!gui) {
				if(!Main.kiosk){
						mc.displayInGameMenu(false);
				}
			} else {
				InputSimulator.pressKey(GLFW.GLFW_KEY_ESCAPE);
				InputSimulator.releaseKey(GLFW.GLFW_KEY_ESCAPE);
			}
			KeyboardHandler.setOverlayShowing(false);
		}

		if (keyExportWorld.isPressed()) {
			if (mc.world != null && mc.player != null) {
				try {
					final BlockPos pos = mc.player.getPosition();
					final int size = 320;
					File dir = new File("menuworlds/custom_114");
					dir.mkdirs();
					File foundFile;
					for (int i = 0;; i++) {
						foundFile = new File(dir, "world" + i + ".mmw");
						if (!foundFile.exists())
							break;
					}
					final File file = foundFile;
					System.out.println("Exporting world... area size: " + size);
					System.out.println("Saving to " + file.getAbsolutePath());
					if (mc.isIntegratedServerRunning()) {
						final World world = mc.getIntegratedServer().getWorld(mc.player.world.getDimensionKey());
						CompletableFuture<Void> task = mc.getIntegratedServer().runAsync(new Runnable() {
							@Override
							public void run() {
								try {
									MenuWorldExporter.saveAreaToFile(world, pos.getX() - size / 2, pos.getZ() - size / 2, size, size, pos.getY(), file);
								} catch (IOException e) {
									e.printStackTrace();
								}
							}
						});
						while (!task.isDone()) {
							Thread.sleep(10);
						}
					} else {
						MenuWorldExporter.saveAreaToFile(mc.world, pos.getX() - size / 2, pos.getZ() - size / 2, size, size, pos.getY(), file);
						mc.ingameGUI.getChatGUI().printChatMessage(new TranslationTextComponent("vivecraft.messages.menuworldexportclientwarning"));
					}
					mc.ingameGUI.getChatGUI().printChatMessage(new StringTextComponent(LangHelper.get("vivecraft.messages.menuworldexportcomplete.1", size)));
					mc.ingameGUI.getChatGUI().printChatMessage(new TranslationTextComponent("vivecraft.messages.menuworldexportcomplete.2", file.getAbsolutePath()));
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}

		if (keyTogglePlayerList.isPressed()) {
			mc.ingameGUI.showPlayerList = !mc.ingameGUI.showPlayerList;
		}

		if (keyToggleHandheldCam.isPressed() && mc.player != null) {
			mc.cameraTracker.toggleVisibility();
			if (mc.cameraTracker.isVisible()) {
				ControllerType hand = findActiveBindingControllerType(keyToggleHandheldCam);
				if (hand == null)
					hand = ControllerType.RIGHT;
				VRData.VRDevicePose handPose = mc.vrPlayer.vrdata_world_pre.getController(hand.ordinal());
				mc.cameraTracker.setPosition(handPose.getPosition());
				mc.cameraTracker.setRotation(new Quaternion(handPose.getMatrix().transposed()));
			}
		}

		GuiHandler.processBindingsGui();
		RadialHandler.processBindings();
		KeyboardHandler.processBindings();
		mc.interactTracker.processBindings();
	}

	private static void changeHotbar(int dir){
		if(mc.player == null || (mc.climbTracker.isGrabbingLadder() && 
				mc.climbTracker.isClaws(mc.player.getHeldItemMainhand()))) //never let go, jack.
		{}
		else{
			//if (Reflector.forgeExists() && mc.currentScreen == null && Display.isActive())
			//	KeyboardSimulator.robot.mouseWheel(-dir * 120);
			//else
				mc.player.inventory.changeCurrentItem(dir);
		}
	}

	private static String findEvent(int eventcode) {
		Field[] fields = VR.class.getFields();

		for (Field field : fields) {
			//VR.class contains a lot of "enums", so we have to limit the search to fields with a matching name by name
			//there might be better solutions but since it's just for Debugging, that should do it.
			if (field.getType() == Integer.TYPE && field.getName().startsWith("EVREventType_VREvent_")) {
				String n = field.getName();
				int val;
				try {
					val = field.getInt(null);
					if(val == eventcode) return n;
				} catch (IllegalArgumentException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				} catch (IllegalAccessException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
			}
		}
		return "";
	}

	// Valve why do we have to poll events before we can get updated controller state?
	private static void pollVREvents()
	{
		if (OpenVR.VRSystem == null) return;
		for (VREvent event = VREvent.create(); VRSystem_PollNextEvent(event) == true; event = VREvent.create()) {
			vrEvents.add(event);
		}
	}

	//jrbuda:: oh hello there you are.
	private static void processVREvents() {
		while (!vrEvents.isEmpty()) {
			VREvent event = vrEvents.poll();
			//System.out.println("SteamVR Event: " + findEvent(event.eventType));

			switch (event.eventType()) {
				/*case EVREventType_VREvent_KeyboardClosed:
					//'huzzah'
					keyboardShowing = false;
					if (mc.currentScreen instanceof GuiChat && !mc.vrSettings.seated) {
						GuiTextField field = (GuiTextField)MCReflection.getField(MCReflection.GuiChat_inputField, mc.currentScreen);
						if (field != null) {
							String s = field.getText().trim();
							if (!s.isEmpty()) {
								mc.currentScreen.sendChatMessage(s);
							}
						}
						//mc.displayGuiScreen((Screen)null);
					}
					break;
				case EVREventType_VREvent_KeyboardCharInput:
					ByteBuffer inbytes = event.data().keyboard().cNewInput();
					String str = memUTF8(inbytes);
					if (mc.currentScreen != null && !mc.vrSettings.alwaysSimulateKeyboard) { // experimental, needs testing
						try {
							for (char ch : str.toCharArray()) {
								int[] codes = KeyboardSimulator.getLWJGLCodes(ch);
								int code = codes.length > 0 ? codes[codes.length - 1] : 0;
								if (InputInjector.isSupported()) InputInjector.typeKey(code, ch);
								else mc.currentScreen.keyTypedPublic(ch, code);
								break;
							}
						} catch (Exception e) {
							e.printStackTrace();
						}
					} else {
						KeyboardSimulator.type(str); //holy shit it works.
					}
					break;*/
				case EVREventType_VREvent_Quit:
					mc.shutdown();
					break;
				case EVREventType_VREvent_TrackedDeviceActivated:
				case EVREventType_VREvent_TrackedDeviceDeactivated:
				case EVREventType_VREvent_TrackedDeviceRoleChanged:
				case EVREventType_VREvent_TrackedDeviceUpdated:
				case EVREventType_VREvent_ModelSkinSettingsHaveChanged:
					getXforms = true;
					break;
				default:
					break;
			}
		}
	}

	public static boolean isBoundInActiveActionSets(KeyBinding binding) {
		List<Long> origins = getInputAction(binding).getOrigins();
		return !origins.isEmpty();
	}

	public static ControllerType findActiveBindingControllerType(KeyBinding binding) {
		if (!inputInitialized) return null;
		long origin = getInputAction(binding).getLastOrigin();
		if (origin != k_ulInvalidInputValueHandle) {
			return getOriginControllerType(origin);
		}
		return null;
	}

	public static TrackedController findActiveBindingController(KeyBinding binding) {
		ControllerType type = findActiveBindingControllerType(binding);
		if (type != null) return type.getController();
		return null;
	}

	public static void triggerBindingHapticPulse(KeyBinding binding, float durationSeconds, float frequency, float amplitude) {
		TrackedController controller = findActiveBindingController(binding);
		if (controller != null) controller.triggerHapticPulse(durationSeconds, frequency, amplitude);
	}

	@Deprecated
	public static void triggerBindingHapticPulse(KeyBinding binding, int duration) {
		TrackedController controller = findActiveBindingController(binding);
		if (controller != null) controller.triggerHapticPulse(duration);
		}

	public static VRInputAction getInputActionByName(String name) {
		return inputActions.get(name);
		}

	public static VRInputAction getInputAction(String keyBindingDesc) {
		return inputActionsByKeyBinding.get(keyBindingDesc);
	}

	public static VRInputAction getInputAction(KeyBinding keyBinding) {
		return getInputAction(keyBinding.getKeyDescription());
	}

	public static Collection<VRInputAction> getInputActions() {
		return Collections.unmodifiableCollection(inputActions.values());
	}

	public static Collection<VRInputAction> getInputActionsInSet(VRInputActionSet set) {
		return Collections.unmodifiableCollection(inputActions.values().stream().filter(action -> action.actionSet == set).collect(Collectors.toList()));
	}

	public static long getActionSetHandle(VRInputActionSet actionSet) {
		return actionSetHandles.get(actionSet);
	}

	public static long getInputSourceHandle(String path) {
		LongBuffer longBuff = BufferUtils.createLongBuffer(1);
		int error = VRInput_GetInputSourceHandle(path, longBuff);
		if (error != 0)
			throw new RuntimeException("Error getting input source handle for '" + path + "': " + getInputError(error));
		return longBuff.get(0);

	}

	public static long getControllerHandle(ControllerType hand) {
		if (mc.vrSettings.vrReverseHands) {
			if (hand == ControllerType.RIGHT)
				return leftControllerHandle;
			else
				return rightControllerHandle;
		} else {
			if (hand == ControllerType.RIGHT)
				return rightControllerHandle;
			else
				return leftControllerHandle;
		}
	}

	public static long getHapticHandle(ControllerType hand) {
		if (hand == ControllerType.RIGHT)
			return rightHapticHandle;
		else
			return leftHapticHandle;
	}
	
	public static String getInputError(int code){
		switch (code){
		case EVRInputError_VRInputError_BufferTooSmall:
			return "BufferTooSmall";
		case EVRInputError_VRInputError_InvalidBoneCount:
			return "InvalidBoneCount";
		case EVRInputError_VRInputError_InvalidBoneIndex:
			return "InvalidBoneIndex";
		case EVRInputError_VRInputError_InvalidCompressedData:
			return "InvalidCompressedData";
		case EVRInputError_VRInputError_InvalidDevice:
			return "InvalidDevice";
		case EVRInputError_VRInputError_InvalidHandle:
			return "InvalidHandle";
		case EVRInputError_VRInputError_InvalidParam:
			return "InvalidParam";
		case EVRInputError_VRInputError_InvalidSkeleton:
			return "InvalidSkeleton";
		case EVRInputError_VRInputError_IPCError:
			return "IPCError";
		case EVRInputError_VRInputError_MaxCapacityReached:
			return "MaxCapacityReached";
		case EVRInputError_VRInputError_MismatchedActionManifest:
			return "MismatchedActionManifest";
		case EVRInputError_VRInputError_MissingSkeletonData:
			return "MissingSkeletonData";
		case EVRInputError_VRInputError_NameNotFound:
			return "NameNotFound";
		case EVRInputError_VRInputError_NoActiveActionSet:
			return "NoActiveActionSet";
		case EVRInputError_VRInputError_NoData:
			return "NoData";
		case EVRInputError_VRInputError_None:
			return "wat";
		case EVRInputError_VRInputError_NoSteam:
			return "NoSteam";
		case EVRInputError_VRInputError_WrongType:
			return "WrongType";
		}
		return "Unknown";
	}
	private static boolean dbg = true;
	private static void updatePose()
	{
		if ( OpenVR.VRSystem == null || OpenVR.VRCompositor == null )
			return;

		int ret = VRCompositor_WaitGetPoses(hmdTrackedDevicePoses, null);

		if (ret>0)
			System.out.println("Compositor Error: GetPoseError " + OpenVRStereoRenderer.getCompostiorError(ret)); 

		if(ret == 101){ //this is so dumb but it works.
			triggerHapticPulse(0, 500);
			triggerHapticPulse(1, 500);
		}

		if (getXforms == true) { //set null by events.
			//dbg = true;
			getTransforms(); //do we want the dynamic info? I don't think so...
			//findControllerDevices();
		} else {
			if (dbg) {
				dbg = false;
				debugOut(0);
				debugOut(controllerDeviceIndex[0]);
				debugOut(controllerDeviceIndex[1]);
			}
		}
		
		HmdMatrix34 matL = HmdMatrix34.create();
		VRSystem_GetEyeToHeadTransform(EVREye_Eye_Left, matL);
		OpenVRUtil.convertSteamVRMatrix3ToMatrix4f(matL, hmdPoseLeftEye);

		HmdMatrix34 matR = HmdMatrix34.create();
		VRSystem_GetEyeToHeadTransform(EVREye_Eye_Right, matR);
		OpenVRUtil.convertSteamVRMatrix3ToMatrix4f(matR, hmdPoseRightEye);

		for (int nDevice = 0; nDevice < k_unMaxTrackedDeviceCount; ++nDevice )
		{
			if ( hmdTrackedDevicePoses.get(nDevice).bPoseIsValid())
			{
				OpenVRUtil.convertSteamVRMatrix3ToMatrix4f(hmdTrackedDevicePoses.get(nDevice).mDeviceToAbsoluteTracking(), poseMatrices[nDevice]);
				HmdVector3 velocity = hmdTrackedDevicePoses.get(nDevice).vVelocity();
				deviceVelocity[nDevice] = new Vector3d(velocity.v(0),velocity.v(1),velocity.v(2));
			}
		}

		if (hmdTrackedDevicePoses.get(k_unTrackedDeviceIndex_Hmd).bPoseIsValid())
		{
			OpenVRUtil.Matrix4fCopy(poseMatrices[k_unTrackedDeviceIndex_Hmd], hmdPose);
			headIsTracking = true;
		}
		else
		{
			headIsTracking = false;
			OpenVRUtil.Matrix4fSetIdentity(hmdPose);
			hmdPose.M[1][3] = 1.62f;
		}
		TPose = false;
		if(TPose) {
			TPose_Right.M[0][3] = 0f;
			TPose_Right.M[1][3] = 0f;
			TPose_Right.M[2][3] = 0f;
			OpenVRUtil.Matrix4fCopy(TPose_Right.rotationY(-120), TPose_Right);
			TPose_Right.M[0][3] = 0.5f;
			TPose_Right.M[1][3] = 1.0f;
			TPose_Right.M[2][3] = -.5f;

			TPose_Left.M[0][3] = 0f;
			TPose_Left.M[1][3] = 0f;
			TPose_Left.M[2][3] = 0f;
			OpenVRUtil.Matrix4fCopy(TPose_Left.rotationY(120), TPose_Left);
			TPose_Left.M[0][3] = -.5f;
			TPose_Left.M[1][3] = 1.0f;
			TPose_Left.M[2][3] = -.5f;
			
			Neutral_HMD.M[0][3] = 0f;
			Neutral_HMD.M[1][3] = 1.8f;

			OpenVRUtil.Matrix4fCopy(Neutral_HMD, hmdPose);
			headIsTracking = true;
		}
		
		// Gotta do this here so we can get the poses
		if(inputInitialized) {
			
			mc.getProfiler().startSection("updateActionState");

				VRActiveActionSet.Buffer activeActionSets = getActiveActionSets();
				if (activeActionSets.sizeof() > 0) {
					int error = VRInput_UpdateActionState(activeActionSetsReference, activeActionSets.sizeof());
					if (error != 0)
						throw new RuntimeException("Error updating action state: code " + getInputError(error));
				}
				inputActions.values().forEach(VRInputAction::readNewData);

			mc.getProfiler().endSection();

			if (mc.vrSettings.vrReverseHands) {
				updateControllerPose(RIGHT_CONTROLLER, leftPoseHandle);
				updateControllerPose(LEFT_CONTROLLER, rightPoseHandle);
			} else {
				updateControllerPose(RIGHT_CONTROLLER, rightPoseHandle);
				updateControllerPose(LEFT_CONTROLLER, leftPoseHandle);
			}
			updateControllerPose(THIRD_CONTROLLER, externalCameraPoseHandle);
		}

		updateAim();

	}

	private static void readPoseData(long actionHandle) {
		//VRInput_GetPoseActionDataForNextFrame was introduced in OpenVR 1.4.18, which is the successor of 1.3.22 used in lwjgl 3.2.2 
		//int error = VRInput_GetPoseActionDataForNextFrame(actionHandle, ETrackingUniverseOrigin_TrackingUniverseStanding, poseData, k_ulInvalidInputValueHandle);
		//that seems to work, TODO: update when LWJGL is updated to 3.2.3
		int error = VRInput_GetPoseActionData(actionHandle, ETrackingUniverseOrigin_TrackingUniverseStanding, 0.0f, poseData, k_ulInvalidInputValueHandle);
		if (error != 0)
			throw new RuntimeException("Error reading pose data: " + getInputError(error));
	}

	private static void readOriginInfo(long inputValueHandle) {
		int error = VRInput_GetOriginTrackedDeviceInfo(inputValueHandle, originInfo);
		if (error != 0)
			throw new RuntimeException("Error reading origin info: " + getInputError(error));
	}

	public static InputOriginInfo getOriginInfo(long inputValueHandle) {
		readOriginInfo(inputValueHandle);
		//TODO: maybe rewrite readOriginInfo() so that it directly returns the instance (since the class variable originInfo is only used here)
		InputOriginInfo originInfoT = InputOriginInfo.create();
		//necessary to create a copy of originInfo, because the java class InputOriginInfo only has getters for it's values
		memCopy(originInfo, originInfoT);
		return originInfoT;
	}

	public static String getOriginName(long handle) {
		ByteBuffer originName = ByteBuffer.allocateDirect(k_unMaxPropertyStringSize);
		int error = VRInput_GetOriginLocalizedName(handle, originName, EVRInputStringBits_VRInputString_All);
		if (error != 0)
			throw new RuntimeException("Error getting origin name: " + MCOpenVR.getInputError(error));
		//TODO: maybe use memUTF8 instead.
		return memASCIISafe(originName);
	}

	public static ControllerType getOriginControllerType(long inputValueHandle) {
		if (inputValueHandle == k_ulInvalidInputValueHandle)
			return null;
		readOriginInfo(inputValueHandle);
		if (originInfo.trackedDeviceIndex() != k_unTrackedDeviceIndexInvalid) {
			if (originInfo.trackedDeviceIndex() == controllerDeviceIndex[RIGHT_CONTROLLER])
				return ControllerType.RIGHT;
			else if (originInfo.trackedDeviceIndex() == controllerDeviceIndex[LEFT_CONTROLLER])
				return ControllerType.LEFT;
		}
		return null;
	}

	private static void updateControllerPose(int controller, long actionHandle) {
	
		if(TPose) {
			if(controller == 0) {
				OpenVRUtil.Matrix4fCopy(TPose_Right, controllerPose[controller]);
			}
			else if(controller == 1) {
				OpenVRUtil.Matrix4fCopy(TPose_Left, controllerPose[controller]);		
			}
			controllerTracking[controller] = true;
			return;
		}
		
		readPoseData(actionHandle);
		if (poseData.activeOrigin() != k_ulInvalidInputValueHandle) {
			readOriginInfo(poseData.activeOrigin());
			int deviceIndex = originInfo.trackedDeviceIndex();
			if (deviceIndex != controllerDeviceIndex[controller])
				getXforms = true;
			controllerDeviceIndex[controller] = deviceIndex;
			if (deviceIndex != k_unTrackedDeviceIndexInvalid) {
				TrackedDevicePose pose = poseData.pose();
				if (pose.bPoseIsValid()) {
					OpenVRUtil.convertSteamVRMatrix3ToMatrix4f(pose.mDeviceToAbsoluteTracking(), poseMatrices[deviceIndex]);
					deviceVelocity[deviceIndex] = new Vector3d(pose.vVelocity().v(0), pose.vVelocity().v(1), pose.vVelocity().v(2));
					OpenVRUtil.Matrix4fCopy(poseMatrices[deviceIndex], controllerPose[controller]);

					controllerTracking[controller] = true;
					return; // controller is tracking, don't execute the code below
				}
			}
		} else {
			controllerDeviceIndex[controller] = k_unTrackedDeviceIndexInvalid;
		}

		//OpenVRUtil.Matrix4fSetIdentity(controllerPose[controller]);
		controllerTracking[controller] = false;
		

	}

	public static boolean isControllerTracking(int controller) {
		return controllerTracking[controller];
	}

	public static boolean isControllerTracking(ControllerType controller) {
		return isControllerTracking(controller.ordinal());
	}

	// Code duplication to reduce garbage
	public static float getAxis1D(VRInputAction action) {
		if (axisUseTracker.getOrDefault(action.keyBinding.getKeyDescription(), false) || action.isEnabled()) {
			float axis = action.getAxis1D(false);
			boolean used = axis != 0;
			axisUseTracker.put(action.keyBinding.getKeyDescription(), used);
			return axis;
		}
		return 0;
	}

	public static Vector2 getAxis2D(VRInputAction action) {
		if (axisUseTracker.getOrDefault(action.keyBinding.getKeyDescription(), false) || action.isEnabled()) {
			Vector2 axis = action.getAxis2D(false);
			boolean used = axis.getX() != 0 || axis.getY() != 0;
			axisUseTracker.put(action.keyBinding.getKeyDescription(), used);
			return axis;
		}
		return new Vector2();
	}

	public static Vector3 getAxis3D(VRInputAction action) {
		if (axisUseTracker.getOrDefault(action.keyBinding.getKeyDescription(), false) || action.isEnabled()) {
			Vector3 axis = action.getAxis3D(false);
			boolean used = axis.getX() != 0 || axis.getY() != 0 || axis.getZ() != 0;
			axisUseTracker.put(action.keyBinding.getKeyDescription(), used);
			return axis;
		}
		return new Vector3();
	}
	// Weeeeee

	/**
	 * @return The coordinate of the 'center' eye position relative to the head yaw plane
	 */

	public static Vector3d getCenterEyePosition() {
		Vector3 pos = OpenVRUtil.convertMatrix4ftoTranslationVector(hmdPose);
		if (mc.vrSettings.seated || mc.vrSettings.allowStandingOriginOffset)
			pos=pos.add(offset);
		return pos.toVector3d();
	}

	/**
	 * @return The coordinate of the left or right eye position relative to the head yaw plane
	 */

	public static Vector3d getEyePosition(RenderPass eye)
	{
		Matrix4f hmdToEye = hmdPoseRightEye;
		if ( eye == RenderPass.LEFT)
		{
			hmdToEye = hmdPoseLeftEye;
		} else if ( eye == RenderPass.RIGHT)
		{
			hmdToEye = hmdPoseRightEye;
		} else {
			hmdToEye = null;
		}

		if(hmdToEye == null){
			Matrix4f pose = hmdPose;
			Vector3 pos = OpenVRUtil.convertMatrix4ftoTranslationVector(pose);
			if (mc.vrSettings.seated || mc.vrSettings.allowStandingOriginOffset)
				pos=pos.add(offset);
			return pos.toVector3d();
		} else {
			Matrix4f pose = Matrix4f.multiply( hmdPose, hmdToEye );
			Vector3 pos = OpenVRUtil.convertMatrix4ftoTranslationVector(pose);
			if (mc.vrSettings.seated || mc.vrSettings.allowStandingOriginOffset)
				pos=pos.add(offset);
			return pos.toVector3d();
		}
	}

	public static Matrix4f getEyeRotation(RenderPass eye)
	{
		Matrix4f hmdToEye;
		if ( eye == RenderPass.LEFT) {
			hmdToEye = hmdPoseLeftEye;
		} else if ( eye == RenderPass.RIGHT) {
			hmdToEye = hmdPoseRightEye;
		} else {
			hmdToEye = null;
		}

		if (hmdToEye != null) {
			Matrix4f eyeRot = new Matrix4f();
			eyeRot.M[0][0] = hmdToEye.M[0][0];
			eyeRot.M[0][1] = hmdToEye.M[0][1];
			eyeRot.M[0][2] = hmdToEye.M[0][2];
			eyeRot.M[0][3] = 0.0F;
			eyeRot.M[1][0] = hmdToEye.M[1][0];
			eyeRot.M[1][1] = hmdToEye.M[1][1];
			eyeRot.M[1][2] = hmdToEye.M[1][2];
			eyeRot.M[1][3] = 0.0F;
			eyeRot.M[2][0] = hmdToEye.M[2][0];
			eyeRot.M[2][1] = hmdToEye.M[2][1];
			eyeRot.M[2][2] = hmdToEye.M[2][2];
			eyeRot.M[2][3] = 0.0F;
			eyeRot.M[3][0] = 0.0F;
			eyeRot.M[3][1] = 0.0F;
			eyeRot.M[3][2] = 0.0F;
			eyeRot.M[3][3] = 1.0F;

			return Matrix4f.multiply(hmdRotation, eyeRot);
		} else {
			return hmdRotation;
		}
	}

	/**
	 *
	 * @return Play area size or null if not valid
	 */
	public static float[] getPlayAreaSize() {
		if (OpenVR.VRChaperone == null || OpenVR.VRChaperone.GetPlayAreaSize == 0L) return null;
		FloatBuffer bufx = BufferUtils.createFloatBuffer(1);
		FloatBuffer bufz = BufferUtils.createFloatBuffer(1);
		boolean valid = VRChaperone_GetPlayAreaSize(bufx, bufz);
		if (valid) return new float[]{bufx.get(0)*mc.vrSettings.walkMultiplier, bufz.get(0)*mc.vrSettings.walkMultiplier};
		return null;
	}

	/**
	 * Gets the orientation quaternion
	 *
	 * @return quaternion w, x, y & z components
	 */

	static Angle getOrientationEuler()
	{
		Quaternion orient = OpenVRUtil.convertMatrix4ftoRotationQuat(hmdPose);
		return orient.toEuler();
	}

	final String k_pch_SteamVR_Section = "steamvr";
	final String k_pch_SteamVR_RenderTargetMultiplier_Float = "renderTargetMultiplier";



	//-------------------------------------------------------
	// IBodyAimController

	float getBodyPitchDegrees() {
		return 0; //Always return 0 for body pitch
	}

	public static Vector3d getAimVector( int controller ) {
		Vector3 v = controllerRotation[controller].transform(forward);
		return v.toVector3d();

	}

	public static Vector3d getHmdVector() {
		Vector3 v = hmdRotation.transform(forward);
		return v.toVector3d();
	}

	public static Vector3d getHandVector( int controller ) {
		Vector3 forward = new Vector3(0,0,-1);
		Matrix4f aimRotation = handRotation[controller];
		Vector3 controllerDirection = aimRotation.transform(forward);
		return controllerDirection.toVector3d();
	}

	public static Matrix4f getAimRotation( int controller ) {
		return controllerRotation[controller];
	}

	public static Matrix4f getHandRotation( int controller ) {
		return handRotation[controller];
	}


	public static Vector3d getAimSource( int controller ) {
		Vector3d out = new Vector3d(aimSource[controller].x, aimSource[controller].y, aimSource[controller].z);
		if(!mc.vrSettings.seated && mc.vrSettings.allowStandingOriginOffset)
			out = out.add(offset.getX(), offset.getY(), offset.getZ());
		return out;
	}

	public static void triggerHapticPulse(ControllerType controller, float durationSeconds, float frequency, float amplitude, float delaySeconds) {
		if (mc.vrSettings.seated || !inputInitialized) return;
		if (mc.vrSettings.vrReverseHands) {
			if (controller == ControllerType.RIGHT)
				controller = ControllerType.LEFT;
			else
				controller = ControllerType.RIGHT;
		}

		hapticScheduler.queueHapticPulse(controller, durationSeconds, frequency, amplitude, delaySeconds);
	}

	public static void triggerHapticPulse(ControllerType controller, float durationSeconds, float frequency, float amplitude) {
		triggerHapticPulse(controller, durationSeconds, frequency, amplitude, 0);
	}
	
	@Deprecated
	public static void triggerHapticPulse(ControllerType controller, int strength) {
		if (strength < 1) return;
		// Through careful analysis of the haptics in the legacy API (read: I put the controller to
		// my ear, listened to the vibration, and reproduced the frequency in Audacity), I have determined
		// that the old haptics used 160Hz. So, these parameters will match the "feel" of the old haptics.
		triggerHapticPulse(controller, strength / 1000000f, 160, 1);
	}

	@Deprecated
	public static void triggerHapticPulse(int controller, int strength) {
		if (controller < 0 || controller >= ControllerType.values().length) return;
		triggerHapticPulse(ControllerType.values()[controller], strength);
	}

	public static float seatedRot;

	public static Vector3 forward = new Vector3(0,0,-1);
	public static Vector3 up = new Vector3(0,1,0);

	static double aimPitch = 0; //needed for seated mode.


	private static void updateAim() {
		if (mc==null)
			return;

		{//hmd
			hmdRotation.M[0][0] = hmdPose.M[0][0];
			hmdRotation.M[0][1] = hmdPose.M[0][1];
			hmdRotation.M[0][2] = hmdPose.M[0][2];
			hmdRotation.M[0][3] = 0.0F;
			hmdRotation.M[1][0] = hmdPose.M[1][0];
			hmdRotation.M[1][1] = hmdPose.M[1][1];
			hmdRotation.M[1][2] = hmdPose.M[1][2];
			hmdRotation.M[1][3] = 0.0F;
			hmdRotation.M[2][0] = hmdPose.M[2][0];
			hmdRotation.M[2][1] = hmdPose.M[2][1];
			hmdRotation.M[2][2] = hmdPose.M[2][2];
			hmdRotation.M[2][3] = 0.0F;
			hmdRotation.M[3][0] = 0.0F;
			hmdRotation.M[3][1] = 0.0F;
			hmdRotation.M[3][2] = 0.0F;
			hmdRotation.M[3][3] = 1.0F;


			Vector3d eye = getCenterEyePosition();
			hmdHistory.add(eye);
			Vector3 v3 = MCOpenVR.hmdRotation.transform(new Vector3(0,-.1f, .1f));
			hmdPivotHistory.add(new Vector3d(v3.getX()+eye.x, v3.getY()+eye.y, v3.getZ()+eye.z));

		}
		
		if(mc.vrSettings.seated){
			controllerPose[0] = hmdPose.inverted().inverted();
			controllerPose[1] = hmdPose.inverted().inverted();
		}
		
		Matrix4f[] controllerPoseTip = new Matrix4f[2];
		controllerPoseTip[0] = new Matrix4f();
		controllerPoseTip[1] = new Matrix4f();
		Matrix4f[] controllerPoseHand = new Matrix4f[2];
		controllerPoseHand[0] = new Matrix4f();
		controllerPoseHand[1] = new Matrix4f();

		{//right controller
			if(mc.vrSettings.seated)
				controllerPoseHand[0] = controllerPose[0];
			 else	
				controllerPoseHand[0] = Matrix4f.multiply(controllerPose[0], getControllerComponentTransform(0,"handgrip"));

			handRotation[0].M[0][0] = controllerPoseHand[0].M[0][0];
			handRotation[0].M[0][1] = controllerPoseHand[0].M[0][1];
			handRotation[0].M[0][2] = controllerPoseHand[0].M[0][2];
			handRotation[0].M[0][3] = 0.0F;
			handRotation[0].M[1][0] = controllerPoseHand[0].M[1][0];
			handRotation[0].M[1][1] = controllerPoseHand[0].M[1][1];
			handRotation[0].M[1][2] = controllerPoseHand[0].M[1][2];
			handRotation[0].M[1][3] = 0.0F;
			handRotation[0].M[2][0] = controllerPoseHand[0].M[2][0];
			handRotation[0].M[2][1] = controllerPoseHand[0].M[2][1];
			handRotation[0].M[2][2] = controllerPoseHand[0].M[2][2];
			handRotation[0].M[2][3] = 0.0F;
			handRotation[0].M[3][0] = 0.0F;
			handRotation[0].M[3][1] = 0.0F;
			handRotation[0].M[3][2] = 0.0F;
			handRotation[0].M[3][3] = 1.0F;	

			if(mc.vrSettings.seated)
				controllerPoseTip[0] = controllerPose[0];
			 else	
				controllerPoseTip[0] = Matrix4f.multiply(controllerPose[0], getControllerComponentTransform(0,"tip"));

			// grab controller position in tracker space, scaled to minecraft units
			Vector3 controllerPos = OpenVRUtil.convertMatrix4ftoTranslationVector(controllerPoseTip[0]);
			aimSource[0] = controllerPos.toVector3d();

			controllerHistory[0].add(getAimSource(0));

			// build matrix describing controller rotation
			controllerRotation[0].M[0][0] = controllerPoseTip[0].M[0][0];
			controllerRotation[0].M[0][1] = controllerPoseTip[0].M[0][1];
			controllerRotation[0].M[0][2] = controllerPoseTip[0].M[0][2];
			controllerRotation[0].M[0][3] = 0.0F;
			controllerRotation[0].M[1][0] = controllerPoseTip[0].M[1][0];
			controllerRotation[0].M[1][1] = controllerPoseTip[0].M[1][1];
			controllerRotation[0].M[1][2] = controllerPoseTip[0].M[1][2];
			controllerRotation[0].M[1][3] = 0.0F;
			controllerRotation[0].M[2][0] = controllerPoseTip[0].M[2][0];
			controllerRotation[0].M[2][1] = controllerPoseTip[0].M[2][1];
			controllerRotation[0].M[2][2] = controllerPoseTip[0].M[2][2];
			controllerRotation[0].M[2][3] = 0.0F;
			controllerRotation[0].M[3][0] = 0.0F;
			controllerRotation[0].M[3][1] = 0.0F;
			controllerRotation[0].M[3][2] = 0.0F;
			controllerRotation[0].M[3][3] = 1.0F;

			Vector3d hdir = getHmdVector();

			if(mc.vrSettings.seated && mc.currentScreen == null){
				org.vivecraft.utils.lwjgl.Matrix4f temp = new org.vivecraft.utils.lwjgl.Matrix4f();

				float hRange = 110;
				float vRange = 180;
				double h = mc.mouseHelper.getMouseX() / (double) mc.getMainWindow().getWidth() * hRange - (hRange / 2);
			
				//h = MathHelper.clamp(h, -hRange/2, hRange/2);

				int hei  = mc.getMainWindow().getHeight();
				if(hei % 2 != 0)
					hei-=1; //fix drifting vertical mouse.

				double v = -mc.mouseHelper.getMouseY() / (double) hei * vRange + (vRange / 2);		

				double nPitch=-v;
				if(mc.isGameFocused()){
					float rotStart = mc.vrSettings.keyholeX;
					float rotSpeed = 1000 * mc.vrSettings.xSensitivity;
					int leftedge=(int)((-rotStart + (hRange / 2)) *(double) mc.getMainWindow().getWidth() / hRange )+1;
					int rightedge=(int)((rotStart + (hRange / 2)) *(double) mc.getMainWindow().getWidth() / hRange )-1;
					float rotMul = ((float)Math.abs(h) - rotStart) / ((hRange / 2) - rotStart); // Scaled 0...1 from rotStart to FOV edge
					//if(rotMul > 0.15) rotMul = 0.15f;

					double xpos = mc.mouseHelper.getMouseX();
					
					if(h < -rotStart){
						seatedRot += rotSpeed * rotMul * mc.getFrameDelta();
  						seatedRot %= 360; // Prevent stupidly large values
						hmdForwardYaw = (float)Math.toDegrees(Math.atan2(hdir.x, hdir.z));   
						xpos = leftedge;
						h=-rotStart;
					}
					if(h > rotStart){
						seatedRot -= rotSpeed * rotMul * mc.getFrameDelta();
						seatedRot %= 360; // Prevent stupidly large values
						hmdForwardYaw = (float)Math.toDegrees(Math.atan2(hdir.x, hdir.z));    	
						xpos = rightedge;
						h=rotStart;
					}

					double ySpeed=0.5 * mc.vrSettings.ySensitivity;
					nPitch=aimPitch+(v)*ySpeed;
					nPitch=MathHelper.clamp(nPitch,-89.9,89.9);
					
					InputSimulator.setMousePos(xpos, hei/2);
					GLFW.glfwSetCursorPos(mc.getMainWindow().getHandle(), xpos, hei/2);

					temp.rotate((float) Math.toRadians(-nPitch), new org.vivecraft.utils.lwjgl.Vector3f(1,0,0));
					temp.rotate((float) Math.toRadians(-180 + h - hmdForwardYaw), new org.vivecraft.utils.lwjgl.Vector3f(0,1,0));
				}


				controllerRotation[0].M[0][0] = temp.m00;
				controllerRotation[0].M[0][1] = temp.m01;
				controllerRotation[0].M[0][2] = temp.m02;

				controllerRotation[0].M[1][0] = temp.m10;
				controllerRotation[0].M[1][1] = temp.m11;
				controllerRotation[0].M[1][2] = temp.m12;

				controllerRotation[0].M[2][0] = temp.m20;
				controllerRotation[0].M[2][1] = temp.m21;
				controllerRotation[0].M[2][2] = temp.m22;
			}	

			Vector3d dir = getAimVector(0);
			aimPitch = (float)Math.toDegrees(Math.asin(dir.y/dir.length()));
			controllerForwardHistory[0].add(dir);
			Vector3d updir = 	controllerRotation[0].transform(up).toVector3d();
			controllerUpHistory[0].add(updir);
		}

		{//left controller
			
			if(mc.vrSettings.seated)
				controllerPoseHand[1] = controllerPose[1];
			 else	
				controllerPoseHand[1] = Matrix4f.multiply(controllerPose[1], getControllerComponentTransform(1,"handgrip"));

			handRotation[1].M[0][0] = controllerPoseHand[1].M[0][0];
			handRotation[1].M[0][1] = controllerPoseHand[1].M[0][1];
			handRotation[1].M[0][2] = controllerPoseHand[1].M[0][2];
			handRotation[1].M[0][3] = 0.0F;
			handRotation[1].M[1][0] = controllerPoseHand[1].M[1][0];
			handRotation[1].M[1][1] = controllerPoseHand[1].M[1][1];
			handRotation[1].M[1][2] = controllerPoseHand[1].M[1][2];
			handRotation[1].M[1][3] = 0.0F;
			handRotation[1].M[2][0] = controllerPoseHand[1].M[2][0];
			handRotation[1].M[2][1] = controllerPoseHand[1].M[2][1];
			handRotation[1].M[2][2] = controllerPoseHand[1].M[2][2];
			handRotation[1].M[2][3] = 0.0F;
			handRotation[1].M[3][0] = 0.0F;
			handRotation[1].M[3][1] = 0.0F;
			handRotation[1].M[3][2] = 0.0F;
			handRotation[1].M[3][3] = 1.0F;	

			// update off hand aim
			if(mc.vrSettings.seated)
				controllerPoseTip[1] = controllerPose[1];
			else
				controllerPoseTip[1] = Matrix4f.multiply(controllerPose[1], getControllerComponentTransform(1,"tip"));

			Vector3 leftControllerPos = OpenVRUtil.convertMatrix4ftoTranslationVector(controllerPoseTip[1]);
			aimSource[1] = leftControllerPos.toVector3d();
			controllerHistory[1].add(getAimSource(1));

			// build matrix describing controller rotation
			controllerRotation[1].M[0][0] = controllerPoseTip[1].M[0][0];
			controllerRotation[1].M[0][1] = controllerPoseTip[1].M[0][1];
			controllerRotation[1].M[0][2] = controllerPoseTip[1].M[0][2];
			controllerRotation[1].M[0][3] = 0.0F;
			controllerRotation[1].M[1][0] = controllerPoseTip[1].M[1][0];
			controllerRotation[1].M[1][1] = controllerPoseTip[1].M[1][1];
			controllerRotation[1].M[1][2] = controllerPoseTip[1].M[1][2];
			controllerRotation[1].M[1][3] = 0.0F;
			controllerRotation[1].M[2][0] = controllerPoseTip[1].M[2][0];
			controllerRotation[1].M[2][1] = controllerPoseTip[1].M[2][1];
			controllerRotation[1].M[2][2] = controllerPoseTip[1].M[2][2];
			controllerRotation[1].M[2][3] = 0.0F;
			controllerRotation[1].M[3][0] = 0.0F;
			controllerRotation[1].M[3][1] = 0.0F;
			controllerRotation[1].M[3][2] = 0.0F;
			controllerRotation[1].M[3][3] = 1.0F;
			
			Vector3d dir = getAimVector(1);
			controllerForwardHistory[1].add(dir);
			Vector3d updir = 	controllerRotation[1].transform(up).toVector3d();
			controllerUpHistory[1].add(updir);
			
			if(mc.vrSettings.seated){
				aimSource[1] = getCenterEyePosition();
				aimSource[0] = getCenterEyePosition();
			}

		}

		boolean debugThirdController = false;
		if(debugThirdController) controllerPose[2] = controllerPose[0];

		// build matrix describing controller rotation
		controllerRotation[2].M[0][0] = controllerPose[2].M[0][0];
		controllerRotation[2].M[0][1] = controllerPose[2].M[0][1];
		controllerRotation[2].M[0][2] = controllerPose[2].M[0][2];
		controllerRotation[2].M[0][3] = 0.0F;
		controllerRotation[2].M[1][0] = controllerPose[2].M[1][0];
		controllerRotation[2].M[1][1] = controllerPose[2].M[1][1];
		controllerRotation[2].M[1][2] = controllerPose[2].M[1][2];
		controllerRotation[2].M[1][3] = 0.0F;
		controllerRotation[2].M[2][0] = controllerPose[2].M[2][0];
		controllerRotation[2].M[2][1] = controllerPose[2].M[2][1];
		controllerRotation[2].M[2][2] = controllerPose[2].M[2][2];
		controllerRotation[2].M[2][3] = 0.0F;
		controllerRotation[2].M[3][0] = 0.0F;
		controllerRotation[2].M[3][1] = 0.0F;
		controllerRotation[2].M[3][2] = 0.0F;
		controllerRotation[2].M[3][3] = 1.0F;

		if(controllerDeviceIndex[THIRD_CONTROLLER]!=-1 && (mc.vrSettings.displayMirrorMode == VRSettings.MIRROR_MIXED_REALITY || mc.vrSettings.displayMirrorMode == VRSettings.MIRROR_THIRD_PERSON )|| debugThirdController) {
			mrMovingCamActive = true;
			Vector3 thirdControllerPos = OpenVRUtil.convertMatrix4ftoTranslationVector(controllerPose[2]);
			aimSource[2] = thirdControllerPos.toVector3d();
		} else {
			mrMovingCamActive = false;
			aimSource[2] = new Vector3d(
					mc.vrSettings.vrFixedCamposX,
					mc.vrSettings.vrFixedCamposY,
					mc.vrSettings.vrFixedCamposZ);
		}


	}

	public static double getCurrentTimeSecs()
	{
		return System.nanoTime() / 1000000000d;
	}

	public static HardwareType getHardwareType() {
		return mc.vrSettings.forceHardwareDetection > 0 ? HardwareType.values()[mc.vrSettings.forceHardwareDetection - 1] : detectedHardware;
	}

	private static boolean gunStyle = false; 
	private static double gunAngle = 0; 

	public static boolean isGunStyle() {
		return gunStyle;
	}
	
	public static double getGunAngle() {
		//return 40;
		return gunAngle;
	}

	public static void resetPosition() {
		Vector3d pos= getCenterEyePosition().scale(-1).add(offset.getX(),offset.getY(),offset.getZ());
		offset=new Vector3((float) pos.x,(float)pos.y+1.62f,(float)pos.z);
	}

	public static void clearOffset() {
		offset=new Vector3(0,0,0);
	}

	public static void setVanillaBindings(KeyBinding[] bindings) {
		vanillaBindingSet = new HashSet<>(Arrays.asList(bindings));
	}

	public static boolean isSafeBinding(KeyBinding kb) {
		// Stupid hard-coded junk
		return getKeyBindings().contains(kb) || kb == mc.gameSettings.keyBindChat || kb == mc.gameSettings.keyBindInventory;
	}

	public static boolean isModBinding(KeyBinding kb) {
		return !vanillaBindingSet.contains(kb);
	}

	public static boolean isHMDTracking() {
		return headIsTracking;
	}
}
