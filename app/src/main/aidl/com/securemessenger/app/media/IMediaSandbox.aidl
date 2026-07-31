package com.securemessenger.app.media;

/**
 * The only way into the media sandbox.
 *
 * One method, framework types only. The ADR sketched custom Parcelables for the
 * request and the result; this deliberately does not use them. A Parcelable is
 * parsing code that runs on BOTH sides of this boundary, and the whole purpose
 * of the boundary is that the untrusted side shares as little of our code as
 * possible. Bundle and ParcelFileDescriptor are marshalled by the platform, so
 * nothing of ours parses anything at the one place we most want parser-free.
 *
 * Neither direction puts bytes in the Binder transaction. That buffer is about
 * 1MB shared across every in-flight call between two processes, while media
 * here reaches 4MB and a decoded 2160px frame is far larger — so both
 * directions pass a file descriptor onto shared memory and only the descriptor
 * crosses.
 */
interface IMediaSandbox {

    /**
     * Decode an image into a bounded ARGB_8888 pixel buffer.
     *
     * @param src         read-only fd onto the already-DECRYPTED image bytes.
     *                    Decryption stays in the main process on purpose: the
     *                    sandbox must never hold a key, only content its sender
     *                    already had.
     * @param byteLength  how much of src is real data.
     * @param maxDimension the bound decodeSampledBitmap applies today, so the
     *                    sandbox decodes small directly instead of decoding
     *                    huge and shrinking.
     *
     * @return Bundle carrying KEY_OK / KEY_ERR, and on success KEY_PIXELS,
     *         KEY_WIDTH, KEY_HEIGHT. See MediaSandbox for the key names and
     *         error codes — they are declared once, on the trusted side.
     */
    Bundle decodeImage(in ParcelFileDescriptor src, long byteLength, int maxDimension);
}
