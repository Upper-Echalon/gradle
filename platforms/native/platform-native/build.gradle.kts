plugins {
    id("gradlebuild.distribution.api-java")
}

description = "Plugins, tasks and compiler infrastructure for compiling/linking code"

errorprone {
    disabledChecks.addAll(
        "DefaultCharset", // 2 occurrences
        "EqualsUnsafeCast", // 1 occurrences
        "GetClassOnClass", // 1 occurrences
        "ReferenceEquality", // 2 occurrences
        "StaticAssignmentInConstructor", // 1 occurrences
        "StringCharset", // 2 occurrences
    )
}

dependencies {
    api(projects.serviceProvider)
    api(projects.baseDiagnostics)
    api(projects.baseServices)
    api(projects.buildOperations)
    api(projects.core)
    api(projects.coreApi)
    api(projects.fileCollections)
    api(projects.files)
    api(projects.hashing)
    api(projects.logging)
    api(projects.modelCore)
    api(projects.native)
    api(projects.softwareDiagnostics)
    api(projects.stdlibJavaExtensions)
    api(projects.platformBase)
    api(projects.workers)

    api(libs.jspecify)
    api(libs.inject)
    api(libs.nativePlatform)
    api(libs.slf4jApi)

    implementation(projects.daemonServerWorker)
    implementation(projects.enterpriseLogging)
    implementation(projects.io)
    implementation(projects.loggingApi)
    implementation(projects.serviceLookup)

    implementation(libs.commonsLang)
    implementation(libs.commonsIo)
    implementation(libs.gson)
    implementation(libs.guava)
    implementation(libs.snakeyaml)

    testFixturesApi(projects.resources)
    testFixturesApi(testFixtures(projects.ide))
    testFixturesImplementation(testFixtures(projects.core))
    testFixturesImplementation(projects.internalIntegTesting)
    testFixturesImplementation(projects.native)
    testFixturesImplementation(projects.platformBase)
    testFixturesImplementation(projects.fileCollections)
    testFixturesImplementation(projects.processServices)
    testFixturesImplementation(projects.snapshots)
    testFixturesImplementation(libs.guava)
    testFixturesImplementation(libs.nativePlatform)
    testFixturesImplementation(libs.groovyXml)
    testFixturesImplementation(libs.commonsLang)
    testFixturesImplementation(libs.commonsIo)

    testImplementation(testFixtures(projects.core))
    testImplementation(testFixtures(projects.messaging))
    testImplementation(testFixtures(projects.platformBase))
    testImplementation(testFixtures(projects.modelCore))
    testImplementation(testFixtures(projects.baseServices))
    testImplementation(testFixtures(projects.snapshots))
    testImplementation(testFixtures(projects.time))

    testRuntimeOnly(projects.distributionsCore) {
        because("ProjectBuilder tests load services from a Gradle distribution.")
    }
    integTestDistributionRuntimeOnly(projects.distributionsNative) {
        because("Required 'ideNative' to test visual studio project file generation for generated sources")
    }
}

packageCycles {
    excludePatterns.add("org/gradle/nativeplatform/plugins/**")
    excludePatterns.add("org/gradle/nativeplatform/tasks/**")
    excludePatterns.add("org/gradle/nativeplatform/internal/resolve/**")
    excludePatterns.add("org/gradle/nativeplatform/toolchain/internal/**")
}

tasks.isolatedProjectsIntegTest {
    enabled = false
}
