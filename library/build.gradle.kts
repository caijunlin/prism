val versionName = "1.0.0"

val jdkVersion = 21
val flavors = listOf("x5", "native", "normal")

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.maven.publish)
}

kotlin {
    jvmToolchain(jdkVersion)
}

android {
    namespace = "com.github.caijunlin.prism"
    version = versionName
    compileSdk {
        version = release(libs.versions.compileSdk.get().toInt())
    }

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    flavorDimensions += "prism"
    productFlavors {
        create("x5") { dimension = "prism" }
        create("native") { dimension = "prism" }
        create("normal") {
            isDefault = true
            dimension = "prism"
        }
    }
    sourceSets {
        val eglKotlinPath = "src/core/egl/kotlin"
        getByName("x5") {
            assets.srcDir("src/facets/x5/assets")
            jniLibs.srcDir("src/facets/x5/jniLibs")
            kotlin.srcDirs("src/facets/x5/kotlin", eglKotlinPath)
        }
        getByName("native") {
            kotlin.srcDirs("src/facets/native/kotlin", eglKotlinPath)
        }
        getByName("normal") {
            kotlin.srcDir("src/facets/normal/kotlin")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    publishing {
        for (flavorName in flavors) {
            singleVariant("${flavorName}Release") {
                withSourcesJar()
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(jdkVersion)
        targetCompatibility = JavaVersion.toVersion(jdkVersion)
    }

    buildFeatures {
        buildConfig = true
    }

    libraryVariants.all {
        val variantName = this.name
        outputs.all {
            val output = this as? com.android.build.gradle.internal.api.BaseVariantOutputImpl
            if (output != null) {
                output.outputFileName = "${android.namespace}-${variantName}-${versionName}.aar"
            }
        }
    }

}

dependencies {
    "x5Implementation"(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
    listOf("x5Implementation", "nativeImplementation").forEach { add(it, libs.libvlc) }
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}

// 配置 Maven 发布
publishing {
    publications {
        flavors.forEach { flavorName ->
            create<MavenPublication>("${flavorName}Release") {
                groupId = "com.github.caijunlin"
                artifactId = flavorName
                version = versionName
                afterEvaluate {
                    from(components["${flavorName}Release"])
                }
            }
        }
    }
}

// 自动发布
tasks.register("createRelease") {
    description = ""
    group = "publishing"
    notCompatibleWithConfigurationCache("")
    doLast {
        val checkProcess = ProcessBuilder("gh", "release", "view", versionName)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
        if (checkProcess.waitFor() == 0) {
            println("Release [$versionName] exist")
            return@doLast
        }
        println("Release: $versionName ...")
        ProcessBuilder(
            "gh",
            "release",
            "create",
            versionName,
            "--title",
            versionName,
            "--generate-notes"
        )
            .inheritIO()
            .start()
            .waitFor()
        println("Success!")
    }
}