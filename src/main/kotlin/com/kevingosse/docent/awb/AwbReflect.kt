package com.kevingosse.docent.awb

/**
 * The generic (AWB-free) reflection primitives shared by the per-variant reflective seams
 * (`DocentSeamCheck`, `WorkbenchSessionDirectory`, `DocentEventNotifier`). The *targets* these resolve
 * (class FQNs, getter shapes) differ 262↔263 and live per-variant (see `AwbNames` + each seam file); the
 * mechanics of loading a class or invoking a zero-arg getter do not, so they exist once here.
 */
internal object AwbReflect {

    /** Load a class by FQN without initializing it, via the calling module's classloader (which sees the
     *  compiled-against AWB plugin classes) — exactly what the FQN-matched reflection sites resolve. */
    fun load(cl: ClassLoader, fqn: String): Class<*>? =
        runCatching { Class.forName(fqn, false, cl) }.getOrNull()

    /** The zero-arg public method [name] on [c], or null if absent. */
    fun zeroArg(c: Class<*>, name: String) = runCatching { c.getMethod(name) }.getOrNull()

    /** Invoke the zero-arg getter [method] on [target] and return its result as a String, or null. */
    fun invokeString(target: Any, method: String): String? =
        runCatching { target.javaClass.getMethod(method).invoke(target) as? String }.getOrNull()
}
