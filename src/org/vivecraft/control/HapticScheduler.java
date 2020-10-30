package org.vivecraft.control;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.lwjgl.openvr.*;

import static org.lwjgl.openvr.VR.*;
import static org.lwjgl.openvr.VRInput.*;

import org.vivecraft.provider.MCOpenVR;

public class HapticScheduler {
	private ScheduledExecutorService executor;

	public HapticScheduler() {
		executor = Executors.newSingleThreadScheduledExecutor();
	}

	private void triggerHapticPulse(ControllerType controller, float durationSeconds, float frequency, float amplitude) {
		int error = VRInput_TriggerHapticVibrationAction(MCOpenVR.getHapticHandle(controller), 0, durationSeconds, frequency, amplitude, k_ulInvalidInputValueHandle);
		if (error != 0)
			System.out.println("Error triggering haptic: " + MCOpenVR.getInputError(error));
	}

	public void queueHapticPulse(ControllerType controller, float durationSeconds, float frequency, float amplitude, float delaySeconds) {
		executor.schedule(() -> triggerHapticPulse(controller, durationSeconds, frequency, amplitude), (long)(delaySeconds * 1000000), TimeUnit.MICROSECONDS);
	}
}
