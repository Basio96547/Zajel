package com.securemessenger.app.ui.screens.verification

/*
 * generateQRCode() used to live here: a second QR encoder, 200px, no
 * error-correction ladder, and no read-back check, drawing a payload
 * (bare hex) that nothing else in the app could parse. It was the
 * mechanical half of the app having two "رمز QR"s. Both halves are gone —
 * this screen now renders QrImage.identityPayload through QrImage.render,
 * the same code and the same renderer as the pairing screen, which also
 * means it inherits the ladder and the "prove it reads back before showing
 * it" guarantee that this encoder never had.
 */

sealed class VerificationStatus {
    object None : VerificationStatus()
    object Verified : VerificationStatus()

    /** A readable identity key that isn't this contact's — the only state that should alarm anyone. */
    object Failed : VerificationStatus()

    /**
     * The scan was not an identity key at all. Previously indistinguishable
     * from [Failed], which meant scanning any unrelated QR — including this
     * app's own *pairing* code — reported a possible wiretap. See
     * SecureRepository.verifyScannedKey.
     */
    object NotAKey : VerificationStatus()
}
