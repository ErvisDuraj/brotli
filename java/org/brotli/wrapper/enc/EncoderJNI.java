/* Copyright 2017 Google Inc. All Rights Reserved.

   Distributed under MIT license.
   See file LICENSE for detail or copy at https://opensource.org/licenses/MIT
*/

package org.brotli.wrapper.enc;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * JNI wrapper for brotli encoder.
 */
class EncoderJNI {
  private static native ByteBuffer nativeCreate(long[] context);
  private static native void nativePush(long[] context, int length);
  private static native ByteBuffer nativePull(long[] context);
  private static native void nativeDestroy(long[] context);
  private static native boolean nativeAttachDictionary(long[] context, ByteBuffer dictionary);
  private static native ByteBuffer nativePrepareDictionary(ByteBuffer dictionary, long type);
  private static native void nativeDestroyDictionary(ByteBuffer dictionary);

  enum Operation {
    PROCESS,
    FLUSH,
    FINISH
  }

  static ByteBuffer prepareDictionary(ByteBuffer dictionary) {
    if (!dictionary.isDirect()) {
      throw new IllegalArgumentException("only direct buffers allowed");
    }
    return nativePrepareDictionary(dictionary, 0);
  }

  static void destroyDictionary(ByteBuffer dictionary) {
    if (!dictionary.isDirect()) {
      throw new IllegalArgumentException("only direct buffers allowed");
    }
    // TODO: add a seal to ensure that this buffer is from native code.
    nativeDestroyDictionary(dictionary);
  }

  static class Wrapper {
    protected final long[] context = new long[5];
    private final ByteBuffer inputBuffer;
    private boolean fresh = true;

    Wrapper(int inputBufferSize, int quality, int lgwin)
        throws IOException {
      this.context[1] = inputBufferSize;
      this.context[2] = quality;
      this.context[3] = lgwin;
      this.inputBuffer = nativeCreate(this.context);
      if (this.context[0] == 0) {
        throw new IOException("failed to initialize native brotli encoder");
      }
      this.context[1] = 1;
      this.context[2] = 0;
      this.context[3] = 0;
    }

    boolean attachDictionary(ByteBuffer dictionary) {
      if (!dictionary.isDirect()) {
        throw new IllegalArgumentException("only direct buffers allowed");
      }
      if (context[0] == 0) {
        throw new IllegalStateException("brotli decoder is already destroyed");
      }
      if (!fresh) {
        throw new IllegalStateException("decoding is already started");
      }
      return nativeAttachDictionary(context, dictionary);
    }

    void push(Operation op, int length) {
      if (length < 0) {
        throw new IllegalArgumentException("negative block length");
      }
      if (context[0] == 0) {
        throw new IllegalStateException("brotli encoder is already destroyed");
      }
      if (!isSuccess() || hasMoreOutput()) {
        throw new IllegalStateException("pushing input to encoder in unexpected state");
      }
      if (hasRemainingInput() && length != 0) {
        throw new IllegalStateException("pushing input to encoder over previous input");
      }
      context[1] = op.ordinal();
      fresh = false;
      nativePush(context, length);
    }

    boolean isSuccess() {
      return context[1] != 0;
    }

    boolean hasMoreOutput() {
      return context[2] != 0;
    }

    boolean hasRemainingInput() {
      return context[3] != 0;
    }

    boolean isFinished() {
      return context[4] != 0;
    }

    ByteBuffer getInputBuffer() {
      return inputBuffer;
    }

    ByteBuffer pull() {
      if (context[0] == 0) {
        throw new IllegalStateException("brotli encoder is already destroyed");
      }
      if (!isSuccess() || !hasMoreOutput()) {
        throw new IllegalStateException("pulling while data is not ready");
      }
      fresh = false;
      return nativePull(context);
    }

    /**
     * Releases native resources.
     */
    void destroy() {
      if (context[0] == 0) {
        throw new IllegalStateException("brotli encoder is already destroyed");
      }
      nativeDestroy(context);
      context[0] = 0;
    }

    @Override
    protected void finalize() throws Throwable {
      if (context[0] != 0) {
        /* TODO: log resource leak? */
        destroy();
      }
      super.finalize();
    }
  }
}
