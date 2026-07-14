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
        // GeckoView (bundled modern Firefox engine — replaces the stuck system WebView 95).
        maven { url = uri("https://maven.mozilla.org/maven2/") }
    }
}

rootProject.name = "X3GeoLibre"
include(":app")
