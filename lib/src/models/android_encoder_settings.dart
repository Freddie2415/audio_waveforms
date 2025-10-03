import '../../audio_waveforms.dart';

/// Class to specify encoder and output format settings for Android.
class AndroidEncoderSettings {
  /// Constructor for AndroidEncoderSettings.
  ///
  /// [androidEncoder] - Defines the encoder type for Android (default: AAC).
  /// [androidOutputFormat] - Specifies the output format for Android recordings (default: MPEG4).
  /// [audioDeviceId] - ID of the preferred audio input device (Android API 23+). If null, uses default device.
  const AndroidEncoderSettings({
    this.androidEncoder = AndroidEncoder.aac,
    this.androidOutputFormat = AndroidOutputFormat.mpeg4,
    this.audioDeviceId,
  });

  /// Encoder type for Android recordings.
  /// Default is AAC.
  final AndroidEncoder androidEncoder;

  /// Output format for Android recordings.
  /// Default is MPEG4.
  final AndroidOutputFormat androidOutputFormat;

  /// ID of the preferred audio input device for recording (Android API 23+).
  ///
  /// If null, the system default audio input device will be used.
  /// To get available device IDs, use Android's AudioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).
  ///
  /// Note: This feature requires Android 6.0 (API level 23) or higher.
  /// On lower versions, this parameter will be ignored.
  final int? audioDeviceId;
}
