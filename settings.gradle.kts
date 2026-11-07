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

rootProject.name = "tapflow"
include(":apps:android-card", ":apps:android-pos", ":packages:protocol-kotlin", ":services:payment-api")
include(":packages:android-auth")
