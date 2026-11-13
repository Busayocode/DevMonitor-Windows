plugins {
    kotlin("jvm") version "2.1.10"
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(17)
}

tasks.register<JavaExec>("smokeTest") {
    group = "verification"
    description = "Compile and run the dependency-free gateway scheduler smoke tests."
    dependsOn(tasks.testClasses)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.aziitech.devmonitor.gateway.GatewayCoreSmokeTestKt")
}
