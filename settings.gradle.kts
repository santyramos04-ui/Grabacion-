pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Owl"
// :app une los módulos; :vault y :collection no dependen entre sí (ver ARCHITECTURE.md).
include(":app", ":core", ":recorder", ":vault", ":collection")
