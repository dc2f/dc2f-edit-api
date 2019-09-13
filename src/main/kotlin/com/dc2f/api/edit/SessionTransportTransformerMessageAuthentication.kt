/// copied from ktor
///  * Copyright 2014-2019 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.

package com.dc2f.api.edit

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Session transformer that appends an [algorithm] MAC (Message Authentication Code) hash of the input.
 * Where the input is either a session contents or a previous transformation.
 * It uses a specified [keySpec] when generating the Mac hash.
 *
 * @property keySpec is a secret key spec for message authentication
 * @property algorithm is a message authentication algorithm name
 */
class SessionTransportTransformerMessageAuthentication(val keySpec: SecretKeySpec, val algorithm: String = "HmacSHA256") {
    constructor(key: ByteArray, algorithm: String = "HmacSHA256") : this(SecretKeySpec(key, algorithm), algorithm)

    fun transformRead(transportValue: String): String? {
        val expectedSignature = transportValue.substringAfterLast('/', "")
        val value = transportValue.substringBeforeLast('/')
        if (MessageDigest.isEqual(mac(value).toByteArray(), expectedSignature.toByteArray()))
            return value
        return null
    }

    fun transformWrite(transportValue: String): String = "$transportValue/${mac(transportValue)}"

    private fun mac(value: String): String {
        val mac = Mac.getInstance(algorithm)
        mac.init(keySpec)

        return hex(mac.doFinal(value.toByteArray()))
    }
}

private val digits = "0123456789abcdef".toCharArray()

/**
 * Encode [bytes] as a HEX string with no spaces, newlines and `0x` prefixes.
 */
fun hex(bytes: ByteArray): String {
    val result = CharArray(bytes.size * 2)
    var resultIndex = 0
    val digits = digits

    for (index in 0 until bytes.size) {
        val b = bytes[index].toInt() and 0xff
        result[resultIndex++] = digits[b shr 4]
        result[resultIndex++] = digits[b and 0x0f]
    }

    return String(result)
}

