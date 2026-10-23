plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.20"
    id("org.jetbrains.intellij") version "1.16.0"
}

group = "com.aogg"
version = "1.4.0"

repositories {
    mavenCentral()
}

intellij {
    version.set("2023.3")
    type.set("PS") // PhpStorm
    plugins.set(listOf())
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "17"
    }

    patchPluginXml {
        sinceBuild.set("233")
        untilBuild.set("262.*")
    }

    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
    }

    // 打包产物同步复制到项目根目录 distributions/（目录不存在自动创建，zip 文件名跟随项目 version）
    val copyPluginZipToDistributions = register<Copy>("copyPluginZipToDistributions") {
        group = "build"
        description = "复制 buildPlugin 产出的插件 zip 到项目根目录 distributions/"
        from(buildPlugin) {
            include("*.zip")
        }
        into(layout.projectDirectory.dir("distributions"))
    }
    buildPlugin {
        finalizedBy(copyPluginZipToDistributions)
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.mockito:mockito-core:5.8.0")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
