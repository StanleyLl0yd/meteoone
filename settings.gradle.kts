import org.gradle.api.initialization.resolve.RepositoriesMode

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

rootProject.name = "MeteoOne"

include(":app")
include(":core:model")
include(":core:network")
include(":core:location")
include(":core:database")
include(":core:preferences")
include(":forecast:domain")
include(":forecast:data")
include(":forecast:repository")
include(":verification:domain")
include(":verification:data")

include(":backend:gateway")
