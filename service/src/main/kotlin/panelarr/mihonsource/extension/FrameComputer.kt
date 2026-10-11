// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.extension

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter

/**
 * Recomputes stack map frames for classes that come out of dex2jar without them (the
 * JVM refuses such class files). Computing frames needs common superclasses, which are
 * looked up in the extension's own classes first, then in the host classloader, without
 * loading any extension code.
 */
class FrameComputer(
    private val classBytes: Map<String, ByteArray>,
    private val host: ClassLoader,
) {
    private val supers = mutableMapOf<String, String?>()
    private val interfaces = mutableMapOf<String, Boolean>()

    fun compute(bytes: ByteArray): ByteArray {
        val reader = ClassReader(bytes)
        val writer =
            object : ClassWriter(COMPUTE_FRAMES) {
                override fun getCommonSuperClass(
                    type1: String,
                    type2: String,
                ): String = commonSuperClass(type1, type2)

                override fun getClassLoader(): ClassLoader = host
            }
        reader.accept(writer, ClassReader.SKIP_FRAMES)
        return writer.toByteArray()
    }

    private fun commonSuperClass(
        a: String,
        b: String,
    ): String {
        if (a == b) return a
        if (isInterface(a) || isInterface(b)) return OBJECT
        val ancestorsOfA = generateSequence(a) { superOf(it) }.toSet()
        return generateSequence(b) { superOf(it) }.firstOrNull { it in ancestorsOfA } ?: OBJECT
    }

    private fun superOf(name: String): String? =
        supers.getOrPut(name) {
            val own = classBytes[name]
            when {
                name == OBJECT -> null
                own != null -> ClassReader(own).superName
                else -> hostClass(name)?.superclass?.name?.replace('.', '/') ?: OBJECT
            }
        }

    private fun isInterface(name: String): Boolean =
        interfaces.getOrPut(name) {
            val own = classBytes[name]
            if (own != null) {
                ClassReader(own).access and org.objectweb.asm.Opcodes.ACC_INTERFACE != 0
            } else {
                hostClass(name)?.isInterface ?: false
            }
        }

    private fun hostClass(name: String): Class<*>? = runCatching { Class.forName(name.replace('/', '.'), false, host) }.getOrNull()

    private companion object {
        const val OBJECT = "java/lang/Object"
    }
}
