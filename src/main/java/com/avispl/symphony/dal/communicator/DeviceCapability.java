package com.avispl.symphony.dal.communicator;

public enum DeviceCapability {
    NETWORK_INTERFACE,
    STEREO_AUDIO_OUTPUT,
    NETWORK_PORT,
    NETWORK_SWITCH,
    HDMI_DECODER,
    SCALER,
    HDMI_AUDIO,
    HDMI,
    STEREO_AUDIO,
    MULTICH_AUDIO,
    HDMI_ENCODER;

    public static boolean capabilitySupported(String capability) {
        try {
            DeviceCapability.valueOf(capability);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
