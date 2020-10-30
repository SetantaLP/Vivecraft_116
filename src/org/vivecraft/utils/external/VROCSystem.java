package org.vivecraft.utils.external;

import org.lwjgl.system.NativeType;

import static org.lwjgl.system.Checks.*;
import static org.lwjgl.system.JNI.*;

/**
 * derived from {@link https://gitlab.com/znixian/OpenOVR/-/blob/master/OpenOVR/API/ISystem_001.h}
 */
public class VROCSystem {
	
    static { OpenComposite.initialize(); }
    
    public static final String IVROCSystem_Version = "IVROCSystem_001";
    public static final int EVRExtendedButtonId_k_EButton_OVRMenu = 0;
    
    protected VROCSystem() {
        throw new UnsupportedOperationException();
    }
    
    
    // --- [ VROCSystem_GetExtendedButtonStatus ] ---

    /** Unsafe version of: {@link #VROCSystem_GetExtendedButtonStatus GetExtendedButtonStatus} */
    public static long nVROCSystem_GetExtendedButtonStatus() {
        long __functionAddress = OpenComposite.VROCSystem.GetExtendedButtonStatus;
        if (CHECKS) {
            check(__functionAddress);
        }
        return callJ(__functionAddress);
    }

    /**
	* Gets the extended button status
	*
	* This is for buttons inaccessable from OpenVR - currently, this is just the menu
	*  button (the left Touch's seperate menu button, not any of the four standard buttons).
	*
	* This is packed in the same manner as OpenVR's button values, so you need to use ButtonMaskFromId
	* to get the values out (you'll have to cast EVRExtendedButtonId to EVRButtonId though).
	*
	* Eg. if(sys->GetExtendedButtonStatus() & ButtonMaskFromId((EVRButtonId) k_EButton_OVRMenu)) ...
	*/
    @NativeType("uint64_t")
	public long VROCSystem_GetExtendedButtonStatus() {
    	return nVROCSystem_GetExtendedButtonStatus();
	}

}
