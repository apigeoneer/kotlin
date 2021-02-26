/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.test.runners.ir.interpreter

import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey
import org.jetbrains.kotlin.config.JVMConfigurationKeys
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.descriptors.IrBuiltIns
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrErrorExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.interpreter.IrInterpreter
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.util.IdSignature
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.test.TargetBackend
import org.jetbrains.kotlin.test.backend.handlers.AbstractIrHandler
import org.jetbrains.kotlin.test.backend.ir.IrBackendInput
import org.jetbrains.kotlin.test.builders.TestConfigurationBuilder
import org.jetbrains.kotlin.test.directives.AdditionalFilesDirectives
import org.jetbrains.kotlin.test.directives.ConfigurationDirectives
import org.jetbrains.kotlin.test.directives.JvmEnvironmentConfigurationDirectives
import org.jetbrains.kotlin.test.directives.LanguageSettingsDirectives
import org.jetbrains.kotlin.test.directives.model.DirectivesContainer
import org.jetbrains.kotlin.test.frontend.classic.ClassicFrontend2IrConverter
import org.jetbrains.kotlin.test.frontend.classic.ClassicFrontendFacade
import org.jetbrains.kotlin.test.model.BinaryKind
import org.jetbrains.kotlin.test.model.DependencyKind
import org.jetbrains.kotlin.test.model.FrontendKinds
import org.jetbrains.kotlin.test.model.TestModule
import org.jetbrains.kotlin.test.parseAnalysisFlags
import org.jetbrains.kotlin.test.runners.AbstractKotlinCompilerWithTargetBackendTest
import org.jetbrains.kotlin.test.services.EnvironmentConfigurator
import org.jetbrains.kotlin.test.services.TestServices
import org.jetbrains.kotlin.test.services.compilerConfigurationProvider
import org.jetbrains.kotlin.test.services.configuration.CommonEnvironmentConfigurator
import org.jetbrains.kotlin.test.services.configuration.JvmEnvironmentConfigurator
import org.jetbrains.kotlin.test.services.moduleStructure
import org.jetbrains.kotlin.test.services.sourceProviders.AdditionalDiagnosticsSourceFilesProvider
import org.jetbrains.kotlin.test.services.sourceProviders.CodegenHelpersSourceFilesProvider
import org.jetbrains.kotlin.test.services.sourceProviders.CoroutineHelpersSourceFilesProvider
import org.junit.Assert
import org.junit.jupiter.api.Assumptions

open class AbstractIrInterpreterBlackBoxTest : AbstractKotlinCompilerWithTargetBackendTest(TargetBackend.JVM_IR) {
    override fun TestConfigurationBuilder.configuration() {
        globalDefaults {
            frontend = FrontendKinds.ClassicFrontend
            targetPlatform = JvmPlatforms.defaultJvmPlatform
            artifactKind = BinaryKind.NoArtifact
            targetBackend = TargetBackend.JVM_IR
            dependencyKind = DependencyKind.Source
        }

        defaultDirectives {
            +JvmEnvironmentConfigurationDirectives.FULL_JDK
            +JvmEnvironmentConfigurationDirectives.WITH_STDLIB
            LanguageSettingsDirectives.LANGUAGE with "-CompileTimeCalculations"
        }

        useConfigurators(
            ::CommonEnvironmentConfigurator,
            ::JvmEnvironmentConfigurator,
            ::IrInterpreterEnvironmentConfigurator
        )

        useAdditionalSourceProviders(
            ::AdditionalDiagnosticsSourceFilesProvider,
            ::CoroutineHelpersSourceFilesProvider,
            ::CodegenHelpersSourceFilesProvider,
        )

        useFrontendFacades(::ClassicFrontendFacade)
        useFrontend2BackendConverters(::ClassicFrontend2IrConverter)
        useBackendHandlers(::IrInterpreterBoxHandler)
    }
}

class IrInterpreterBoxHandler(testServices: TestServices) : AbstractIrHandler(testServices) {
    private lateinit var irBuiltins: IrBuiltIns
    private val irFiles = mutableListOf<IrFile>()

    override fun processModule(module: TestModule, info: IrBackendInput) {
        irBuiltins = info.backendInput.irModuleFragment.irBuiltins
        irFiles.addAll(info.backendInput.irModuleFragment.files)
    }

    override fun processAfterAllModules(someAssertionWasFailed: Boolean) {
        val modules = testServices.moduleStructure.modules

        Assumptions.assumeFalse(modules.flatMap { it.files }.any { it.name.endsWith(".java") }) { "Can't interpret java files" }
        Assumptions.assumeFalse(modules.flatMap { it.files }.singleOrNull()?.name == "sync.kt") { "Ignore `await` method call interpretation" }

        Assumptions.assumeFalse(AdditionalFilesDirectives.WITH_COROUTINES in testServices.moduleStructure.allDirectives) { "Ignore coroutines" }
        Assumptions.assumeFalse(JvmEnvironmentConfigurationDirectives.WITH_REFLECT in testServices.moduleStructure.allDirectives) { "Ignore jvm reflection" }

        val configuration = testServices.compilerConfigurationProvider.getCompilerConfiguration(modules.last())
        val boxFunction = irFiles
            .flatMap { it.declarations }
            .filterIsInstance<IrFunction>()
            .first { it.name.asString() == "box" && it.valueParameters.isEmpty() }

        val boxIrCall = IrCallImpl(
            UNDEFINED_OFFSET, UNDEFINED_OFFSET, irBuiltins.stringType, boxFunction.symbol as IrSimpleFunctionSymbol, 0, 0
        )

        val interpreterResult = try {
            @Suppress("UNCHECKED_CAST")
            val irInterpreter = IrInterpreter(irBuiltins, configuration[CommonConfigurationKeys.IR_BODY_MAP] as Map<IdSignature, IrBody>)
            irInterpreter.interpret(boxIrCall, irFiles.last())
        } catch (e: Throwable) {
            val message = e.message
            if (message == "Cannot interpret get method on top level non const properties" || message == "Cannot interpret set method on top level properties") {
                Assumptions.assumeFalse(true) { message }
                return
            }
            throw e
        }

        if (interpreterResult is IrErrorExpression) assertions.fail { interpreterResult.description }
        if (interpreterResult !is IrConst<*>) assertions.fail { "Expect const, but returned ${interpreterResult::class.java}" }
        assertions.assertEquals("OK", interpreterResult.value)
    }
}

class IrInterpreterEnvironmentConfigurator(testServices: TestServices) : EnvironmentConfigurator(testServices) {
    private val fullRuntimeKlib = "build/js-ir-runtime/klib"

    override fun configureCompilerConfiguration(configuration: CompilerConfiguration, module: TestModule) {
        configuration.put(JVMConfigurationKeys.KLIB_PATH_FOR_COMPILE_TIME, fullRuntimeKlib)
    }
}