package org.vivecraft.utils.external;

import static org.lwjgl.openvr.VR.*;
import static org.lwjgl.system.MemoryStack.*;
import static org.lwjgl.system.MemoryUtil.*;
import static org.vivecraft.utils.external.VROCSystem.*;

import java.nio.IntBuffer;
import java.util.function.LongFunction;

import javax.annotation.Nullable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.PointerBuffer;
import org.lwjgl.openvr.OpenVR;
import org.lwjgl.system.MemoryStack;

/**
 * Created by SetantaLP
 * adapted version of org.lwjgl.openvr.OpenVR for OpenComposite
 */
public final class OpenComposite {
    private static final Logger LOGGER = LogManager.getLogger();

    @Nullable public static IVROCSystem VROCSystem;
    
    private static int token;

    private OpenComposite() {
    }
    
    static void initialize() {
        // intentionally empty to trigger static initializer
    }
    
    public static void create(int token) {
    	try {
    		//To make sure that the OpenVR class (that loads the openvr_api.dll (windows)/libopenvr_api.so (linux)) has been loaded
    		//calling OpenVR.initialize() would be better, but thats package private.
	     	OpenVR.checkInitToken();
    	}catch (IllegalStateException e) {
    		
		}
    	OpenComposite.token = token;
	     
    	VROCSystem = getGenericInterface(IVROCSystem_Version, IVROCSystem::new);
    }
    
    @Nullable
    private static <T> T getGenericInterface(String interfaceNameVersion, LongFunction<T> supplier) {
        try (MemoryStack stack = stackPush()) {
            IntBuffer peError = stack.mallocInt(1);
            long ivr = VR_GetGenericInterface("FnTable:" + interfaceNameVersion, peError);
            return ivr != NULL && peError.get(0) == EVRInitError_VRInitError_None ? supplier.apply(ivr) : null;
        }
    }
    
    public static void checkInitToken() {
        if (token == 0) {
            throw new IllegalStateException("The OpenVR API must be initialized first.");
        }

        int initToken = VR_GetInitToken();
        if (token != initToken) {
            destroy();
            create(initToken);
        }
    }
    
    public static void destroy() {
        token = 0;
        
        VROCSystem = null;
    }
    
    public static final class IVROCSystem {

        public final long GetExtendedButtonStatus;
    
        public IVROCSystem(long tableAddress) {
            PointerBuffer table = memPointerBuffer(tableAddress, 1);
            
            GetExtendedButtonStatus = table.get(0);
        }
    }
    
}
