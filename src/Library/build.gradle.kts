plugins {
    kotlin("multiplatform") version "1.8.10"
    kotlin("plugin.serialization") version("1.8.10")
    id("maven-publish")
}

group = "me.jeroen"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

kotlin {
    jvm {
        compilations.all {
            kotlinOptions.jvmTarget = "1.8"
        }
        withJava()
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
        }
    }
    js(BOTH) {
        browser {
            commonWebpackConfig {
                // cssSupport.enabled = true
            }
        }
    }
    val hostOs = System.getProperty("os.name")
    val isMingwX64 = hostOs.startsWith("Windows")
    val nativeTarget = when {
        hostOs == "Mac OS X" -> macosX64("native")
        hostOs == "Linux" -> linuxX64("native")
        isMingwX64 -> mingwX64("native")
        else -> throw GradleException("Host OS is not supported in Kotlin/Native.")
    }

    
    sourceSets {
        val commonMain by getting {
            val ktor_version: String by project
            val okhttp_version: String by project
            dependencies {
                // Moved all dependencies to jvmMain
                val coroutines_version = "1.6.4"
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutines_version")
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        val jvmMain by getting {
            val ktor_version: String by project
            val okhttp_version: String by project
            dependencies {
                implementation("io.ktor:ktor-client-core:$ktor_version")
                implementation("io.ktor:ktor-client-cio:$ktor_version")
                implementation("io.ktor:ktor-client-java:$ktor_version")
                implementation("io.ktor:ktor-client-okhttp:$ktor_version")

                // to parse json
                implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor_version")
                implementation("io.ktor:ktor-client-content-negotiation:$ktor_version")

                // to utilize the okhttp listener factory
                implementation("com.squareup.okhttp3:okhttp:$okhttp_version")
            }
        }
        val jvmTest by getting
        val jsMain by getting
        val jsTest by getting
        val nativeMain by getting
        val nativeTest by getting
    }
}
