plugins {
    kotlin("jvm") version "2.0.20"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    // QR code generation for the pairing panel - pure Java, no native code.
    implementation("com.google.zxing:core:3.5.3")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.scylla.tool.phonemic.pc.MainKt")
    // macOS reads the Dock label from this VM flag at launch, not the JFrame title -
    // without it, `./gradlew run` shows the raw java launcher ("java" + Duke icon).
    applicationDefaultJvmArgs = listOf("-Xdock:name=Phone Mic")
}
