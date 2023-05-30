/*
 * Copyright (c) 2023 AVI-SPL, Inc. All Rights Reserved.
 */
package com.avispl.symphony.dal.communicator;

import java.util.Arrays;
import java.util.List;

public class Constants {
    public static final List<String> RESERVED_IO_TYPES = Arrays.asList("HDMI", "RS232", "CEC", "USB", "HID");
    public static final List<String> baudRateArray = Arrays.asList("2400", "4800", "9600", "19200", "38400", "57600", "76800", "115200");
    public static final List<String> dataStartBitsArray = Arrays.asList("6", "7", "8");
    public static final List<String> dataStopBitsArray = Arrays.asList("1", "2");
    public static final List<String> parityArray = Arrays.asList("NONE", "ODD", "EVEN");
    public static final List<String> cdVersions = Arrays.asList("Version 1", "Version 2");
    public static final List<String> HDMI_TX_MODES = Arrays.asList("AUTO", "LOW");
    public static final List<String> HDMI_OUTPUT_MODES = Arrays.asList("FOLLOW_SINK_1", "FOLLOW_SINK_2", "FOLLOW_SOURCE");
    // Node types
    public static final String UART = "UART";
    public static final String STEREO_AUDIO_OUTPUT = "STEREO_AUDIO_OUTPUT";
    public static final String HDMI_ENCODER = "HDMI_ENCODER";
    public static final String HDMI_DECODER = "HDMI_DECODER";
    public static final String LED = "LED";
    public static final String VIDEO_COMPRESSOR = "VIDEO_COMPRESSOR";
    public static final String VIDEO_DECOMPRESSOR = "VIDEO_DECOMPRESSOR";
    public static final String TRANSMITTER = "TRANSMITTER";
    public static final String HDMI = "HDMI";
    public static final String SCALER = "SCALER";

    // Groups
    public static final String STREAM = "Stream";


    // Keys
    public static final String BAUD_RATE_KEY = "nodes[UART:%s].configuration.baud_rate";
    public static final String DATA_BITS_KEY = "nodes[UART:%s].configuration.data_bits";
    public static final String PARITY_KEY = "nodes[UART:%s].configuration.parity";
    public static final String STOP_BITS_KEY = "nodes[UART:%s].configuration.stop_bits";
    public static final String DEVICE_NAME_KEY = "configuration.device_name";
    public static final String LOCATE_MODE_KEY = "configuration.locate_mode";
    public static final String RESUME_STREAMING_KEY = "configuration.resume_streaming";
    public static final String HDCP_OUTPUT_MODE_KEY = "nodes[%s].configuration.hdcp_output_mode";
    public static final String VIDEO_COMPRESSOR_VERSION_KEY = "nodes[VIDEO_COMPRESSOR:%s].configuration.version";
    public static final String VIDEO_DECOMPRESSOR_VERSION_KEY = "nodes[VIDEO_DECOMPRESSOR:%s].configuration.version";
    public static final String HDMI_TX_5V_KEY = "nodes[%s].configuration.hdmi_tx_5v";
    public static final String NODE_INPUTS_KEY = "nodes[%s].inputs[%s].configuration.source.value";

    // Properties
    public static final String REBOOT = "Reboot";
    public static final String DEVICE_NAME = "Configuration#DeviceName";
    public static final String HDMI_OUTPUT_MODE = "HDMIEncoder#HDCPOutputMode";
    public static final String HDMI_STEREO_AUDIO_SOURCE = "HDMIEncoder#StereoAudioSource";
    public static final String HDMI_AUDIO_SOURCE = "HDMIEncoder#AudioSource";
    public static final String VIDEO_COMPRESSOR_VERSION = "VideoCompressorVersion";
    public static final String VIDEO_DECOMPRESSOR_VERSION = "VideoDecompressorVersion";
    public static final String LED_FUNCTION = "Configuration#LEDFunction";
    public static final String HDMI_TX5v = "HDMIEncoder#HDMITX5v";
    public static final String BAUD_NAME_PROPERTY = "#BaudRate";
    public static final String DATA_BITS_PROPERTY = "#DataBits";
    public static final String STOP_BITS_PROPERTY = "#StopBits";
    public static final String PARITY_PROPERTY = "#Parity";
    public static final String BAUD_RATE = "BaudRate";
    public static final String DATA_BITS = "DataBits";
    public static final String PARITY = "Parity";
    public static final String STOP_BITS = "StopBits";
    public static final String NATIVE_VIDEO_STREAM_STATE = "NativeVideoStreamState";
    public static final String SCALED_VIDEO_STREAM_STATE = "ScaledVideoStreamState";
    public static final String HDMI_AUDIO_STREAM_STATE = "HDMIAudioStreamState";
    public static final String VIDEO_NATIVE = "VideoNative";
    public static final String VIDEO_SCALED = "VideoScaled";
    public static final String HDMI_AUDIO = "HDMIAudio";
    public static final String UPTIME = "Uptime";
    public static final String VIDEO_RESOLUTION = "VideoInput#Resolution";
    public static final String DEVICE_MODE = "DeviceMode";
    public static final String LOCATE_MODE = "Configuration#LocateMode";
    public static final String RESUME_STREAMING = "Configuration#ResumeStreaming";

    // Paths
    public static final String BAUD_RATE_PATH = "/configuration/baud_rate";
    public static final String DATA_BITS_PATH = "/configuration/data_bits";
    public static final String STOP_BITS_PATH = "/configuration/stop_bits";
    public static final String PARITY_PATH = "/configuration/parity";
    public static final String VERSION_PATH = "/configuration/version";
    public static final String CONFIGURATION_PATH = "/configuration";
    public static final String FUNCTION_CHOICES_PATH = "/function/choices";
    public static final String FUNCTION_VALUE_PATH = "/function/value";
    public static final String INPUTS_PATH = "/inputs";
    public static final String VALUE_PATH = "/value";
    public static final String DESCRIPTION_PATH = "/description";
    public static final String TYPE_PATH = "/type";
    public static final String NAME_PATH = "/name";
    public static final String CONFIGURATION_SOURCE_CHOICES_PATH = "/configuration/source/choices";
    public static final String CONFIGURATION_SOURCE_VALUE_PATH = "/configuration/source/value";
    public static final String INDEX_PATH = "/index";
    public static final String DEVICE_ID_PATH = "/device_id";
    public static final String STREAM_PATH = "/streams";
    public static final String SUBSCRIPTIONS_PATH = "/subscriptions";
    public static final String NODES_PATH = "/nodes";

    // Inputs
    public static final String MAIN = "main";
    public static final String AUDIO = "audio";

    // Status
    public static final String UNSUPPORTED = "UNSUPPORTED";
    public static final String SUCCESS = "SUCCESS";
    public static final String PROCESSING = "PROCESSING";
    public static final String STOPPED = "STOPPED";
    public static final String STREAMING = "STREAMING";

}
