import java.util.Properties

plugins { id("com.android.application") }

val distributionProperties =
    Properties().apply {
        providers.environmentVariable("GALAXY_GLASS_SIGNING_PROPERTIES").orNull?.let { path ->
            file(path).reader(Charsets.UTF_8).use { load(it) }
            for (key in listOf("storeFile", "storePassword", "keyAlias", "keyPassword")) {
                check(!getProperty(key).isNullOrBlank()) {
                    "Missing release signing property: $key"
                }
            }
        }
    }

android {
    namespace = "dev.ryuya.galaxyglass"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }
    buildToolsVersion = "37.0.0"
    buildFeatures { buildConfig = true }
    defaultConfig {
        applicationId = "dev.ryuya.galaxyglass"
        minSdk = 33
        targetSdk = 37
        versionCode = 33
        versionName = "0.4.12"
    }
    signingConfigs {
        if (!distributionProperties.isEmpty)
            create("distribution") {
                storeFile = file(distributionProperties.getProperty("storeFile"))
                storePassword = distributionProperties.getProperty("storePassword")
                keyAlias = distributionProperties.getProperty("keyAlias")
                keyPassword = distributionProperties.getProperty("keyPassword")
            }
    }
    buildTypes {
        release {
            isDebuggable = false
            // Reflection and external hook contracts must retain their names.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("distribution")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint { abortOnError = true }
}

dependencies {
    implementation("org.luckypray:dexkit:2.3.0")
    implementation("io.github.libxposed:service:102.0.0")

    compileOnly("io.github.libxposed:api:102.0.0")
    implementation("org.smali:dexlib2:2.5.2")
    implementation("com.google.android.material:material:1.14.0")
    testImplementation("junit:junit:4.13.2")
}

val exportRuntimeComponents =
    tasks.register("exportRuntimeComponents") {
        doLast {
            val components =
                configurations
                    .getByName("debugRuntimeClasspath")
                    .resolvedConfiguration
                    .resolvedArtifacts
                    .map { it.moduleVersion.id.toString() }
                    .toSortedSet()
            val file = layout.buildDirectory.file("runtime-components.txt").get().asFile
            file.parentFile.mkdirs()
            file.writeText(components.joinToString("\n") + "\n")
        }
    }
val generatedLicenses = layout.buildDirectory.dir("generated/licenseAssets")

android.sourceSets
    .getByName("main")
    .assets
    .directories
    .add(generatedLicenses.get().asFile.absolutePath)

val dependencyLicenses =
    tasks.register("generateDependencyLicenses") {
        dependsOn(exportRuntimeComponents)
        inputs.file(rootProject.file("third_party/components.tsv"))
        inputs.dir(rootProject.file("third_party"))
        inputs.file(rootProject.file("LICENSE"))
        inputs.file(layout.buildDirectory.file("runtime-components.txt"))
        outputs.dir(generatedLicenses)
        doLast {
            val rows =
                rootProject
                    .file("third_party/components.tsv")
                    .readLines()
                    .filter { it.isNotBlank() && !it.startsWith("#") }
                    .map { it.split('\t') }
            val licensed = rows.map { it[0] }.toSet()
            val actual =
                layout.buildDirectory
                    .file("runtime-components.txt")
                    .get()
                    .asFile
                    .readLines()
                    .filter { it.isNotBlank() }
                    .toSet() + "io.github.libxposed:api:102.0.0"
            check(actual == licensed) {
                "Review dependency licenses: missing=${actual-licensed}; obsolete=${licensed-actual}"
            }
            val out = generatedLicenses.get().asFile
            out.mkdirs()
            rootProject.file("LICENSE").copyTo(out.resolve("APP_LICENSE.txt"), true)
            val texts = out.resolve("licenses")
            texts.mkdirs()
            for (name in rows.map { it[2] }.distinct()) {
                rootProject.file("third_party/$name").copyTo(texts.resolve(name), true)
            }
            out.resolve("THIRD_PARTY_NOTICES.txt").delete()
            out.resolve("LICENSE_CATALOG.tsv").writeText(
                rows.joinToString(separator = "\n", postfix = "\n") { it.joinToString("\t") },
            )
        }
    }

tasks.named("preBuild").configure { dependsOn(dependencyLicenses) }
