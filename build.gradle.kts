plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

group = "dev.qqgate"
version = "1.2.0"
repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    // Gson 由服务端运行时提供，仅编译/测试需要显式声明
    compileOnly("com.google.code.gson:gson:2.11.0")
    implementation("org.java-websocket:Java-WebSocket:1.6.0")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("com.google.code.gson:gson:2.11.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.yaml:snakeyaml:2.2")
}
tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
}

tasks.processResources {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching("plugin.yml") { expand(props) }
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}

tasks.shadowJar {
    archiveClassifier.set("")
    relocate("org.java_websocket", "dev.qqgate.shade.jws")
}

tasks.build { dependsOn(tasks.shadowJar) }
tasks.jar { enabled = false }
