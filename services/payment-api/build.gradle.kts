import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin { jvmToolchain(21) }
application { mainClass.set("com.tapflow.api.ApplicationKt") }

dependencies {
    implementation(project(":packages:protocol-kotlin"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.serialization.json)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgres)
    implementation(libs.hikari)
    implementation(libs.postgres)
    implementation(libs.argon2)
    implementation(libs.ktor.server.call.id)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.server.body.limit)
    runtimeOnly(libs.logback)
    testImplementation(libs.ktor.server.test)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.testcontainers.postgres)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test { useJUnitPlatform() }

tasks.register<JavaExec>("seedDemo") {
    group = "application"
    description = "Seed synthetic demo users and a merchant; passwords must be supplied locally."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.tapflow.api.db.SeedDemoKt")
}

tasks.withType<JavaExec>().configureEach {
    val localConfig = rootProject.file(".env")
    if (localConfig.isFile) {
        val properties = Properties().apply { localConfig.reader(Charsets.UTF_8).use { load(it) } }
        properties.forEach { key, value ->
            if (System.getenv(key.toString()) == null) environment(key.toString(), value.toString())
        }
    }
}

tasks.processResources {
    from(rootProject.file("contracts/openapi.yaml")) { into("contracts") }
}

sourceSets.test { resources.srcDir(rootProject.file("contracts/fixtures")) }
