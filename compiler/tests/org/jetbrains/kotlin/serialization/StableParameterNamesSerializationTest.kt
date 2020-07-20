/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.serialization

import com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.analyzer.common.CommonResolverForModuleFactory
import org.jetbrains.kotlin.backend.common.serialization.metadata.KlibMetadataMonolithicSerializer
import org.jetbrains.kotlin.backend.common.serialization.metadata.KlibMetadataVersion
import org.jetbrains.kotlin.builtins.DefaultBuiltIns
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.config.addKotlinSourceRoot
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.LanguageVersionSettingsImpl
import org.jetbrains.kotlin.config.languageVersionSettings
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.impl.FunctionDescriptorImpl
import org.jetbrains.kotlin.konan.util.KlibMetadataFactories
import org.jetbrains.kotlin.library.KotlinLibraryVersioning
import org.jetbrains.kotlin.library.ToolingSingleFileKlibResolveStrategy
import org.jetbrains.kotlin.library.impl.BuiltInsPlatform
import org.jetbrains.kotlin.library.impl.KotlinLibraryWriterImpl
import org.jetbrains.kotlin.library.metadata.NativeTypeTransformer
import org.jetbrains.kotlin.library.metadata.NullFlexibleTypeDeserializer
import org.jetbrains.kotlin.library.resolveSingleFileKlib
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.platform.CommonPlatforms
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter.Companion.CLASSIFIERS_MASK
import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter.Companion.FUNCTIONS_MASK
import org.jetbrains.kotlin.resolve.scopes.MemberScope
import org.jetbrains.kotlin.storage.LockBasedStorageManager
import org.jetbrains.kotlin.test.KotlinTestUtils
import org.jetbrains.kotlin.test.TestCaseWithTmpdir
import org.jetbrains.kotlin.util.DummyLogger
import org.jetbrains.kotlin.utils.addToStdlib.assertedCast
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.kotlin.konan.file.File as KFile

class StableParameterNamesSerializationTest : TestCaseWithTmpdir() {
    private val SOURCE_FILE = "compiler/testData/serialization/stableParameterNames/test.kt"

    private fun loadModuleFromSourceFile(): ModuleDescriptor {
        val configuration = KotlinTestUtils.newConfiguration()
        configuration.put(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, MessageCollector.NONE)
        configuration.put(CommonConfigurationKeys.MODULE_NAME, KotlinTestUtils.TEST_MODULE_NAME)
        configuration.addKotlinSourceRoot(SOURCE_FILE)

        val rootDisposable = Disposer.newDisposable()
        try {
            val environment = KotlinCoreEnvironment.createForTests(
                parentDisposable = rootDisposable,
                initialConfiguration = configuration,
                extensionConfigs = EnvironmentConfigFiles.METADATA_CONFIG_FILES
            )

            return CommonResolverForModuleFactory.analyzeFiles(
                files = environment.getSourceFiles(),
                moduleName = Name.special("<${KotlinTestUtils.TEST_MODULE_NAME}>"),
                dependOnBuiltIns = true,
                languageVersionSettings = environment.configuration.languageVersionSettings,
                targetPlatform = CommonPlatforms.defaultCommonPlatform
            ) { content ->
                environment.createPackagePartProvider(content.moduleContentScope)
            }.moduleDescriptor
        } finally {
            Disposer.dispose(rootDisposable)
        }
    }

    private fun serializeModule(module: ModuleDescriptor, klibFile: File) {
        val serializer = KlibMetadataMonolithicSerializer(
            languageVersionSettings = LanguageVersionSettingsImpl.DEFAULT,
            metadataVersion = KlibMetadataVersion.INSTANCE,
            skipExpects = false
        )

        val serializedMetadata = serializer.serializeModule(module)

        val libraryName = module.name.asString().removeSurrounding("<", ">")
        val library = KotlinLibraryWriterImpl(
            libDir = KFile(klibFile.path),
            moduleName = libraryName,
            versions = KotlinLibraryVersioning(
                libraryVersion = null,
                compilerVersion = null,
                abiVersion = null,
                metadataVersion = KlibMetadataVersion.INSTANCE.toString(),
                irVersion = null
            ),
            builtInsPlatform = BuiltInsPlatform.COMMON,
            nativeTargets = emptyList(),
            nopack = true,
            shortName = libraryName
        )

        library.addMetadata(serializedMetadata)
        library.commit()
    }

    private fun deserializeModule(klibFile: File): ModuleDescriptor {
        val library = resolveSingleFileKlib(
            libraryFile = KFile(klibFile.path),
            logger = DummyLogger,
            strategy = ToolingSingleFileKlibResolveStrategy
        )

        val metadataFactories =
            KlibMetadataFactories(
                { DefaultBuiltIns.Instance },
                NullFlexibleTypeDeserializer,
                NativeTypeTransformer()
            )

        val module = metadataFactories.DefaultDeserializedDescriptorFactory.createDescriptor(
            library = library,
            languageVersionSettings = LanguageVersionSettingsImpl.DEFAULT,
            storageManager = LockBasedStorageManager.NO_LOCKS,
            builtIns = DefaultBuiltIns.Instance,
            packageAccessHandler = null
        )
        module.setDependencies(listOf(module))

        return module
    }

    private fun collectCallables(module: ModuleDescriptor): List<FunctionDescriptorImpl> {
        fun DeclarationDescriptor.castToFunctionImpl(): FunctionDescriptorImpl =
            assertedCast { "Not an instance of ${FunctionDescriptorImpl::class.java}: ${this::class.java}, $this" }

        val result = mutableListOf<FunctionDescriptorImpl>()

        fun recurse(memberScope: MemberScope) {
            memberScope
                .getContributedDescriptors(DescriptorKindFilter(FUNCTIONS_MASK or CLASSIFIERS_MASK))
                .forEach { descriptor ->
                    when (descriptor) {
                        is SimpleFunctionDescriptor -> {
                            if (descriptor.kind != CallableMemberDescriptor.Kind.FAKE_OVERRIDE)
                                result += descriptor.castToFunctionImpl()
                        }
                        is ClassDescriptor -> {
                            descriptor.constructors.mapTo(result) { it.castToFunctionImpl() }
                            recurse(descriptor.unsubstitutedMemberScope)
                        }
                    }
                }
        }

        recurse(module.getPackage(FqName.ROOT).memberScope)

        return result
    }

    private val FunctionDescriptorImpl.signature: String
        get() = buildString {
            append(fqNameSafe)
            append('(')
            valueParameters.joinTo(this, ",") { it.type.constructor.declarationDescriptor?.fqNameSafe?.asString().orEmpty() }
            append(')')
        }

    fun test() {
        val originalModule = loadModuleFromSourceFile()
        val originalCallables = collectCallables(originalModule)

        assertTrue { originalCallables.isNotEmpty() }
        assertTrue { originalCallables.all { it.hasStableParameterNames() } }

        originalCallables.forEach { it.setHasStableParameterNames(false) }

        val metaFile = File(tmpdir, File(SOURCE_FILE).name + ".klib")
        serializeModule(originalModule, metaFile)

        val deserializedModule = deserializeModule(metaFile)
        val deserializedCallables = collectCallables(deserializedModule)

        assertEquals(
            expected = originalCallables.map { it.signature }.toSet(),
            actual = deserializedCallables.map { it.signature }.toSet(),
            "Mismatched sets of callables found"
        )

        assertTrue { deserializedCallables.all { !it.hasStableParameterNames() } }
    }
}
