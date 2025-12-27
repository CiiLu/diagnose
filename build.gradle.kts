plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinxSerialization)
}

group = "me.user"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

val appendPayloadTask = tasks.register("appendPayload") {
    group = "build"

    val payloadFile = file("src/nativeMain/resources/JavaInfo.jar")

    doLast {
        val exePath = extensions.extraProperties["exePath"] as? String
            ?: throw GradleException("Executable path not found!")

        val exeFile = file(exePath)

        if (!payloadFile.exists()) {
            println(">>> Payload file not found: ${payloadFile.absolutePath}. Skipping append.")
            return@doLast
        }
        if (!exeFile.exists()) return@doLast

        val payloadBytes = payloadFile.readBytes()

        exeFile.appendBytes(payloadBytes)
    }
}

kotlin {
    val hostOs = System.getProperty("os.name")
    val isArm64 = System.getProperty("os.arch") == "aarch64"
    val isMingwX64 = hostOs.startsWith("Windows")

    val nativeTarget = when {
        hostOs == "Mac OS X" && isArm64 -> macosArm64("native")
        hostOs == "Mac OS X" && !isArm64 -> macosX64("native")
        hostOs == "Linux" && isArm64 -> linuxArm64("native")
        hostOs == "Linux" && !isArm64 -> linuxX64("native")
        isMingwX64 -> mingwX64("native")
        else -> throw GradleException("Host OS is not supported in Kotlin/Native.")
    }

    nativeTarget.apply {
        binaries {
            executable {
                entryPoint = "main"

                linkTaskProvider.configure {
                    if (isMingwX64) {

                        finalizedBy(appendPayloadTask)

                        val outputFile = this.outputFile.get()
                        doFirst {
                            appendPayloadTask.configure {
                                extensions.extraProperties["exePath"] = outputFile.absolutePath
                            }
                        }
                    }
                }
            }
        }
    }

    sourceSets {
        nativeMain.dependencies {
            val ktorVersion = "3.3.3"

            implementation("io.ktor:ktor-client-core:$ktorVersion")

            if (isMingwX64) {
                implementation("io.ktor:ktor-client-winhttp:$ktorVersion")
            } else {
                implementation("io.ktor:ktor-client-cio:$ktorVersion")
            }
            implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
            implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")

            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation(libs.kotlinxSerializationJson)
        }
    }
}

tasks.named("nativeTest") {
    enabled = false
}