package com.waitleisure.util

import java.security.MessageDigest

/**
 * PIN 的存取。
 *
 * L1 用裸 SHA-256（无盐）。对「防自己」这个威胁模型是够的 ——
 * 攻击者得先拿到设备文件系统才能爆破。
 * 如果以后要做成多人/商用产品，应换成 PBKDF2 或 Argon2 并加随机盐。
 */
object Pin {

    fun hash(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(pin.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    fun verify(pin: String, storedHash: String?): Boolean {
        if (storedHash.isNullOrEmpty()) return false
        return hash(pin) == storedHash
    }

    /** PIN 至少要 4 位，否则锁机形同虚设。 */
    fun isValid(pin: String): Boolean = pin.length >= 4 && pin.all { it.isDigit() }
}
