package com.zen3515.homeassistant_mobile_ble_proxy.proxy

import org.bouncycastle.crypto.InvalidCipherTextException
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.math.ec.rfc7748.X25519
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal class EspHomeNoiseTransport(
    private val input: InputStream,
    private val output: OutputStream,
    nodeName: String,
    macAddress: String,
    preSharedKey: ByteArray,
) : EspHomeTransport {
    private val sendCipher: NoiseCipherState
    private val receiveCipher: NoiseCipherState

    init {
        require(preSharedKey.size == KEY_SIZE_BYTES) { "Noise PSK must be 32 bytes" }

        val clientHello = readRawFrame(MAX_HANDSHAKE_FRAME_SIZE)
            ?: throw IOException("Client disconnected before Noise hello")

        writeRawFrame(buildServerHello(nodeName, macAddress))

        val handshake = HandshakeResponder(
            prologue = buildPrologue(clientHello),
            preSharedKey = preSharedKey.copyOf(),
        )

        val clientHandshakeFrame = readRawFrame(MAX_HANDSHAKE_FRAME_SIZE)
            ?: throw IOException("Client disconnected during Noise handshake")

        if (clientHandshakeFrame.isEmpty()) {
            sendHandshakeReject("Empty handshake message")
            throw IOException("Empty handshake message")
        }

        if (clientHandshakeFrame[0] != HANDSHAKE_SUCCESS) {
            sendHandshakeReject("Bad handshake error byte")
            throw IOException("Bad handshake error byte: ${clientHandshakeFrame[0].toInt() and 0xFF}")
        }

        try {
            handshake.readClientMessage(clientHandshakeFrame.copyOfRange(1, clientHandshakeFrame.size))
        } catch (e: InvalidCipherTextException) {
            sendHandshakeReject("Handshake MAC failure")
            throw IOException("Noise handshake MAC failure", e)
        } catch (e: Exception) {
            sendHandshakeReject("Handshake error")
            throw IOException("Noise handshake failed", e)
        }

        val serverHandshakePayload = handshake.writeServerMessage(EMPTY_BYTES)
        writeRawFrame(byteArrayOf(HANDSHAKE_SUCCESS) + serverHandshakePayload)

        val transportCiphers = handshake.split()
        sendCipher = transportCiphers.send
        receiveCipher = transportCiphers.receive
    }

    override fun readFrame(): EspHomeFrameCodec.Frame? {
        val encryptedPayload = readRawFrame(MAX_DATA_FRAME_SIZE) ?: return null

        val decrypted = try {
            receiveCipher.decrypt(EMPTY_BYTES, encryptedPayload)
        } catch (e: InvalidCipherTextException) {
            throw IOException("Noise decrypt failed", e)
        }

        if (decrypted.size < NOISE_DATA_HEADER_SIZE) {
            throw IOException("Bad data packet: decrypted payload too short (${decrypted.size})")
        }

        val typeId = ((decrypted[0].toInt() and 0xFF) shl 8) or (decrypted[1].toInt() and 0xFF)
        val payloadLength = ((decrypted[2].toInt() and 0xFF) shl 8) or (decrypted[3].toInt() and 0xFF)

        if (payloadLength > decrypted.size - NOISE_DATA_HEADER_SIZE) {
            throw IOException(
                "Bad data packet: payload length $payloadLength exceeds available ${decrypted.size - NOISE_DATA_HEADER_SIZE}",
            )
        }

        return EspHomeFrameCodec.Frame(
            typeId = typeId,
            payload = decrypted.copyOfRange(NOISE_DATA_HEADER_SIZE, NOISE_DATA_HEADER_SIZE + payloadLength),
        )
    }

    override fun writeFrame(typeId: Int, payload: ByteArray) {
        if (payload.size > MAX_NOISE_PAYLOAD_SIZE) {
            throw IOException("Payload too large for Noise frame: ${payload.size}")
        }

        val plain = ByteArray(NOISE_DATA_HEADER_SIZE + payload.size)
        plain[0] = (typeId ushr 8).toByte()
        plain[1] = typeId.toByte()
        plain[2] = (payload.size ushr 8).toByte()
        plain[3] = payload.size.toByte()
        payload.copyInto(plain, NOISE_DATA_HEADER_SIZE)

        val encrypted = sendCipher.encrypt(EMPTY_BYTES, plain)
        writeRawFrame(encrypted)
    }

    private fun sendHandshakeReject(reason: String) {
        runCatching {
            writeRawFrame(byteArrayOf(HANDSHAKE_FAILURE) + reason.toByteArray(Charsets.UTF_8))
        }
    }

    @Throws(IOException::class)
    private fun readRawFrame(limit: Int): ByteArray? {
        val indicator = input.read()
        if (indicator == -1) {
            return null
        }

        if (indicator == 0x00) {
            // Plaintext ESPHome clients send 0x00 preamble; surface a typed error so the
            // caller can return a "requires encryption" hint.
            throw PlaintextProbeException()
        }

        if (indicator != FRAME_INDICATOR) {
            throw IOException("Invalid Noise frame indicator: $indicator")
        }

        val high = input.read()
        val low = input.read()
        if (high == -1 || low == -1) {
            throw IOException("Unexpected EOF while reading Noise frame header")
        }

        val frameSize = (high shl 8) or low
        if (frameSize > limit) {
            throw IOException("Bad packet: frame size $frameSize exceeds limit $limit")
        }

        val payload = ByteArray(frameSize)
        readFully(payload)
        return payload
    }

    @Throws(IOException::class)
    private fun writeRawFrame(payload: ByteArray) {
        if (payload.size > MAX_NOISE_WIRE_PAYLOAD_SIZE) {
            throw IOException("Noise frame too large: ${payload.size}")
        }

        output.write(FRAME_INDICATOR)
        output.write((payload.size ushr 8) and 0xFF)
        output.write(payload.size and 0xFF)
        output.write(payload)
        output.flush()
    }

    @Throws(IOException::class)
    private fun readFully(target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val read = input.read(target, offset, target.size - offset)
            if (read < 0) {
                throw IOException("Unexpected EOF while reading Noise frame payload")
            }
            offset += read
        }
    }

    private class HandshakeResponder(
        prologue: ByteArray,
        private val preSharedKey: ByteArray,
    ) {
        private val symmetricState = SymmetricState()
        private val random = SecureRandom()
        private var step = 0
        private var remoteEphemeral: ByteArray? = null

        init {
            symmetricState.initialize(PROTOCOL_NAME)
            symmetricState.mixHash(prologue)
        }

        fun readClientMessage(message: ByteArray) {
            check(step == 0) { "Unexpected handshake step" }
            if (message.size < X25519_PUBLIC_KEY_SIZE + CHACHA20POLY1305_TAG_BYTES) {
                throw IOException("Handshake message too short: ${message.size}")
            }

            val remotePublic = message.copyOfRange(0, X25519_PUBLIC_KEY_SIZE)
            remoteEphemeral = remotePublic

            // psk0 applies before the first message pattern token.
            symmetricState.mixKeyAndHash(preSharedKey)

            symmetricState.mixHash(remotePublic)
            symmetricState.mixKey(remotePublic)

            val encryptedPayload = message.copyOfRange(X25519_PUBLIC_KEY_SIZE, message.size)
            symmetricState.decryptAndHash(encryptedPayload)
            step = 1
        }

        fun writeServerMessage(payload: ByteArray): ByteArray {
            check(step == 1) { "Unexpected handshake step" }

            val remotePublic = remoteEphemeral ?: throw IOException("Missing remote ephemeral key")

            val localPrivate = ByteArray(X25519_PRIVATE_KEY_SIZE)
            X25519.generatePrivateKey(random, localPrivate)

            val localPublic = ByteArray(X25519_PUBLIC_KEY_SIZE)
            X25519.generatePublicKey(localPrivate, 0, localPublic, 0)

            symmetricState.mixHash(localPublic)
            symmetricState.mixKey(localPublic)

            val sharedSecret = ByteArray(X25519_SHARED_SECRET_SIZE)
            val ok = X25519.calculateAgreement(localPrivate, 0, remotePublic, 0, sharedSecret, 0)
            if (!ok) {
                throw IOException("Failed to calculate X25519 shared secret")
            }

            symmetricState.mixKey(sharedSecret)

            val encryptedPayload = symmetricState.encryptAndHash(payload)
            step = 2
            return localPublic + encryptedPayload
        }

        fun split(): TransportCiphers {
            check(step == 2) { "Handshake not complete" }
            return symmetricState.splitResponder()
        }
    }

    private class SymmetricState {
        private var chainingKey = ByteArray(HASH_SIZE)
        private var hash = ByteArray(HASH_SIZE)
        private val cipherState = NoiseCipherState()

        fun initialize(protocolName: ByteArray) {
            hash = if (protocolName.size <= HASH_SIZE) {
                ByteArray(HASH_SIZE).also { target ->
                    protocolName.copyInto(target)
                }
            } else {
                sha256(protocolName)
            }
            chainingKey = hash.copyOf()
            cipherState.reset()
        }

        fun mixHash(data: ByteArray) {
            hash = sha256(hash, data)
        }

        fun mixKey(inputKeyMaterial: ByteArray) {
            val outputs = hkdf(chainingKey, inputKeyMaterial, 2)
            chainingKey = outputs[0]
            cipherState.initializeKey(outputs[1])
        }

        fun mixKeyAndHash(inputKeyMaterial: ByteArray) {
            val outputs = hkdf(chainingKey, inputKeyMaterial, 3)
            chainingKey = outputs[0]
            mixHash(outputs[1])
            cipherState.initializeKey(outputs[2])
        }

        fun encryptAndHash(plaintext: ByteArray): ByteArray {
            val ciphertext = cipherState.encrypt(hash, plaintext)
            mixHash(ciphertext)
            return ciphertext
        }

        fun decryptAndHash(ciphertext: ByteArray): ByteArray {
            val plaintext = cipherState.decrypt(hash, ciphertext)
            mixHash(ciphertext)
            return plaintext
        }

        fun splitResponder(): TransportCiphers {
            val outputs = hkdf(chainingKey, EMPTY_BYTES, 2)

            val receive = NoiseCipherState().apply { initializeKey(outputs[0]) }
            val send = NoiseCipherState().apply { initializeKey(outputs[1]) }

            return TransportCiphers(send = send, receive = receive)
        }
    }

    private class NoiseCipherState {
        private var key: ByteArray? = null
        private var nonce: Long = 0

        fun reset() {
            key = null
            nonce = 0
        }

        fun initializeKey(newKey: ByteArray) {
            key = newKey.copyOf(KEY_SIZE_BYTES)
            nonce = 0
        }

        fun encrypt(ad: ByteArray, plaintext: ByteArray): ByteArray {
            val activeKey = key ?: return plaintext.copyOf()
            val cipher = ChaCha20Poly1305()
            val parameters = AEADParameters(
                KeyParameter(activeKey),
                CHACHA20POLY1305_TAG_BYTES * 8,
                packNonce(nonce),
                ad,
            )
            cipher.init(true, parameters)

            val output = ByteArray(cipher.getOutputSize(plaintext.size))
            var outLength = cipher.processBytes(plaintext, 0, plaintext.size, output, 0)
            outLength += cipher.doFinal(output, outLength)
            nonce += 1
            return output.copyOf(outLength)
        }

        fun decrypt(ad: ByteArray, ciphertext: ByteArray): ByteArray {
            val activeKey = key ?: return ciphertext.copyOf()
            val cipher = ChaCha20Poly1305()
            val parameters = AEADParameters(
                KeyParameter(activeKey),
                CHACHA20POLY1305_TAG_BYTES * 8,
                packNonce(nonce),
                ad,
            )
            cipher.init(false, parameters)

            val output = ByteArray(cipher.getOutputSize(ciphertext.size))
            var outLength = cipher.processBytes(ciphertext, 0, ciphertext.size, output, 0)
            outLength += cipher.doFinal(output, outLength)
            nonce += 1
            return output.copyOf(outLength)
        }
    }

    private data class TransportCiphers(
        val send: NoiseCipherState,
        val receive: NoiseCipherState,
    )

    companion object {
        private const val FRAME_INDICATOR = 0x01
        private const val HANDSHAKE_SUCCESS: Byte = 0x00
        private const val HANDSHAKE_FAILURE: Byte = 0x01

        private const val MAX_HANDSHAKE_FRAME_SIZE = 128
        private const val MAX_DATA_FRAME_SIZE = 32 * 1024
        private const val MAX_NOISE_WIRE_PAYLOAD_SIZE = 0xFFFF
        private const val NOISE_DATA_HEADER_SIZE = 4

        private const val HASH_SIZE = 32
        private const val KEY_SIZE_BYTES = 32
        private const val CHACHA20POLY1305_TAG_BYTES = 16
        private const val MAX_NOISE_PAYLOAD_SIZE = MAX_DATA_FRAME_SIZE - NOISE_DATA_HEADER_SIZE - CHACHA20POLY1305_TAG_BYTES
        private const val X25519_PRIVATE_KEY_SIZE = 32
        private const val X25519_PUBLIC_KEY_SIZE = 32
        private const val X25519_SHARED_SECRET_SIZE = 32

        private val EMPTY_BYTES = ByteArray(0)
        private val PROTOCOL_NAME = "Noise_NNpsk0_25519_ChaChaPoly_SHA256".toByteArray(Charsets.US_ASCII)
        private val PROLOGUE_PREFIX = "NoiseAPIInit".toByteArray(Charsets.US_ASCII)

        private fun buildPrologue(clientHello: ByteArray): ByteArray {
            val prologue = ByteArray(PROLOGUE_PREFIX.size + 2 + clientHello.size)
            PROLOGUE_PREFIX.copyInto(prologue, 0)
            prologue[PROLOGUE_PREFIX.size] = (clientHello.size ushr 8).toByte()
            prologue[PROLOGUE_PREFIX.size + 1] = clientHello.size.toByte()
            clientHello.copyInto(prologue, PROLOGUE_PREFIX.size + 2)
            return prologue
        }

        private fun buildServerHello(nodeName: String, macAddress: String): ByteArray {
            val nameBytes = nodeName.toByteArray(Charsets.UTF_8)
            val macBytes = macAddress.toByteArray(Charsets.US_ASCII)

            val hello = ByteArray(1 + nameBytes.size + 1 + macBytes.size + 1)
            var offset = 0
            hello[offset++] = FRAME_INDICATOR.toByte()

            nameBytes.copyInto(hello, offset)
            offset += nameBytes.size
            hello[offset++] = 0

            macBytes.copyInto(hello, offset)
            offset += macBytes.size
            hello[offset] = 0

            return hello
        }

        private fun hkdf(chainingKey: ByteArray, ikm: ByteArray, outputs: Int): List<ByteArray> {
            require(outputs in 1..3) { "Noise HKDF supports 1-3 outputs" }

            val tempKey = hmacSha256(chainingKey, ikm)
            val result = ArrayList<ByteArray>(outputs)

            var previous = EMPTY_BYTES
            for (index in 1..outputs) {
                val message = ByteArray(previous.size + 1)
                if (previous.isNotEmpty()) {
                    previous.copyInto(message)
                }
                message[message.size - 1] = index.toByte()
                previous = hmacSha256(tempKey, message)
                result += previous
            }

            return result
        }

        private fun sha256(vararg parts: ByteArray): ByteArray {
            val digest = MessageDigest.getInstance("SHA-256")
            parts.forEach(digest::update)
            return digest.digest()
        }

        private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(data)
        }

        private fun packNonce(value: Long): ByteArray {
            val out = ByteArray(12)
            var nonce = value
            for (index in 0 until 8) {
                out[4 + index] = (nonce and 0xFF).toByte()
                nonce = nonce ushr 8
            }
            return out
        }
    }

    class PlaintextProbeException :
        IOException("Plaintext client attempted to connect to encrypted API")
}
