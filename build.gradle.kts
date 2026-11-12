plugins {
    java
}

group = "fr.coffres"
version = "1.2.1"

val paperApi = "io.papermc.paper:paper-api:26.2.build.135-stable"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly(paperApi)
    // Le serveur de test (sans Minecraft) a besoin de Gson et d'Adventure à l'exécution.
    testImplementation(paperApi)
    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
}

tasks.processResources {
    filteringCharset = "UTF-8"
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.jar {
    archiveFileName = "Coffres-${project.version}.jar"
}

// Lance uniquement le site web avec des données de démonstration : ./gradlew runDev
tasks.register<JavaExec>("runDev") {
    group = "application"
    description = "Démarre le site Coffres avec des fausses données (sans serveur Minecraft)."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "fr.coffres.dev.DevServer"
    args(file("src/main/resources/web").absolutePath, (project.findProperty("port") ?: "8125").toString())
    workingDir = layout.buildDirectory.dir("dev").get().asFile
    doFirst { workingDir.mkdirs() }
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
}
