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

rootProject.name = "iptv-player"
// -PdesktopOnly: the Linux app alone (its CI has no Android SDK set up).
if (!providers.gradleProperty("desktopOnly").isPresent) include(":app")
include(":desktop")
