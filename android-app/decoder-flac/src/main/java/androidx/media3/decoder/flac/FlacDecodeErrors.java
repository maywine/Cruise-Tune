package androidx.media3.decoder.flac;

import androidx.media3.common.util.UnstableApi;

/** Exposes the package-private JNI error type without treating transport errors as bad audio. */
@UnstableApi
public final class FlacDecodeErrors {
  private FlacDecodeErrors() {}

  public static boolean isFrameDecodeFailure(Throwable error) {
    return error instanceof FlacDecoderJni.FlacFrameDecodeException;
  }
}
