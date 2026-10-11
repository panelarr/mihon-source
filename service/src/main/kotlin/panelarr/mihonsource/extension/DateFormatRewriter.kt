// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.extension

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * Makes extensions create Android-style (ICU) date formats: every `new SimpleDateFormat(…)`
 * in extension bytecode becomes `new IcuSimpleDateFormat(…)`, a SimpleDateFormat subclass
 * from the host. Only creation sites change; types, fields and calls keep referring to
 * SimpleDateFormat, so nothing else needs to link differently. Classes that don't mention
 * SimpleDateFormat are returned untouched.
 */
object DateFormatRewriter {
    private const val JDK = "java/text/SimpleDateFormat"
    const val HOST = "panelarr/mihonsource/compat/IcuSimpleDateFormat"
    private val SUPPORTED_CONSTRUCTORS =
        setOf(
            "()V",
            "(Ljava/lang/String;)V",
            "(Ljava/lang/String;Ljava/util/Locale;)V",
            "(Ljava/lang/String;Ljava/text/DateFormatSymbols;)V",
        )
    private val MARKER = JDK.toByteArray()

    fun rewrite(
        className: String,
        bytes: ByteArray,
    ): ByteArray {
        if (!contains(bytes, MARKER)) return bytes
        val reader = ClassReader(bytes)
        // Frames stay valid: only the class created changes, and it's a subtype of the original.
        val writer = ClassWriter(reader, 0)
        var changed = false
        reader.accept(
            object : ClassVisitor(Opcodes.ASM9, writer) {
                override fun visitMethod(
                    access: Int,
                    name: String?,
                    descriptor: String?,
                    signature: String?,
                    exceptions: Array<out String>?,
                ): MethodVisitor {
                    val next = super.visitMethod(access, name, descriptor, signature, exceptions)
                    return object : MethodVisitor(Opcodes.ASM9, next) {
                        // NEWs we rewrote and whose constructor call is still to come. Constructor
                        // calls without one (a subclass calling super) are left alone.
                        private var pending = 0

                        override fun visitTypeInsn(
                            opcode: Int,
                            type: String?,
                        ) {
                            if (opcode == Opcodes.NEW && type == JDK) {
                                pending++
                                changed = true
                                super.visitTypeInsn(opcode, HOST)
                            } else {
                                super.visitTypeInsn(opcode, type)
                            }
                        }

                        override fun visitMethodInsn(
                            opcode: Int,
                            owner: String?,
                            name: String?,
                            descriptor: String?,
                            isInterface: Boolean,
                        ) {
                            val ours = opcode == Opcodes.INVOKESPECIAL && owner == JDK && name == "<init>" && pending > 0
                            if (ours) {
                                check(
                                    descriptor in SUPPORTED_CONSTRUCTORS,
                                ) { "unexpected SimpleDateFormat constructor $descriptor in $className" }
                                pending--
                                super.visitMethodInsn(opcode, HOST, name, descriptor, isInterface)
                            } else {
                                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface)
                            }
                        }
                    }
                }
            },
            0,
        )
        return if (changed) writer.toByteArray() else bytes
    }

    private fun contains(
        haystack: ByteArray,
        needle: ByteArray,
    ): Boolean {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return true
        }
        return false
    }
}
