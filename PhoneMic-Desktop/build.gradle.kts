plugins {
    kotlin("jvm") version "2.0.20"
    application
}

repositories {
    mavenCentral()
}

// JavaCPP ships FFmpeg's native libraries as per-platform classifier jars. Pull only the one
// for the machine doing the build - CI builds each OS on its own runner, so every package
// gets the right natives without dragging ~200 MB of other platforms along.
val nativePlatform = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch")
    when {
        os.contains("win") -> "windows-x86_64"
        os.contains("mac") && arch == "aarch64" -> "macosx-arm64"
        os.contains("mac") -> "macosx-x86_64"
        else -> "linux-x86_64"
    }
}

dependencies {
    // QR code generation for the pairing panel - pure Java, no native code.
    implementation("com.google.zxing:core:3.5.3")

    // Camera: H.264 decoding of the phone's video (FFmpeg, LGPL build) ...
    val javacppVersion = "1.5.14"
    implementation("org.bytedeco:javacpp:$javacppVersion")
    runtimeOnly("org.bytedeco:javacpp:$javacppVersion:$nativePlatform")
    implementation("org.bytedeco:ffmpeg:8.1.2-$javacppVersion")
    runtimeOnly("org.bytedeco:ffmpeg:8.1.2-$javacppVersion:$nativePlatform")
    // ... and the Windows OBS virtual camera's shared memory (kernel32/registry via JNA).
    implementation("net.java.dev.jna:jna:5.19.1")
    implementation("net.java.dev.jna:jna-platform:5.19.1")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.scylla.tool.phonemic.pc.MainKt")
    // macOS reads the Dock label from this VM flag at launch, not the JFrame title -
    // without it, `./gradlew run` shows the raw java launcher ("java" + Duke icon).
    // Other JVMs reject the flag outright, so only pass it on macOS.
    if (System.getProperty("os.name").lowercase().contains("mac")) {
        applicationDefaultJvmArgs = listOf("-Xdock:name=Phone Mic")
    }
}
