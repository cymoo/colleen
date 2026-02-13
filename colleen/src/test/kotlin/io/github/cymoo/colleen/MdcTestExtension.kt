package io.github.cymoo.colleen

import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.slf4j.MDC

class MdcTestExtension : BeforeEachCallback, AfterEachCallback {

    override fun beforeEach(context: ExtensionContext) {
        val testClass = context.testClass.orElse(null)?.simpleName ?: "Unknown"
        val testMethod = context.displayName

        MDC.put("testClass", testClass)
        MDC.put("testMethod", testMethod)
    }

    override fun afterEach(context: ExtensionContext) {
        MDC.remove("testClass")
        MDC.remove("testMethod")
    }
}