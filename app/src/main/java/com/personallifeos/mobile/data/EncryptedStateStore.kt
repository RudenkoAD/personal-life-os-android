package com.personallifeos.mobile.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Small encrypted, replace-once store for the repository's durable state. */
internal interface DurableStore {
    fun read(): ByteArray?
    fun write(plain: ByteArray)
}

internal class EncryptedStateStore(context: Context) : DurableStore {
    private val directory = context.noBackupFilesDir.apply { mkdirs() }
    private val file = File(directory, "life-repository.bin")
    private val temporary = File(directory, "life-repository.bin.tmp")
    private val keyAlias = "personal-life-os.repository.v1"

    override fun read(): ByteArray? {
        val source = when {
            file.isFile -> file
            temporary.isFile -> temporary
            else -> return null
        }
        val packed = source.readBytes()
        require(packed.size > IV_LENGTH) { "Повреждено локальное хранилище" }
        val iv = packed.copyOfRange(0, IV_LENGTH)
        val ciphertext = packed.copyOfRange(IV_LENGTH, packed.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    /** Writes to a sibling temporary file and replaces the target atomically. */
    override fun write(plain: ByteArray) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(plain)
        val packed = cipher.iv + encrypted
        FileOutputStream(temporary).use { output ->
            output.write(packed)
            output.fd.sync()
        }
        if (!temporary.renameTo(file)) {
            throw IllegalStateException("Не удалось сохранить локальное состояние")
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = store.getKey(keyAlias, null)
        if (existing is SecretKey) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_BITS = 128
    }
}
