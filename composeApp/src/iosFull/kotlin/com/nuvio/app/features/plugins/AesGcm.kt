package com.nuvio.app.features.plugins

/**
 * AES-GCM (NIST SP 800-38D) built only from a public AES block primitive.
 *
 * iOS: CommonCrypto's GCM entry points (CCCryptorGCMEncrypt/Decrypt/Final) are private API
 * and App Store Connect rejects binaries that reference them (ITMS-90338), so the plugin
 * runtime computes GCM here: CTR keystream from AES-ECB (public CCCrypt) plus GHASH.
 *
 * [aesEcb] must encrypt a buffer whose length is a multiple of 16 with AES-ECB, no padding.
 * Output layout matches WebCrypto: ciphertext || 16-byte tag. No additional authenticated
 * data (the plugin API does not pass any).
 */
internal object AesGcm {
    private const val BLOCK = 16
    const val TAG_LENGTH = 16

    fun seal(aesEcb: (ByteArray) -> ByteArray, iv: ByteArray, plaintext: ByteArray): ByteArray {
        val h = aesEcb(ByteArray(BLOCK))
        val j0 = initialCounter(h, iv)
        val ciphertext = ctr(aesEcb, inc32(j0), plaintext)
        val tag = tag(aesEcb, h, j0, ciphertext)
        return ciphertext + tag
    }

    fun open(aesEcb: (ByteArray) -> ByteArray, iv: ByteArray, sealed: ByteArray): ByteArray {
        require(sealed.size >= TAG_LENGTH) { "Data too short for GCM decryption" }
        val ciphertext = sealed.copyOfRange(0, sealed.size - TAG_LENGTH)
        val receivedTag = sealed.copyOfRange(sealed.size - TAG_LENGTH, sealed.size)
        val h = aesEcb(ByteArray(BLOCK))
        val j0 = initialCounter(h, iv)
        val expectedTag = tag(aesEcb, h, j0, ciphertext)
        var diff = 0
        for (i in 0 until TAG_LENGTH) diff = diff or (expectedTag[i].toInt() xor receivedTag[i].toInt())
        require(diff == 0) { "AES-GCM tag verification failed" }
        return ctr(aesEcb, inc32(j0), ciphertext)
    }

    private fun initialCounter(h: ByteArray, iv: ByteArray): ByteArray {
        require(iv.isNotEmpty()) { "AES-GCM requires an IV" }
        if (iv.size == 12) return iv + byteArrayOf(0, 0, 0, 1)
        return ghash(h, iv, lengthsBlock(0, iv.size.toLong() * 8))
    }

    private fun tag(aesEcb: (ByteArray) -> ByteArray, h: ByteArray, j0: ByteArray, ciphertext: ByteArray): ByteArray {
        val s = ghash(h, ciphertext, lengthsBlock(0, ciphertext.size.toLong() * 8))
        val ekj0 = aesEcb(j0)
        return ByteArray(BLOCK) { (ekj0[it].toInt() xor s[it].toInt()).toByte() }
    }

    /** CTR with the GCM 32-bit counter increment, keystream produced in one AES-ECB call. */
    private fun ctr(aesEcb: (ByteArray) -> ByteArray, firstCounter: ByteArray, input: ByteArray): ByteArray {
        if (input.isEmpty()) return ByteArray(0)
        val blocks = (input.size + BLOCK - 1) / BLOCK
        val counters = ByteArray(blocks * BLOCK)
        var counter = firstCounter
        for (b in 0 until blocks) {
            counter.copyInto(counters, b * BLOCK)
            counter = inc32(counter)
        }
        val keystream = aesEcb(counters)
        return ByteArray(input.size) { (input[it].toInt() xor keystream[it].toInt()).toByte() }
    }

    private fun inc32(block: ByteArray): ByteArray {
        val out = block.copyOf()
        var i = BLOCK - 1
        while (i >= BLOCK - 4) {
            val v = (out[i].toInt() and 0xFF) + 1
            out[i] = v.toByte()
            if (v <= 0xFF) break
            i--
        }
        return out
    }

    private fun lengthsBlock(aadBits: Long, dataBits: Long): ByteArray {
        val out = ByteArray(BLOCK)
        for (i in 0 until 8) {
            out[7 - i] = (aadBits ushr (8 * i)).toByte()
            out[15 - i] = (dataBits ushr (8 * i)).toByte()
        }
        return out
    }

    /** GHASH_H(data zero-padded to 16 bytes || lengths). */
    private fun ghash(h: ByteArray, data: ByteArray, lengths: ByteArray): ByteArray {
        val hHi = h.readLong(0)
        val hLo = h.readLong(8)
        var yHi = 0L
        var yLo = 0L
        var offset = 0
        while (offset < data.size) {
            val chunk = ByteArray(BLOCK)
            data.copyInto(chunk, 0, offset, minOf(offset + BLOCK, data.size))
            yHi = yHi xor chunk.readLong(0)
            yLo = yLo xor chunk.readLong(8)
            val (mHi, mLo) = gfMultiply(yHi, yLo, hHi, hLo)
            yHi = mHi; yLo = mLo
            offset += BLOCK
        }
        yHi = yHi xor lengths.readLong(0)
        yLo = yLo xor lengths.readLong(8)
        val (rHi, rLo) = gfMultiply(yHi, yLo, hHi, hLo)
        val out = ByteArray(BLOCK)
        out.writeLong(0, rHi)
        out.writeLong(8, rLo)
        return out
    }

    /** Multiplication in GF(2^128) with the GCM bit order (SP 800-38D, Algorithm 1). */
    private fun gfMultiply(xHi: Long, xLo: Long, yHi: Long, yLo: Long): Pair<Long, Long> {
        var zHi = 0L
        var zLo = 0L
        var vHi = yHi
        var vLo = yLo
        for (i in 0 until 128) {
            val bit = if (i < 64) (xHi ushr (63 - i)) and 1L else (xLo ushr (127 - i)) and 1L
            if (bit == 1L) {
                zHi = zHi xor vHi
                zLo = zLo xor vLo
            }
            val lsb = vLo and 1L
            vLo = (vLo ushr 1) or (vHi shl 63)
            vHi = vHi ushr 1
            if (lsb == 1L) vHi = vHi xor -0x1f00000000000000L // 0xE1 << 56
        }
        return zHi to zLo
    }

    private fun ByteArray.readLong(offset: Int): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (this[offset + i].toLong() and 0xFF)
        return v
    }

    private fun ByteArray.writeLong(offset: Int, value: Long) {
        for (i in 0 until 8) this[offset + i] = (value ushr (56 - 8 * i)).toByte()
    }
}
