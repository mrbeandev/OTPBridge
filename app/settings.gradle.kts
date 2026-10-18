pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "OTPBridge"
include(":android")
// Relocate the native module's build output; all Android files stay in app/.
project(":android").projectDir = file("android")
